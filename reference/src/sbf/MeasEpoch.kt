package sbf

/** Array sizes of the decoded model (measepochconfig.h). */
object MeasLimits {
    const val LOGICAL_CHANNELS = 100
    const val ANTENNAS = 3
    const val SIGNALS_PER_SATELLITE = 8
}

/** Bits of [MeasSet.flags] (`MEASFLAG_*` in measepoch.h). */
object MeasFlag {
    const val SMOOTHING = 1 shl 0
    const val RESERVED = 1 shl 1
    const val HALF_CYCLE_AMBIGUITY = 1 shl 2
    const val FIRST_MEAS = 1 shl 3
    const val APME_IN_SYNC = 1 shl 5

    /** Set on every valid measurement set; `flags != 0` means the set is valid. */
    const val VALIDITY = 1 shl 7
}

/** Bits of [MeasEpoch.commonFlags] (`COMMONFLAG_*` in measepoch.h). */
object CommonFlag {
    const val MULTIPATH_MITIGATION = 1 shl 0
    const val AT_LEAST_ONE_SMOOTHING = 1 shl 1
    const val E6C_ENCRYPTED = 1 shl 2
    const val CLOCK_STEERING_ACTIVE = 1 shl 3
    const val MEAS_FROM_DATA_COMPONENT = 1 shl 4
    const val HIGH_DYNAMICS_MODE = 1 shl 7
}

enum class RxTowStatus { INVALID, NOT_SET, COARSE, PRECISE }

/** All observables of one signal on one antenna (`MeasSet_t`). */
class MeasSet {
    /** Pseudorange (m), [Dnu.F64] if unknown. */
    var prM = 0.0

    /** Carrier phase (cycles), [Dnu.F64] if unknown. */
    var lCycles = 0.0

    /** Doppler (Hz), [Dnu.F32] if unknown. */
    var dopplerHz = 0f

    /** C/N0 (dB-Hz), [Dnu.F32] if unknown. */
    var cn0DbHz = 0f

    /** PLL lock time (ms), 0 if unknown. */
    var pllTimerMs = 0L

    /** [SignalType] value. */
    var signalType = 0

    /** [MeasFlag] bits; 0 means this set is empty. */
    var flags = 0

    /** Code multipath correction already applied to [prM] (mm). */
    var mpMm = 0

    /** Carrier multipath correction already applied to [lCycles] (1/512 cycle). */
    var carrierMp1_512c = 0

    /** Add to [prM] to undo smoothing (mm). */
    var smoothingCorrMm = 0

    /** Lock counter (modulo 256), incremented at every cycle slip or reacquisition. */
    var lockCount = 0

    /** Unfiltered C/N0 (dB-Hz). */
    var rawCn0DbHz = 0

    /** MeasExtra `Info` field (proprietary). */
    var info = 0

    /** Like [pllTimerMs] but restarted at every cycle slip (ms), 0 if unknown. */
    var lockTimeMs = 0L

    var prVarianceM2 = 0f
    var lVarianceCycles2 = 0f

    val isValid: Boolean get() = flags != 0

    /** Equivalent of `memset(0)`. */
    fun clear() {
        prM = 0.0; lCycles = 0.0; dopplerHz = 0f; cn0DbHz = 0f; pllTimerMs = 0L
        signalType = 0; flags = 0; mpMm = 0; carrierMp1_512c = 0; smoothingCorrMm = 0
        lockCount = 0; rawCn0DbHz = 0; info = 0; lockTimeMs = 0L
        prVarianceM2 = 0f; lVarianceCycles2 = 0f
    }

