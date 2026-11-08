package ruggedmobile.sbf

/** Minimal byte input, so the reader has no dependency on java.io. */
fun interface ByteSource {
    /** Reads up to [length] bytes into [buffer] at [offset]; returns the count, or -1 at end of input. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * A [ByteSource] over an in-memory array. [rewind] starts again from the beginning without
 * allocating, which is handy for replays and benchmarks.
 */
class ByteArraySource(
    private val data: ByteArray,
    private val start: Int = 0,
    private val end: Int = data.size,
) : ByteSource {
    private var pos = start

    init {
        require(start in 0..end && end <= data.size) { "range $start..$end outside array of ${data.size}" }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= end) return -1
        val n = minOf(length, end - pos)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }

    fun rewind() {
        pos = start
    }
}

/** Lets the collector look at the next block in the input without consuming it. */
fun interface BlockLookahead {
    /** The next valid block, or null at end of input. */
    fun peekNext(): SbfBlock?
}

/**
 * Scans a byte stream for SBF blocks (`GetNextBlock` / `CheckBlock` in sbfread.c).
 *
 * Every `'$'` byte is a candidate block start. A candidate is accepted when:
 * - its header is complete and the sync bytes are `$@`;
 * - `16 <= Length <= 65532`;
 * - the whole block is available;
 * - the CRC matches.
 *
 * On rejection, scanning resumes at the byte after the `'$'`, which gives the same
 * byte-by-byte resynchronisation as the C code.
 *
 * Like the C code, `Length % 4 == 0` is not enforced; the check is off by default for
 * compatibility with sbf2asc and can be enabled with [requireLengthMultipleOf4].
 *
 * **Zero-copy:** [next] and [peek] return views into the reader's own buffer instead of
 * copying each block into a new array. [next] always returns the same [SbfBlock] object, and
 * [peek] another one. The block returned by [next] stays valid, even across [peek], until the
 * following call to [next]. The buffer is allocated once and never grows, so the reader's
 * memory use is constant however long the stream runs.
 *
 * The reader is also a [BlockLookahead], so it can be passed straight to a
 * [MeasEpochCollector].
 */
class SbfBlockReader(
    private val source: ByteSource,
    bufferSize: Int = 1 shl 20,
    private val requireLengthMultipleOf4: Boolean = false,
) : BlockLookahead {
    // Capacity: the current block (<= MAX) must stay in the buffer while a whole candidate
    // block (<= MAX) is read behind it, which needs 2 * MAX.
    private val buf = ByteArray(maxOf(bufferSize, 2 * SbfLimits.MAX_BLOCK_LENGTH))
    private var start = 0
    private var end = 0
    private var eof = false
    private var bufferFileOffset = 0L

    /** Result of the last [scan]: where the accepted block is in [buf]. */
    private var foundOffset = 0
    private var foundLength = 0

    private val current = SbfBlock()
    private var currentValid = false

    private val peeked = SbfBlock()
    private var peekState = PEEK_NONE
    private var peekedEndOffset = 0L

    /** File offset just after the last block returned by [next]. */
    var position: Long = 0L
        private set

    /**
     * Number of candidate blocks rejected because of a CRC mismatch.
     *
     * As in the original port (fix vs C), each byte range is scanned once even with
     * look-ahead, so CRC errors are not counted twice. A `'$'` inside a payload can still
     * produce a spurious count.
     */
    var crcErrors: Int = 0
        private set

    /** Returns the next valid block, or null at end of input. The view is valid until the next call. */
    fun next(): SbfBlock? {
        // The previous block is released: its bytes may now be overwritten.
        currentValid = false
        if (peekState != PEEK_NONE) {
            val found = peekState == PEEK_FOUND
            peekState = PEEK_NONE
            if (!found) return null
            current.bind(buf, peeked.offset, peeked.length)
            currentValid = true
            position = peekedEndOffset
            return current
        }
        if (!scan()) return null
        current.bind(buf, foundOffset, foundLength)
        currentValid = true
        position = fileOffset(start)
        return current
    }

    /**
     * Returns the block [next] would return, without consuming it (`END_POS_DONT_CHANGE`).
     * The block last returned by [next] stays valid.
     */
    fun peek(): SbfBlock? {
        if (peekState == PEEK_NONE) {
            if (scan()) {
                peeked.bind(buf, foundOffset, foundLength)
                peekState = PEEK_FOUND
            } else {
                peekState = PEEK_EOF
            }
            peekedEndOffset = fileOffset(start)
        }
        return if (peekState == PEEK_FOUND) peeked else null
    }

    override fun peekNext(): SbfBlock? = peek()

    private fun fileOffset(index: Int): Long = bufferFileOffset + index

    /** Finds the next valid block from [start]; on success sets [foundOffset]/[foundLength]. */
    private fun scan(): Boolean {
        val dollar = '$'.code.toByte()
        val at = '@'.code.toByte()
        while (true) {
            if (start >= end && ensure(1) == 0) return false
            // Fast skip to the next '$' within the bytes already buffered.
            if (buf[start] != dollar) {
                var i = start + 1
                val e = end
                while (i < e && buf[i] != dollar) i++
                start = i
                continue
            }
            if (ensure(SbfLimits.HEADER_SIZE) < SbfLimits.HEADER_SIZE || buf[start + 1] != at) {
                start++
                continue
            }
            val length = (buf[start + 6].toInt() and 0xFF) or ((buf[start + 7].toInt() and 0xFF) shl 8)
            if (length > SbfLimits.MAX_BLOCK_LENGTH || length < SbfLimits.MIN_BLOCK_LENGTH ||
                (requireLengthMultipleOf4 && length % 4 != 0) ||
                ensure(length) < length
            ) {
                start++
                continue
            }
            if (!Crc16.isValid(buf, start, length)) {
                crcErrors++
                start++
                continue
            }
            foundOffset = start
            foundLength = length
            start += length
            return true
        }
    }

    /** Makes up to [n] bytes available from [start]; returns how many are available. */
    private fun ensure(n: Int): Int {
        if (end - start >= n) return n
        compact()
        while (end - start < n && !eof) {
            val r = source.read(buf, end, buf.size - end)
            if (r < 0) eof = true else end += r
        }
        return minOf(n, end - start)
    }

    /**
     * Moves the unscanned bytes `[start, end)` to the front of the buffer to make room. If the
     * block last returned by [next] is still valid, it is moved to the front first and the
     * unscanned bytes follow it, so the caller's view stays correct.
     */
    private fun compact() {
        if (currentValid) {
            val cOff = current.offset
            val cLen = current.length
            if (cOff == 0 && start == cLen) return
            // The current block always lies before `start`, so neither copy overwrites the other.
            if (cOff != 0) buf.copyInto(buf, 0, cOff, cOff + cLen)
            buf.copyInto(buf, cLen, start, end)
            bufferFileOffset += start - cLen
            end = cLen + (end - start)
            start = cLen
            current.moveTo(0)
        } else if (start > 0) {
            buf.copyInto(buf, 0, start, end)
            bufferFileOffset += start
            end -= start
            start = 0
        }
    }

    private companion object {
        const val PEEK_NONE = 0
        const val PEEK_FOUND = 1
        const val PEEK_EOF = 2
    }
}
