package ruggedmobile.sbf.verify

import ruggedmobile.sbf.BlockNumber
import ruggedmobile.sbf.Crc16
import ruggedmobile.sbf.EpochFilter
import ruggedmobile.sbf.SbfBlock
import ruggedmobile.sbf.SignalType
import ruggedmobile.sbf.Svid
import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exhaustive comparison of the pure functions that were reimplemented (table lookups instead of
 * `when`, slicing-by-8 CRC, raw-field filter) against the original, over their whole input
 * domain or a large random sample.
 */
class TablesParityTest {
    @Test
    fun wavelengthTableMatchesOriginalWhen() {
        for (signal in -10..300) for (fn in -12..12) {
            val ref = sbf.SignalType.wavelength(signal, fn)
            val new = SignalType.wavelength(signal, fn)
            assertEquals(ref.toRawBits(), new.toRawBits(), "signal=$signal fn=$fn")
        }
    }

    @Test
    fun svidAndBlockClassification() {
        for (svid in -5..300) assertEquals(sbf.Svid.fromSbfSvid(svid), Svid.fromSbfSvid(svid), "svid=$svid")
        for (n in 0..0x1FFF) assertEquals(sbf.BlockNumber.isGnssMeasBlock(n), BlockNumber.isGnssMeasBlock(n), "block=$n")
    }

    @Test
    fun crcMatchesOriginal() {
        val rnd = Random(7)
        val data = ByteArray(70_000).also { rnd.nextBytes(it) }
        repeat(2000) {
            val off = rnd.nextInt(1000)
            val len = rnd.nextInt(data.size - off)
            assertEquals(sbf.Crc16.compute(data, off, len), Crc16.compute(data, off, len), "off=$off len=$len")
        }
    }

    @Test
    fun epochFilterMatchesOriginal() {
        val rnd = Random(11)
        val towSamples = listOf(0L, 1L, 999L, 1000L, 86_399_999L, 86_400_000L, 604_799_999L, 0xFFFFFFFFL)
        val filters = listOf(
            Triple(EpochFilter.DONT_CARE_FIRST, EpochFilter.DONT_CARE_LAST, 1),
            Triple(3_600_000L, 7_200_000L, 1000),
            Triple(2300L * 604_800_000L + 5000, 2300L * 604_800_000L + 900_000, 100),
            Triple(-1L, 50_000_000L, 7),
        )
        for ((first, last, interval) in filters) for (accept in listOf(true, false)) {
            val ref = sbf.EpochFilter(first, last, interval, accept)
            val new = EpochFilter(first, last, interval, accept)
            val tows = towSamples + List(500) { rnd.nextLong(0, 0x1_0000_0000L) }
            for (tow in tows) for (wnc in listOf(0, 2300, 65535)) {
                val raw = SbfEncoder.block(BlockNumber.DOP_2, tow = tow, wnc = wnc) { u8(0) }
                assertEquals(ref.includes(sbf.SbfBlock(raw)), new.includes(SbfBlock().wrap(raw)), "tow=$tow wnc=$wnc")
            }
        }
    }

    /** Field accessors at every offset, including past both ends of the block. */
    @Test
    fun blockAccessorsMatchOriginal() {
        val rnd = Random(3)
        val raw = SbfEncoder.block(BlockNumber.MEAS_EXTRA, tow = 1, wnc = 2) { repeat(57) { u8(rnd.nextInt(256)) } }
        // Embed the block in a larger array so out-of-block reads would hit neighbouring bytes.
        val padded = ByteArray(raw.size + 20) { 0x5A } .also { raw.copyInto(it, 10) }
        val ref = sbf.SbfBlock(raw)
        val new = SbfBlock().wrap(padded, 10, raw.size)
        for (off in -10..raw.size + 10) {
            assertEquals(ref.u8(off), new.u8(off), "u8 $off")
            assertEquals(ref.i8(off), new.i8(off), "i8 $off")
            assertEquals(ref.u16(off), new.u16(off), "u16 $off")
            assertEquals(ref.i16(off), new.i16(off), "i16 $off")
            assertEquals(ref.u32(off), new.u32(off), "u32 $off")
            assertEquals(ref.i32(off), new.i32(off), "i32 $off")
            assertEquals(ref.f32(off).toRawBits(), new.f32(off).toRawBits(), "f32 $off")
            assertEquals(ref.f64(off).toRawBits(), new.f64(off).toRawBits(), "f64 $off")
        }
    }
}
