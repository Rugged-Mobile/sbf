package ruggedmobile.sbf

/**
 * One decoded measurement epoch (`MeasEpoch_t`), stored as columns (struct of arrays).
 *
 * ## Layout
 * - Epoch header: plain properties ([towMs], [wnc], ...).
 * - Channel columns, indexed by channel `ch` in `0 until channelCount`: [prn], [fnPlus8], [channel].
 * - Measurement-set columns, indexed by a *set index* `s = setIndex(ch, antenna, slot)`:
 *   [prM], [lCycles], [dopplerHz], [cn0DbHz], [flags], ... One set holds all observables of one
 *   signal on one antenna (the original `MeasSet`). Each channel owns [SETS_PER_CHANNEL]
 *   consecutive sets: antenna-major, then the [MeasLimits.SIGNALS_PER_SATELLITE] slots.
 *
 * Every column is one primitive array allocated with the epoch, so an epoch is a fixed ~120 KB
 * with no per-set objects. Columns use the narrowest type that holds every value the decoders
 * can produce; the accessors widen them back, so the values match the original `MeasSet` fields.
 *
 * ## Lifetime
 * The collector decodes every epoch into the same instance, so read it inside the callback, or
 * keep a copy with [copyFrom] into an epoch you own (no allocation).
 *
 * ## Example
 * ```
 * for (ch in 0 until e.channelCount) {
 *     val first = MeasEpoch.setIndex(ch, antenna = 0, slot = 0)
 *     for (s in first until first + MeasLimits.SIGNALS_PER_SATELLITE) {
 *         if (e.isValid(s) && e.signalType(s) == SignalType.GPS_L1CA) use(e.prn(ch), e.prM(s))
 *     }
 * }
 * ```
 */
class MeasEpoch {
    /** Time of week (ms), [Dnu.U32] if unknown. */
    var towMs: Long = 0L
        internal set

    /** Continuous week number, [Dnu.U16] if unknown. */
    var wnc: Int = 0
        internal set

    /** One of the [RxTowStatus] values. */
    var rxTowStatus: Int = RxTowStatus.NOT_SET
        internal set

    /** Accumulated integer clock jump since receiver start (ms), [Dnu.I32] if unknown. */
    var totalClockJumpMs: Int = 0
        internal set

    /** Converts carrier-phase variance (cycles²) into Doppler variance (Hz²). */
    var dopplerVarFactor: Float = 0f
        internal set

    /** [CommonFlag] bits. */
    var commonFlags: Int = 0
        internal set

    /** Number of used channels (`nbrElements`). */
    var channelCount: Int = 0
        internal set

    // ---- channel columns
    internal val chPrn = IntArray(CHANNELS)
    internal val chFnPlus8 = IntArray(CHANNELS)
    internal val chChannel = IntArray(CHANNELS)

    // ---- measurement-set columns
    internal val sPrM = DoubleArray(SETS)
    internal val sLCycles = DoubleArray(SETS)
    internal val sDopplerHz = FloatArray(SETS)
    internal val sCn0DbHz = FloatArray(SETS)
    internal val sPllTimerMs = IntArray(SETS)
    internal val sSignalType = ByteArray(SETS)
    internal val sFlags = ByteArray(SETS)
    internal val sMpMm = ShortArray(SETS)
    internal val sCarrierMp = ByteArray(SETS)
    internal val sSmoothingCorrMm = ShortArray(SETS)
    internal val sLockCount = ByteArray(SETS)
    internal val sRawCn0 = ByteArray(SETS)
    internal val sInfo = ByteArray(SETS)
    internal val sLockTimeMs = IntArray(SETS)
    internal val sPrVar = FloatArray(SETS)
    internal val sLVar = FloatArray(SETS)

    /**
     * Channels `0 until dirtyChannels` may hold non-zero data. Clearing only that range is
     * equivalent to clearing everything, and much cheaper (typically 30-60 of 100 channels).
     */
    internal var dirtyChannels = 0

    // ---- channel accessors

    /** Internal satellite number of channel [ch], see [Svid]. */
    fun prn(ch: Int): Int = chPrn[ch]

    /** GLONASS frequency number + 8, 0 for other constellations. */
    fun fnPlus8(ch: Int): Int = chFnPlus8[ch]

    /** Logical (receiver) channel number, 0-based. */
    fun channel(ch: Int): Int = chChannel[ch]

    // ---- measurement-set accessors (s = setIndex(ch, antenna, slot))

    /** Pseudorange (m), [Dnu.F64] if unknown. */
    fun prM(s: Int): Double = sPrM[s]

