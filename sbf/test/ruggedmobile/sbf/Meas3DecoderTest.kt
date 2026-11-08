package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.LeBytes
import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

class Meas3DecoderTest {
    private val prMm = 21_000_000_000L // 21000 km
    private val prMsb = prMm / 4294967296L
    private val prLsb = prMm % 4294967296L

    /** Meas3Ranges for one GPS satellite, 1 s reference interval, PR rate present. */
    private fun ranges(tow: Long, data: LeBytes.() -> Unit) =
        SbfEncoder.block(BlockNumber.MEAS3_RANGES, tow = tow, wnc = 2300) {
            u8(0); u8(0); u16(1 shl Meas3System.GPS) // CommonFlags, CumClkJumps, Constellations
            u8((2 shl 4) or 8); u8(0) // Misc: 1000 ms reference interval, PRR present, antenna 0; Reserved
            data()
        }

    private val refEpoch = ranges(1000) {
        u8(0x01); u8(0x01) // M3SatData BF1 (1 mask byte), SatMask: satellite 0 (G01)
        // MasterLong BF1: PRMSB, CmC = 2097152 (zero carrier offset), LTI4 = 7 (1 s)
        u32((prMsb shl 2) or (2097152L shl 6) or (7L shl 28))
        u32(prLsb)
        u16(35 or (1 shl 6) or (1 shl 15)) // BF2: CN0 35, SigMask = L1CA, Cont = 1
        u8(0) // BF3: no extra signals
        u16(100) // PRR, 64 mm/s units
    }

    private fun deltaEpoch(tow: Long) = ranges(tow) {
        u8(0) // reuse the M3SatData header of the reference epoch
        // MasterDeltaS: type bits 10b, CN0 1, PR 8192 (zero offset), CmC 8192
        u32(2L or (1L shl 2) or (8192L shl 4) or (8192L shl 18))
    }

    /**
     * Reference epoch with a MasterLong using the SigMask continuation byte (Cont = 1), then a
     * MasterDeltaS 100 ms later. The delta pseudorange is extrapolated with the reference PR
     * rate (100 × 64 mm/s), so the result shows whether PRR was read from the right offset.
     */
    @Test
    fun masterLongWithContinuationReadsPrRateAfterBf3() {
        val epochs = run(listOf(refEpoch, deltaEpoch(1100)))

        assertEquals(2, epochs.size)
        assertEquals((prLsb.toDouble() + 4294967296.0 * prMsb.toDouble()) * .001, epochs[0].first)
        assertEquals(45f, epochs[0].second)
        // drift = 100 * 64 mm/s * 0.1 s = 640 mm. The C code reads PRR from byte 10 (BF3)
        // here and would extrapolate with 25600 * 64 mm/s instead.
        assertEquals(epochs[0].first + 640 * .001 + 8192 * .001 - 8.192, epochs[1].first)
        assertEquals(45f, epochs[1].second)
    }

    /** A delta epoch whose reference epoch was never seen decodes to an empty epoch. */
    @Test
    fun deltaWithoutReferenceIsEmpty() {
        val counts = mutableListOf<Int>()
        val blocks = listOf(deltaEpoch(1100)).map { SbfBlock().wrap(it) }
        val collector = MeasEpochCollector({ null }) { e -> counts += e.channelCount }
        blocks.forEach(collector::add)
        assertEquals(listOf(0), counts)
    }

    private fun run(raw: List<ByteArray>): List<Pair<Double, Float>> {
        val blocks = raw.map { SbfBlock().wrap(it) }
        val epochs = mutableListOf<Pair<Double, Float>>()
        var i = 0
        val collector = MeasEpochCollector({ blocks.getOrNull(i + 1) }) { e ->
            assertEquals(1, e.prn(0))
            val s = MeasEpoch.setIndex(0, 0, 0)
            epochs += e.prM(s) to e.cn0DbHz(s)
        }
        while (i < blocks.size) {
            collector.add(blocks[i])
            i++
        }
        return epochs
    }
}
