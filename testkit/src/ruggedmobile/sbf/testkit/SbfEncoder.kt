package ruggedmobile.sbf.testkit

/** Little-endian byte writer for building test payloads (no java.io, so it runs on every platform). */
class LeBytes {
    private var buf = ByteArray(64)

    var size: Int = 0
        private set

    fun u8(v: Int) = apply {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun f32(v: Float) = i32(v.toRawBits())
    fun f64(v: Double) = apply { val b = v.toRawBits(); u32(b and 0xFFFFFFFFL); u32(b ushr 32) }
    fun bytes(b: ByteArray) = apply { for (x in b) u8(x.toInt()) }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * Encodes complete SBF blocks (header, time header, CRC).
 *
 * Has its own bit-by-bit CRC so that it shares no code with either decoder under test.
 */
object SbfEncoder {
    /**
     * Builds a block with [body] placed after the time header (offset 14), padded to a
     * multiple of 4 bytes.
     */
    fun block(number: Int, revision: Int = 0, tow: Long, wnc: Int, body: ByteArray): ByteArray {
        val unpadded = 14 + body.size
        val length = (unpadded + 3) / 4 * 4
        val b = ByteArray(length)
        b[0] = '$'.code.toByte()
        b[1] = '@'.code.toByte()
        val id = number or (revision shl 13)
        putU16(b, 4, id)
        putU16(b, 6, length)
        putU32(b, 8, tow)
        putU16(b, 12, wnc)
        body.copyInto(b, 14)
        putU16(b, 2, crc16(b, 4, length - 4))
        return b
    }

    fun block(number: Int, revision: Int = 0, tow: Long, wnc: Int, body: LeBytes.() -> Unit): ByteArray =
        block(number, revision, tow, wnc, LeBytes().apply(body).toByteArray())

    /** Concatenates chunks into one stream. */
    fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) {
            c.copyInto(out, p)
            p += c.size
        }
        return out
    }

    /** CRC-16/XMODEM, bit by bit. */
    fun crc16(b: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        putU16(b, off, (v and 0xFFFF).toInt())
        putU16(b, off + 2, ((v ushr 16) and 0xFFFF).toInt())
    }
}

/**
 * Generic read function shape (matches `ByteSource.read` of both libraries) that delivers
 * [data] in pieces of at most [maxChunk] bytes, to exercise buffer refills and compaction.
 */
class ChunkedBytes(private val data: ByteArray, private val maxChunk: Int = 7) {
    private var pos = 0

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(length, data.size - pos, maxChunk)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }
}