    /** Carrier phase (cycles), [Dnu.F64] if unknown. */
    fun lCycles(s: Int): Double = sLCycles[s]

    /** Doppler (Hz), [Dnu.F32] if unknown. */
    fun dopplerHz(s: Int): Float = sDopplerHz[s]

    /** C/N0 (dB-Hz), [Dnu.F32] if unknown. */
    fun cn0DbHz(s: Int): Float = sCn0DbHz[s]

    /** PLL lock time (ms), 0 if unknown. */
    fun pllTimerMs(s: Int): Long = sPllTimerMs[s].toLong()

    /** [SignalType] value. */
    fun signalType(s: Int): Int = sSignalType[s].toInt() and 0xFF

    /** [MeasFlag] bits; 0 means this set is empty. */
    fun flags(s: Int): Int = sFlags[s].toInt() and 0xFF

    /** True if the set holds a measurement (`flags != 0`). */
    fun isValid(s: Int): Boolean = sFlags[s].toInt() != 0

    /** Code multipath correction already applied to [prM] (mm). */
    fun mpMm(s: Int): Int = sMpMm[s].toInt()

    /** Carrier multipath correction already applied to [lCycles] (1/512 cycle). */
    fun carrierMp1_512c(s: Int): Int = sCarrierMp[s].toInt()

    /** Add to [prM] to undo smoothing (mm). */
    fun smoothingCorrMm(s: Int): Int = sSmoothingCorrMm[s].toInt()

    /** Lock counter (modulo 256), incremented at every cycle slip or reacquisition. */
    fun lockCount(s: Int): Int = sLockCount[s].toInt() and 0xFF

    /** Unfiltered C/N0 (dB-Hz). */
    fun rawCn0DbHz(s: Int): Int = sRawCn0[s].toInt() and 0xFF

    /** MeasExtra `Info` field (proprietary). */
    fun info(s: Int): Int = sInfo[s].toInt() and 0xFF

    /** Like [pllTimerMs] but restarted at every cycle slip (ms), 0 if unknown. */
    fun lockTimeMs(s: Int): Long = sLockTimeMs[s].toLong() and 0xFFFFFFFFL

    fun prVarianceM2(s: Int): Float = sPrVar[s]
    fun lVarianceCycles2(s: Int): Float = sLVar[s]

    /** Makes this epoch an exact copy of [other], reusing this epoch's arrays (no allocation). */
    fun copyFrom(other: MeasEpoch) {
        if (other === this) return
        clear()
        towMs = other.towMs
        wnc = other.wnc
        rxTowStatus = other.rxTowStatus
        totalClockJumpMs = other.totalClockJumpMs
        dopplerVarFactor = other.dopplerVarFactor
        commonFlags = other.commonFlags
        channelCount = other.channelCount
        val nc = other.dirtyChannels
        val ns = nc * SETS_PER_CHANNEL
        other.chPrn.copyInto(chPrn, 0, 0, nc)
        other.chFnPlus8.copyInto(chFnPlus8, 0, 0, nc)
        other.chChannel.copyInto(chChannel, 0, 0, nc)
        other.sPrM.copyInto(sPrM, 0, 0, ns)
        other.sLCycles.copyInto(sLCycles, 0, 0, ns)
        other.sDopplerHz.copyInto(sDopplerHz, 0, 0, ns)
        other.sCn0DbHz.copyInto(sCn0DbHz, 0, 0, ns)
        other.sPllTimerMs.copyInto(sPllTimerMs, 0, 0, ns)
        other.sSignalType.copyInto(sSignalType, 0, 0, ns)
        other.sFlags.copyInto(sFlags, 0, 0, ns)
        other.sMpMm.copyInto(sMpMm, 0, 0, ns)
        other.sCarrierMp.copyInto(sCarrierMp, 0, 0, ns)
        other.sSmoothingCorrMm.copyInto(sSmoothingCorrMm, 0, 0, ns)
        other.sLockCount.copyInto(sLockCount, 0, 0, ns)
        other.sRawCn0.copyInto(sRawCn0, 0, 0, ns)
        other.sInfo.copyInto(sInfo, 0, 0, ns)
        other.sLockTimeMs.copyInto(sLockTimeMs, 0, 0, ns)
        other.sPrVar.copyInto(sPrVar, 0, 0, ns)
        other.sLVar.copyInto(sLVar, 0, 0, ns)
        dirtyChannels = nc
    }

    // ---- decoder-side helpers

