package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Crc16Test {
    @Test
    fun checkValueIsXmodem() {
        val data = "123456789".encodeToByteArray()
        assertEquals(0x31C3, Crc16.compute(data))
        assertEquals(0x31C3, Crc16.computeBytewise(data))
    }

    /** Slicing-by-8 must equal the byte-wise CRC (and an independent bit-wise one) for every length and alignment. */
    @Test
    fun slicingMatchesBytewise() {
        val rnd = Random(1234)
        val data = ByteArray(4096).also { rnd.nextBytes(it) }
        for (len in 0..80) {
            for (off in 0..9) {
                val expected = Crc16.computeBytewise(data, off, len)
                assertEquals(expected, Crc16.compute(data, off, len), "len=$len off=$off")
                assertEquals(expected, SbfEncoder.crc16(data, off, len), "len=$len off=$off")
            }
        }
        repeat(200) {
            val off = rnd.nextInt(100)
            val len = rnd.nextInt(data.size - off)
            val init = rnd.nextInt(0x10000)
            assertEquals(Crc16.computeBytewise(data, off, len, init), Crc16.compute(data, off, len, init))
        }
    }
}
