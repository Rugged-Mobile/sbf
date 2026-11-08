package ruggedmobile.sbf.verify

import ruggedmobile.sbf.MeasEpoch
import kotlin.test.fail

/**
 * A copy of one epoch decoded by the reference (original) library, taken inside its callback
 * (its epoch object is reused) and compared later with the new library's epoch.
 *
 * Every field is stored as raw bits (doubles/floats via `toRawBits`), so comparisons are
 * bit-exact, including NaN payloads and -0.0.
 */
class EpochSnapshot {
    var towMs = 0L
    var wnc = 0
    var rxTowStatus = 0
    var totalClockJumpMs = 0
    var dopplerVarFactorBits = 0
    var commonFlags = 0
    var channelCount = 0
    val chan = IntArray(MeasEpoch.CHANNELS * 3)
    val sets = LongArray(MeasEpoch.SETS * FIELDS)

    fun fill(e: sbf.MeasEpoch): EpochSnapshot {
        towMs = e.towMs
        wnc = e.wnc
        rxTowStatus = e.rxTowStatus.ordinal
        totalClockJumpMs = e.totalClockJumpMs
        dopplerVarFactorBits = e.dopplerVarFactor.toRawBits()
        commonFlags = e.commonFlags
        channelCount = e.nbrElements
        for (ch in 0 until channelCount) {
            val c = e.channelData[ch]
            chan[ch * 3] = c.prn
            chan[ch * 3 + 1] = c.fnPlus8
            chan[ch * 3 + 2] = c.channel
            for (ant in 0 until 3) for (slot in 0 until 8) {
                val m = c.measSet[ant][slot]
                val o = MeasEpoch.setIndex(ch, ant, slot) * FIELDS
                sets[o + 0] = m.prM.toRawBits()
                sets[o + 1] = m.lCycles.toRawBits()
                sets[o + 2] = m.dopplerHz.toRawBits().toLong()
                sets[o + 3] = m.cn0DbHz.toRawBits().toLong()
                sets[o + 4] = m.pllTimerMs
                sets[o + 5] = m.signalType.toLong()
                sets[o + 6] = m.flags.toLong()
                sets[o + 7] = m.mpMm.toLong()
                sets[o + 8] = m.carrierMp1_512c.toLong()
                sets[o + 9] = m.smoothingCorrMm.toLong()
                sets[o + 10] = m.lockCount.toLong()
                sets[o + 11] = m.rawCn0DbHz.toLong()
                sets[o + 12] = m.info.toLong()
                sets[o + 13] = m.lockTimeMs
                sets[o + 14] = m.prVarianceM2.toRawBits().toLong()
                sets[o + 15] = m.lVarianceCycles2.toRawBits().toLong()
            }
        }
        return this
    }

    /** Fails with a precise description of the first difference from [e]. */
    fun assertSame(e: MeasEpoch, where: String) {
        fun check(name: String, ref: Long, new: Long) {
            if (ref != new) fail("$where: $name differs: reference=$ref new=$new (epoch tow=$towMs)")
        }
        check("towMs", towMs, e.towMs)
        check("wnc", wnc.toLong(), e.wnc.toLong())
        check("rxTowStatus", rxTowStatus.toLong(), e.rxTowStatus.toLong())
        check("totalClockJumpMs", totalClockJumpMs.toLong(), e.totalClockJumpMs.toLong())
        check("dopplerVarFactor", dopplerVarFactorBits.toLong(), e.dopplerVarFactor.toRawBits().toLong())
        check("commonFlags", commonFlags.toLong(), e.commonFlags.toLong())
        check("channelCount", channelCount.toLong(), e.channelCount.toLong())
        for (ch in 0 until channelCount) {
            check("prn[$ch]", chan[ch * 3].toLong(), e.prn(ch).toLong())
            check("fnPlus8[$ch]", chan[ch * 3 + 1].toLong(), e.fnPlus8(ch).toLong())
            check("channel[$ch]", chan[ch * 3 + 2].toLong(), e.channel(ch).toLong())
            for (ant in 0 until 3) for (slot in 0 until 8) {
                val s = MeasEpoch.setIndex(ch, ant, slot)
                val o = s * FIELDS
                val at = "[ch=$ch ant=$ant slot=$slot]"
                check("prM$at", sets[o + 0], e.prM(s).toRawBits())
                check("lCycles$at", sets[o + 1], e.lCycles(s).toRawBits())
                check("dopplerHz$at", sets[o + 2], e.dopplerHz(s).toRawBits().toLong())
                check("cn0DbHz$at", sets[o + 3], e.cn0DbHz(s).toRawBits().toLong())
                check("pllTimerMs$at", sets[o + 4], e.pllTimerMs(s))
                check("signalType$at", sets[o + 5], e.signalType(s).toLong())
                check("flags$at", sets[o + 6], e.flags(s).toLong())
                check("mpMm$at", sets[o + 7], e.mpMm(s).toLong())
                check("carrierMp1_512c$at", sets[o + 8], e.carrierMp1_512c(s).toLong())
                check("smoothingCorrMm$at", sets[o + 9], e.smoothingCorrMm(s).toLong())
                check("lockCount$at", sets[o + 10], e.lockCount(s).toLong())
                check("rawCn0DbHz$at", sets[o + 11], e.rawCn0DbHz(s).toLong())
                check("info$at", sets[o + 12], e.info(s).toLong())
                check("lockTimeMs$at", sets[o + 13], e.lockTimeMs(s))
                check("prVarianceM2$at", sets[o + 14], e.prVarianceM2(s).toRawBits().toLong())
                check("lVarianceCycles2$at", sets[o + 15], e.lVarianceCycles2(s).toRawBits().toLong())
            }
        }
    }

    companion object {
        const val FIELDS = 16
    }
}