    /** Equivalent of the original `MeasEpoch.clear()` (memset 0 of the whole epoch). */
    internal fun clear() {
        towMs = 0L; wnc = 0; rxTowStatus = RxTowStatus.NOT_SET; totalClockJumpMs = 0
        dopplerVarFactor = 0f; commonFlags = 0; channelCount = 0
        val nc = dirtyChannels
        if (nc == 0) return
        val ns = nc * SETS_PER_CHANNEL
        chPrn.fill(0, 0, nc)
        chFnPlus8.fill(0, 0, nc)
        chChannel.fill(0, 0, nc)
        sPrM.fill(0.0, 0, ns)
        sLCycles.fill(0.0, 0, ns)
        sDopplerHz.fill(0f, 0, ns)
        sCn0DbHz.fill(0f, 0, ns)
        sPllTimerMs.fill(0, 0, ns)
        sSignalType.fill(0, 0, ns)
        sFlags.fill(0, 0, ns)
        sMpMm.fill(0, 0, ns)
        sCarrierMp.fill(0, 0, ns)
        sSmoothingCorrMm.fill(0, 0, ns)
        sLockCount.fill(0, 0, ns)
        sRawCn0.fill(0, 0, ns)
        sInfo.fill(0, 0, ns)
        sLockTimeMs.fill(0, 0, ns)
        sPrVar.fill(0f, 0, ns)
        sLVar.fill(0f, 0, ns)
        dirtyChannels = 0
    }

    /** Must be called before writing anything to channel [ch] or its sets. */
    internal fun touch(ch: Int) {
        if (ch >= dirtyChannels) dirtyChannels = ch + 1
    }

    /** Equivalent of `chan.measSet[ant][slot].copyFrom(m)`: writes all 16 fields of set [s]. */
    internal fun storeSet(s: Int, m: MeasSetScratch) {
        sPrM[s] = m.prM
        sLCycles[s] = m.lCycles
        sDopplerHz[s] = m.dopplerHz
        sCn0DbHz[s] = m.cn0DbHz
        sPllTimerMs[s] = m.pllTimerMs
        sSignalType[s] = m.signalType.toByte()
        sFlags[s] = m.flags.toByte()
        sMpMm[s] = m.mpMm.toShort()
        sCarrierMp[s] = m.carrierMp1_512c.toByte()
        sSmoothingCorrMm[s] = m.smoothingCorrMm.toShort()
        sLockCount[s] = m.lockCount.toByte()
        sRawCn0[s] = m.rawCn0DbHz.toByte()
        sInfo[s] = m.info.toByte()
        sLockTimeMs[s] = m.lockTimeMs
        sPrVar[s] = m.prVarianceM2
        sLVar[s] = m.lVarianceCycles2
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

    companion object {
        const val CHANNELS = MeasLimits.LOGICAL_CHANNELS
        const val SETS_PER_CHANNEL = MeasLimits.ANTENNAS * MeasLimits.SIGNALS_PER_SATELLITE
        const val SETS = CHANNELS * SETS_PER_CHANNEL

        /** Index of the set of channel [ch], [antenna] (0..2) and signal [slot] (0..7). */
        fun setIndex(ch: Int, antenna: Int, slot: Int): Int =
            (ch * MeasLimits.ANTENNAS + antenna) * MeasLimits.SIGNALS_PER_SATELLITE + slot

        internal const val DEFAULT_DOPPLER_VARIANCE_FACTOR = 163f

        /** CumClkJumps is a signed 8-bit count stored modulo 256. */
        internal fun clockJump(cumClkJumps: Int): Int = if (cumClkJumps >= 128) cumClkJumps - 256 else cumClkJumps
    }
}

/**
 * One measurement set being assembled by the Meas3 decoder (the original decoder's reusable
 * `master` / `slave` `MeasSet` objects). Written to an epoch with [MeasEpoch.storeSet].
 */
internal class MeasSetScratch {
    var prM = 0.0
    var lCycles = 0.0
    var dopplerHz = 0f
    var cn0DbHz = 0f
    var pllTimerMs = 0
    var signalType = 0
    var flags = 0
    var mpMm = 0
    var carrierMp1_512c = 0
    var smoothingCorrMm = 0
    var lockCount = 0
    var rawCn0DbHz = 0
    var info = 0
    var lockTimeMs = 0
    var prVarianceM2 = 0f
    var lVarianceCycles2 = 0f

    fun clear() {
        prM = 0.0; lCycles = 0.0; dopplerHz = 0f; cn0DbHz = 0f; pllTimerMs = 0
        signalType = 0; flags = 0; mpMm = 0; carrierMp1_512c = 0; smoothingCorrMm = 0
        lockCount = 0; rawCn0DbHz = 0; info = 0; lockTimeMs = 0
        prVarianceM2 = 0f; lVarianceCycles2 = 0f
    }
}
