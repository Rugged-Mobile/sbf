package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.ChunkedBytes
import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SbfBlockReaderTest {
    private fun block(tow: Long, number: Int = BlockNumber.RECEIVER_STATUS_2) =
        SbfEncoder.block(number, tow = tow, wnc = 2300) { u8(1); u8(0); u32(100) }

    /** Source delivering the stream in 7-byte pieces to exercise refills. */
    private fun source(vararg chunks: ByteArray): ByteSource {
        val c = ChunkedBytes(SbfEncoder.concat(*chunks))
        return ByteSource(c::read)
    }

    @Test
    fun findsBlocksBetweenGarbage() {
        val garbage = "junk$$@@x".encodeToByteArray()
        val reader = SbfBlockReader(source(garbage, block(1000), garbage, block(2000), garbage))
        assertEquals(1000L, reader.next()!!.tow)
        assertEquals(2000L, reader.next()!!.tow)
        assertNull(reader.next())
        assertEquals(0, reader.crcErrors)
    }

    @Test
    fun resyncsInsideACorruptedBlock() {
        // A block with a bad CRC whose payload hides a complete valid block: scanning resumes
        // at the byte after the rejected '$' and finds the inner block.
        val inner = block(1234)
        val outer = SbfEncoder.block(BlockNumber.RECEIVER_STATUS_2, tow = 999, wnc = 1) { bytes(inner) }
        outer[outer.size - 1] = (outer[outer.size - 1] + 1).toByte()
        val reader = SbfBlockReader(source(outer))
        assertEquals(1234L, reader.next()!!.tow)
        assertNull(reader.next())
        assertEquals(1, reader.crcErrors)
    }

    @Test
    fun rejectsTooShortAndTruncatedBlocks() {
        val tooShort = byteArrayOf('$'.code.toByte(), '@'.code.toByte(), 0, 0, 0, 0, 12, 0, 0, 0, 0, 0)
        val truncated = block(5).copyOf(15)
        val reader = SbfBlockReader(source(tooShort, block(7), truncated))
        assertEquals(7L, reader.next()!!.tow)
        assertNull(reader.next())
    }

    @Test
    fun peekDoesNotConsume() {
        val a = block(1)
        val b = block(2)
        val reader = SbfBlockReader(source(a, b))
        val peeked = reader.peek()
        assertEquals(1L, peeked!!.tow)
        assertEquals(1L, reader.next()!!.tow)
        assertEquals(a.size.toLong(), reader.position)
        assertEquals(2L, reader.peek()!!.tow)
        assertEquals(2L, reader.next()!!.tow)
        assertEquals((a.size + b.size).toLong(), reader.position)
        assertNull(reader.peek())
        assertNull(reader.next())
    }

    @Test
    fun nextReturnsTheSameViewObject() {
        val reader = SbfBlockReader(source(block(1), block(2)))
        val first = reader.next()
        val second = reader.next()
        assertSame(first, second)
        assertEquals(2L, second!!.tow)
    }

    @Test
    fun decodesIdFields() {
        val raw = SbfEncoder.block(BlockNumber.MEAS_EPOCH_2, revision = 1, tow = 0xFFFFFFFFL, wnc = 65535) { u8(0) }
        val b = SbfBlockReader(source(raw)).next()!!
        assertEquals(BlockNumber.MEAS_EPOCH_2, b.number)
        assertEquals(1, b.revision)
        assertEquals(false, b.isTimeValid)
    }

    /**
     * The block returned by next() must stay intact while peek() reads far ahead, even when
     * that forces the buffer to be compacted (minimum-size buffer, big blocks, garbage between).
     */
    @Test
    fun currentBlockSurvivesPeekWithCompaction() {
        val big = { tow: Long, fill: Int ->
            SbfEncoder.block(BlockNumber.RECEIVER_STATUS_2, tow = tow, wnc = 2300) {
                repeat(60_000) { u8((it * fill) and 0x7F) }
            }
        }
        val blocks = (1..6).map { big(it * 1000L, it) }
        val garbage = ByteArray(5000) { 0x11 }
        val stream = mutableListOf<ByteArray>()
        for (b in blocks) { stream += b; stream += garbage }
        val reader = SbfBlockReader(ByteArraySource(SbfEncoder.concat(*stream.toTypedArray())), bufferSize = 0)

        for (expected in blocks) {
            val cur = reader.next()!!
            val peeked = reader.peek()
            // Compare after the peek: compaction may have moved the current block.
            assertContentEquals(expected, cur.toByteArray())
            if (peeked != null) assertEquals(cur.tow + 1000, peeked.tow)
        }
        assertNull(reader.next())
    }

    @Test
    fun byteArraySourceRewinds() {
        val data = SbfEncoder.concat(block(1), block(2))
        val src = ByteArraySource(data)
        repeat(2) {
            val reader = SbfBlockReader(src)
            assertEquals(1L, reader.next()!!.tow)
            assertEquals(2L, reader.next()!!.tow)
            assertNull(reader.next())
            src.rewind()
        }
    }

    @Test
    fun wrapViewsCallerBytes() {
        val raw = SbfEncoder.concat(byteArrayOf(9, 9, 9), block(42))
        val view = SbfBlock().wrap(raw, 3)
        assertEquals(42L, view.tow)
        assertEquals(0, view.u8(view.length)) // past the end reads 0
        assertEquals(0, view.u8(-1))
        assertFailsWith<IllegalArgumentException> { SbfBlock().wrap(raw, 3, raw.size) }
    }
}
