package ruggedmobile.sbf

import kotlin.test.Test
import kotlin.test.assertEquals

class MeasEpochTest {
    private fun filled(channels: Int): MeasEpoch {
        val e = MeasEpoch()
        e.setTime(1234, 2300)
        e.channelCount = channels
        for (ch in 0 until channels) {
            e.touch(ch)
            e.chPrn[ch] = ch + 1
            for (i in 0 until MeasEpoch.SETS_PER_CHANNEL) {
                val s = ch * MeasEpoch.SETS_PER_CHANNEL + i
                e.sPrM[s] = s * 1.5
                e.sFlags[s] = MeasFlag.VALIDITY.toByte()
                e.sLockTimeMs[s] = -1 // uint32 max
            }
        }
        return e
    }

    @Test
    fun clearResetsEveryTouchedChannel() {
        val e = filled(40)
        e.clear()
        assertEquals(0, e.channelCount)
        assertEquals(RxTowStatus.NOT_SET, e.rxTowStatus)
        for (s in 0 until MeasEpoch.SETS) {
            assertEquals(0.0, e.prM(s))
            assertEquals(0, e.flags(s))
        }
        for (ch in 0 until MeasEpoch.CHANNELS) assertEquals(0, e.prn(ch))
    }

    @Test
    fun accessorsWidenNarrowColumns() {
        val e = filled(1)
        assertEquals(MeasFlag.VALIDITY, e.flags(0))
        assertEquals(0xFFFFFFFFL, e.lockTimeMs(0))
    }

    @Test
    fun copyFromIsExactAndClearsLeftovers() {
        val src = filled(3)
        val dst = filled(50)
        dst.copyFrom(src)
        assertEquals(src.towMs, dst.towMs)
        assertEquals(3, dst.channelCount)
        for (s in 0 until MeasEpoch.SETS) {
            assertEquals(src.prM(s), dst.prM(s))
            assertEquals(src.flags(s), dst.flags(s))
            assertEquals(src.lockTimeMs(s), dst.lockTimeMs(s))
        }
    }

    @Test
    fun setIndexLayout() {
        assertEquals(0, MeasEpoch.setIndex(0, 0, 0))
        assertEquals(8, MeasEpoch.setIndex(0, 1, 0))
        assertEquals(24, MeasEpoch.setIndex(1, 0, 0))
        assertEquals(MeasEpoch.SETS - 1, MeasEpoch.setIndex(99, 2, 7))
    }

    @Test
    fun wavelengthTable() {
        // Spot checks: GLONASS FDMA per frequency number, out-of-range values.
        assertEquals(299792458.0 / (1602.00e6 + 3 * 562500), SignalType.wavelength(SignalType.GLO_L1CA, 3))
        assertEquals(299792458.0 / (1246.00e6 - 7 * 437500), SignalType.wavelength(SignalType.GLO_L2P, -7))
        assertEquals(Dnu.F64, SignalType.wavelength(SignalType.GLO_L1P, 7))
        assertEquals(Dnu.F64, SignalType.wavelength(-1, 0))
        assertEquals(Dnu.F64, SignalType.wavelength(256, 0))
        assertEquals(Dnu.F64, SignalType.wavelength(SignalType.LAST, 0))
        assertEquals(299792458.0 / 1575.42e6, SignalType.wavelength(SignalType.MSS, 0))
    }
}
