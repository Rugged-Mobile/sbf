package ruggedmobile.sbf

/**
 * CRC-16/XMODEM (CCITT polynomial 0x1021, initial value 0), as in crc.c.
 *
 * Computed with "slicing-by-8": eight 256-entry tables let the loop fold 8 input bytes per
 * iteration with independent table lookups instead of a serial dependency per byte. Table `k`
 * holds the CRC of a byte followed by `k` zero bytes; by linearity of the CRC the 8 lookups
 * XOR together to the same result as 8 byte-wise steps. [computeBytewise] is the plain
 * byte-wise version, kept as the specification and for tests.
 */
object Crc16 {
    private const val SLICES = 8

    /** `SLICES` tables of 256 entries, flattened: `table[k * 256 + b]`. */
    private val table = IntArray(SLICES * 256).also { t ->
        for (i in 0 until 256) {
            var crc = i shl 8
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            t[i] = crc and 0xFFFF
        }
        for (k in 1 until SLICES) {
            for (i in 0 until 256) {
                val prev = t[(k - 1) * 256 + i]
                t[k * 256 + i] = ((prev shl 8) and 0xFFFF) xor t[prev ushr 8]
            }
        }
    }

    fun compute(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset, initial: Int = 0): Int {
        val t = table
        var crc = initial
        var i = offset
        val end = offset + length
        val end8 = end - 7
        while (i < end8) {
            val a0 = ((crc ushr 8) xor buf[i].toInt()) and 0xFF
            val a1 = (crc xor buf[i + 1].toInt()) and 0xFF
            crc = t[7 * 256 + a0] xor
                t[6 * 256 + a1] xor
                t[5 * 256 + (buf[i + 2].toInt() and 0xFF)] xor
                t[4 * 256 + (buf[i + 3].toInt() and 0xFF)] xor
                t[3 * 256 + (buf[i + 4].toInt() and 0xFF)] xor
                t[2 * 256 + (buf[i + 5].toInt() and 0xFF)] xor
                t[1 * 256 + (buf[i + 6].toInt() and 0xFF)] xor
                t[buf[i + 7].toInt() and 0xFF]
            i += 8
        }
        while (i < end) {
            crc = ((crc shl 8) and 0xFFFF) xor t[((crc ushr 8) xor buf[i].toInt()) and 0xFF]
            i++
        }
        return crc
    }

    /** Reference byte-wise implementation (identical to the original `Crc16.compute`). */
    internal fun computeBytewise(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset, initial: Int = 0): Int {
        var crc = initial
        for (i in offset until offset + length) {
            crc = ((crc shl 8) and 0xFFFF) xor table[((crc ushr 8) xor buf[i].toInt()) and 0xFF]
        }
        return crc
    }

    /** `CRCIsValid`: CRC over bytes [4, Length) must equal the CRC field at offset 2. */
    fun isValid(buf: ByteArray, offset: Int, length: Int): Boolean =
        compute(buf, offset + 4, length - 4) == rdU16(buf, 0, buf.size, offset + 2)
}