    /** Equivalent of C struct assignment. */
    fun copyFrom(o: MeasSet) {
        prM = o.prM; lCycles = o.lCycles; dopplerHz = o.dopplerHz; cn0DbHz = o.cn0DbHz
        pllTimerMs = o.pllTimerMs; signalType = o.signalType; flags = o.flags; mpMm = o.mpMm
        carrierMp1_512c = o.carrierMp1_512c; smoothingCorrMm = o.smoothingCorrMm
        lockCount = o.lockCount; rawCn0DbHz = o.rawCn0DbHz; info = o.info; lockTimeMs = o.lockTimeMs
        prVarianceM2 = o.prVarianceM2; lVarianceCycles2 = o.lVarianceCycles2
    }
}

/** All measurements of one satellite (`MeasChannel_t`). */
class MeasChannel {
    /** Internal satellite number, see [Svid]. */
    var prn = 0

    /** GLONASS frequency number + 8, 0 for other constellations. */
    var fnPlus8 = 0

    /** Logical channel number (0-based). */
    var channel = 0

    /** `[antenna][signal slot]`. */
    val measSet: Array<Array<MeasSet>> =
        Array(MeasLimits.ANTENNAS) { Array(MeasLimits.SIGNALS_PER_SATELLITE) { MeasSet() } }

    fun clear() {
        prn = 0; fnPlus8 = 0; channel = 0
        for (ant in measSet) for (m in ant) m.clear()
    }
}

/**
 * One decoded measurement epoch (`MeasEpoch_t`).
 *
 * The collector reuses a single instance, so consumers must read it before the next
 * epoch is decoded.
 */
class MeasEpoch {
    /** Time of week (ms), [Dnu.U32] if unknown. */
    var towMs = 0L

    /** Continuous week number, [Dnu.U16] if unknown. */
    var wnc = 0
    var rxTowStatus = RxTowStatus.NOT_SET

    /** Accumulated integer clock jump since receiver start (ms), [Dnu.I32] if unknown. */
    var totalClockJumpMs = 0

    /** Converts carrier-phase variance (cycles²) into Doppler variance (Hz²). */
    var dopplerVarFactor = 0f

    /** [CommonFlag] bits. */
    var commonFlags = 0

    /** Number of used entries in [channelData]. */
    var nbrElements = 0
    val channelData: Array<MeasChannel> = Array(MeasLimits.LOGICAL_CHANNELS) { MeasChannel() }

    /** The used channels. */
    val channels: List<MeasChannel> get() = channelData.asList().subList(0, nbrElements)

    fun clear() {
        towMs = 0L; wnc = 0; rxTowStatus = RxTowStatus.NOT_SET; totalClockJumpMs = 0
        dopplerVarFactor = 0f; commonFlags = 0; nbrElements = 0
        for (c in channelData) c.clear()
    }

    internal fun setCommonFlagsFromSbf(sbfCommonFlags: Int) {
        var f = 0
        if (sbfCommonFlags and 0x01 != 0) f = f or CommonFlag.MULTIPATH_MITIGATION
        if (sbfCommonFlags and 0x02 != 0) f = f or CommonFlag.AT_LEAST_ONE_SMOOTHING
        if (sbfCommonFlags and 0x08 != 0) f = f or CommonFlag.CLOCK_STEERING_ACTIVE
        if (sbfCommonFlags and 0x20 != 0) f = f or CommonFlag.HIGH_DYNAMICS_MODE
        if (sbfCommonFlags and 0x40 != 0) f = f or CommonFlag.E6C_ENCRYPTED
        commonFlags = f
    }

    internal fun setTime(tow: Long, wnc: Int) {
        towMs = tow
        this.wnc = wnc
        rxTowStatus = RxTowStatus.NOT_SET
        if (tow != Dnu.U32) rxTowStatus = RxTowStatus.COARSE
        if (wnc != Dnu.U16) rxTowStatus = RxTowStatus.PRECISE
    }

    internal companion object {
        const val DEFAULT_DOPPLER_VARIANCE_FACTOR = 163f

        /** CumClkJumps is a signed 8-bit count stored modulo 256. */
        fun clockJump(cumClkJumps: Int): Int = if (cumClkJumps >= 128) cumClkJumps - 256 else cumClkJumps
    }
}
