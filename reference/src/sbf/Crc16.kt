package sbf

/** CRC-16/XMODEM (CCITT polynomial 0x1021, initial value 0), as in crc.c. */
object Crc16 {
    private val table = IntArray(256) { i ->
        var crc = i shl 8
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc and 0xFFFF
    }

    fun compute(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset, initial: Int = 0): Int {
        var crc = initial
        for (i in offset until offset + length) {
            crc = ((crc shl 8) and 0xFFFF) xor table[((crc ushr 8) xor buf[i].toInt()) and 0xFF]
        }
        return crc
    }

    /** `CRCIsValid`: CRC over bytes [4, Length) must equal the CRC field at offset 2. */
    fun isValid(buf: ByteArray, offset: Int, length: Int): Boolean =
        compute(buf, offset + 4, length - 4) == buf.u16(offset + 2)
}
