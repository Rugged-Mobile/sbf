package sbf

/** Minimal byte input, so the reader has no dependency on java.io. */
fun interface ByteSource {
    /** Reads up to [length] bytes into [buffer] at [offset]; returns the count, or -1 at end of input. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
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
 */
class SbfBlockReader(
    private val source: ByteSource,
    bufferSize: Int = 1 shl 20,
    private val requireLengthMultipleOf4: Boolean = false,
) {
    private val buf = ByteArray(maxOf(bufferSize, 2 * SbfLimits.MAX_BLOCK_LENGTH))
    private var start = 0
    private var end = 0
    private var eof = false
    private var bufferFileOffset = 0L

    private var peeked: SbfBlock? = null
    private var peekedEndOffset = 0L
    private var peekedValid = false

    /** File offset just after the last block returned by [next]. */
    var position: Long = 0L
        private set

    /**
     * Number of candidate blocks rejected because of a CRC mismatch.
     *
     * Fix vs C: sbfread.c re-scans the same bytes after every look-ahead peek, so it counts
     * each CRC error twice when measurements are decoded. Here each byte range is scanned
     * once. Note that a `'$'` inside a payload can still produce a spurious count.
     */
    var crcErrors: Int = 0
        private set

    /** Returns the next valid block, or null at end of input. */
    fun next(): SbfBlock? {
        if (peekedValid) {
            val block = peeked
            peeked = null
            peekedValid = false
            if (block != null) position = peekedEndOffset
            return block
        }
        val block = scan()
        if (block != null) position = fileOffset(start)
        return block
    }

    /** Returns the block [next] would return, without consuming it (`END_POS_DONT_CHANGE`). */
    fun peek(): SbfBlock? {
        if (!peekedValid) {
            peeked = scan()
            peekedEndOffset = fileOffset(start)
            peekedValid = true
        }
        return peeked
    }

    private fun fileOffset(index: Int): Long = bufferFileOffset + index

    private fun scan(): SbfBlock? {
        while (true) {
            if (start >= end && ensure(1) == 0) return null
            if (buf[start] != '$'.code.toByte()) {
                start++
                continue
            }
            if (ensure(SbfLimits.HEADER_SIZE) < SbfLimits.HEADER_SIZE ||
                buf[start + 1] != '@'.code.toByte()
            ) {
                start++
                continue
            }
            val length = buf.u16(start + 6)
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
            val block = SbfBlock(buf.copyOfRange(start, start + length))
            start += length
            return block
        }
    }

    /** Makes up to [n] bytes available from [start]; returns how many are available. */
    private fun ensure(n: Int): Int {
        if (end - start >= n) return n
        if (start > 0) {
            buf.copyInto(buf, 0, start, end)
            bufferFileOffset += start
            end -= start
            start = 0
        }
        while (end - start < n && !eof) {
            val r = source.read(buf, end, buf.size - end)
            if (r < 0) eof = true else end += r
        }
        return minOf(n, end - start)
    }
}
