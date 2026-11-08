package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeasDecoderTest {
    private val l1 = SignalType.wavelength(SignalType.GPS_L1CA, 0)
    private val l2 = SignalType.wavelength(SignalType.GPS_L2P, 0)

    /** MeasEpoch v2 rev 1: GPS SVID 12 on receiver channel 5 with L1 C/A (Type-1) and L2 P (Type-2). */
    private fun measEpoch(tow: Long = 1000) = SbfEncoder.block(BlockNumber.MEAS_EPOCH_2, revision = 1, tow = tow, wnc = 2300) {
        u8(1); u8(20); u8(12); u8(0); u8(255); u8(0) // N, SB1Size, SB2Size, CommonFlags, CumClkJumps, Reserved
        // Type-1: PR = 4 * 2^32 mm + 2820130939 mm = 20000000.123 m
        u8(5); u8(SignalType.GPS_L1CA); u8(12); u8(4)
        u32(2820130939L); i32(-12345678)
        u16(1000); u8(0); u8(140) // CarrierLSB, CarrierMSB, CN0
        u16(60); u8(0x01); u8(1) // LockTime, ObsInfo (smoothed), N_Type2
        // Type-2: L2 P, PR offset +1.5 m, carrier +0.5 cycles, half-cycle ambiguity
        u8(SignalType.GPS_L2P); u8(30); u8(120); u8(0); u8(0); u8(0x04)
        u16(1500); u16(500); u16(0)
    }

    private fun decode(raw: ByteArray): MeasEpoch {
        val epoch = MeasEpoch()
        LegacyMeasDecoder().decodeMeasEpoch(SbfBlock().wrap(raw), epoch)
        return epoch
    }

    @Test
    fun measEpochType1AndType2() {
        val epoch = decode(measEpoch())
        assertEquals(1, epoch.channelCount)
        assertEquals(-1, epoch.totalClockJumpMs)
        assertEquals(12, epoch.prn(0))
        assertEquals(4, epoch.channel(0))

        val a = MeasEpoch.setIndex(0, 0, 0)
        assertEquals(SignalType.GPS_L1CA, epoch.signalType(a))
        assertEquals(4 * 4294967.296 + 2820130939L * 0.001, epoch.prM(a))
        assertEquals(epoch.prM(a) / l1 + 1.0, epoch.lCycles(a))
        assertEquals((-12345678 * 0.0001).toFloat(), epoch.dopplerHz(a))
        assertEquals(45f, epoch.cn0DbHz(a))
        assertEquals(60_000L, epoch.pllTimerMs(a))
        assertEquals(MeasFlag.VALIDITY or MeasFlag.SMOOTHING, epoch.flags(a))

        // GPS L2 P goes to slot 4 and has no +10 dB-Hz C/N0 offset.
        val b = MeasEpoch.setIndex(0, 0, 4)
        assertEquals(SignalType.GPS_L2P, epoch.signalType(b))
        assertEquals(epoch.prM(a) + 1.5, epoch.prM(b))
        assertEquals(epoch.prM(b) / l2 + 0.5, epoch.lCycles(b))
        assertEquals(30f, epoch.cn0DbHz(b))
        assertEquals(MeasFlag.VALIDITY or MeasFlag.HALF_CYCLE_AMBIGUITY, epoch.flags(b))
    }

    @Test
    fun measExtraRevision3Overlay() {
        val epoch = decode(measEpoch())
        val extra = SbfEncoder.block(BlockNumber.MEAS_EXTRA, revision = 3, tow = 1000, wnc = 2300) {
            u8(1); u8(16); f32(200f)
            u8(5); u8(SignalType.GPS_L1CA); u16(-15); u16(7); u16(2500); u16(100); u16(0)
            u8(3); u8(-2); u8(1); u8(4)
        }
        LegacyMeasDecoder().decodeMeasExtra(SbfBlock().wrap(extra), epoch)
        val m = MeasEpoch.setIndex(0, 0, 0)
        assertEquals(200f, epoch.dopplerVarFactor)
        assertEquals(-15, epoch.mpMm(m))
        assertEquals(7, epoch.smoothingCorrMm(m))
        assertEquals((2500 * 1e-4).toFloat(), epoch.prVarianceM2(m))
        assertEquals((100 * 1e-6).toFloat(), epoch.lVarianceCycles2(m))
        assertEquals(10L, epoch.pllTimerMs(m))
        assertEquals(3, epoch.lockCount(m))
        assertEquals(-2, epoch.carrierMp1_512c(m))
        assertTrue(epoch.flags(m) and MeasFlag.APME_IN_SYNC != 0)
        assertEquals(45.125f, epoch.cn0DbHz(m))
    }

    @Test
    fun corruptSubBlockCountDoesNotCrash() {
        val raw = measEpoch()
        raw[14] = 255.toByte() // N far beyond the block
        val fixed = SbfEncoder.block(BlockNumber.MEAS_EPOCH_2, revision = 1, tow = 1000, wnc = 2300, body = raw.copyOfRange(14, raw.size))
        decode(fixed)
    }

    /** Fix: the C collector never clears SISMeas, so a stale SISMeas overrides later MeasEpoch blocks. */
    @Test
    fun staleSisMeasIsNotReused() {
        val sisMeas = SbfEncoder.block(BlockNumber.SIS_MEAS, tow = 1000, wnc = 2300) {
            u8(1); u8(20); u8(12); u8(0); u8(0); u8(0)
            u8(3); u8(SignalType.GPS_L1CA); u8(5); u8(0)
            u32(2820130939L); i32(0); u16(0); u8(0); u8(160); u16(10); u16(4)
        }
        val prns = collect(listOf(sisMeas, measEpoch(tow = 2000))).map { it.first() }
        assertEquals(listOf(5, 12), prns)
    }

    @Test
    fun liveModeClosesEpochWhenSameBlockKindsArrive() {
        val blocks = listOf(measEpoch(1000), measEpoch(2000), measEpoch(3000))
        val tows = mutableListOf<Long>()
        val collector = MeasEpochCollector(null) { e -> tows += e.towMs }
        for (raw in blocks) collector.add(SbfBlock().wrap(raw))
        collector.flush()
        // Epoch 1 closes when epoch 2 starts (no history yet); then each closes on arrival.
        assertEquals(listOf(1000L, 2000L, 3000L), tows)
    }

    /** Runs blocks through a file-mode collector; returns the PRNs of each decoded epoch. */
    private fun collect(raw: List<ByteArray>): List<List<Int>> {
        val blocks = raw.map { SbfBlock().wrap(it) }
        var i = 0
        val result = mutableListOf<List<Int>>()
        val collector = MeasEpochCollector({ blocks.getOrNull(i + 1) }) { e ->
            result += (0 until e.channelCount).map { e.prn(it) }
        }
        while (i < blocks.size) {
            collector.add(blocks[i])
            i++
        }
        return result
    }
}
