package ruggedmobile.sbf

/**
 * Decoders for the "legacy" measurement blocks (sbfread_meas.c):
 * MeasEpoch v2 (4027), GenMeasEpoch v1 (5944), SISMeas (4115), and the MeasExtra (4000)
 * and MeasFullRange (4098) overlays.
 *
 * Arithmetic is written in the same order as the C code (and the original Kotlin port) so
 * results are bit-identical. Differences from the original port are structural only:
 * - results are written straight into the epoch's columns;
 * - the per-sub-block scratch values (`Obs`, `Type1Ref` in the original, allocated on every
 *   call) are fields of this decoder, which is created once per collector.
 */
internal class LegacyMeasDecoder {

    // Observables of one sub-block (original `Obs`).
    private var obsPr = 0.0
    private var obsCarrier = 0.0
    private var obsDoppler = 0.0
    private var obsCn0 = 0f
    private var obsLockTime = 0L

    // What a Type-1 sub-block passes on to its Type-2 sub-blocks (original `Type1Ref`).
    private var t1Pr = 0.0
    private var t1Doppler = 0.0
    private var t1Wavelength = 0.0

    private fun cn0FromSbf(signal: Int, cn0: Int): Float = when {
        cn0 == 255 -> Dnu.F32
        !SignalType.isGpsPCode(signal) -> (cn0 / 4.0 + 10.0).toFloat()
        else -> (cn0 / 4.0).toFloat()
    }

    /** `sbfread_GetMeasSetFromType1`. */
    private fun type1Obs(
        signal: Int, wavelength: Double, codeMsb: Int, codeLsb: Long, doppler: Int,
        carrierLsb: Int, carrierMsb: Int, cn0: Int, lockTime: Int,
    ) {
        obsPr = if (codeMsb == 0 && codeLsb == 0L) Dnu.F64 else codeMsb * 4294967.296 + codeLsb * 0.001
        obsDoppler = if (doppler == Int.MIN_VALUE) Dnu.F64 else doppler * 0.0001
        obsCarrier = if (carrierLsb == 0 && carrierMsb == -128) Dnu.F64
        else obsPr / wavelength + (carrierMsb * 65536 + carrierLsb) / 1000.0
        obsCn0 = cn0FromSbf(signal, cn0)
        obsLockTime = if (lockTime == 65535) LOCKTIME_UNKNOWN else lockTime.toLong()
    }

    /** `sbfread_GetMeasSetFromType2`. */
    private fun type2Obs(
        signal: Int, wavelength: Double,
        codeOffsetLsb: Int, codeOffsetMsb: Int, dopplerOffsetLsb: Int, dopplerOffsetMsb: Int,
        carrierLsb: Int, carrierMsb: Int, cn0: Int, lockTime: Int,
    ) {
        // If this offset is valid, the Type-1 pseudorange is necessarily valid too.
        obsPr = if (codeOffsetLsb == 0 && codeOffsetMsb == -4) Dnu.F64
        else t1Pr + (codeOffsetMsb * 65536 + codeOffsetLsb) * 1e-3
        obsCarrier = if (carrierLsb == 0 && carrierMsb == -128) Dnu.F64
        else obsPr / wavelength + (carrierMsb * 65536 + carrierLsb) * 0.001
        obsDoppler = if (dopplerOffsetLsb == 0 && dopplerOffsetMsb == -16) Dnu.F64
        else t1Doppler * t1Wavelength / wavelength + (dopplerOffsetMsb * 65536 + dopplerOffsetLsb) * 0.0001
        obsCn0 = cn0FromSbf(signal, cn0)
        obsLockTime = if (lockTime == 255) LOCKTIME_UNKNOWN else lockTime.toLong()
    }

    /** Sign-extends the low [bits] bits of [x] (`sbfread_ExtendSignBit`). */
    private fun signExtend(x: Int, bits: Int): Int {
        val shift = 32 - bits
        return (x shl shift) shr shift
    }

    /** `sbfread_AddMeasSet`: stores the observables in the signal's default slot of channel [ch]. */
    private fun addMeasSet(
        epoch: MeasEpoch, ch: Int, signal: Int, antIdx: Int,
        smoothing: Boolean, reserved: Boolean, hca: Boolean,
    ): Boolean {
        // Fix vs C: the C code indexes DefaultSigIdxInConstellation without a range check.
        val slot = if (signal in 0 until SignalType.LAST) SignalType.DEFAULT_SLOT[signal] else -1
        if (antIdx >= MeasLimits.ANTENNAS || slot !in 0 until MeasLimits.SIGNALS_PER_SATELLITE) return false

        val s = MeasEpoch.setIndex(ch, antIdx, slot)
        val cn0 = obsCn0
        epoch.sSignalType[s] = signal.toByte()
        epoch.sPrM[s] = obsPr
        epoch.sLCycles[s] = obsCarrier
        epoch.sDopplerHz[s] = obsDoppler.toFloat()
        epoch.sCn0DbHz[s] = cn0
        epoch.sRawCn0[s] = ((cn0.toInt() / 2 * 2) and 0xFF).toByte()
        epoch.sFlags[s] = (
            MeasFlag.VALIDITY or
                (if (smoothing) MeasFlag.SMOOTHING else 0) or
                (if (reserved) MeasFlag.RESERVED else 0) or
                (if (hca) MeasFlag.HALF_CYCLE_AMBIGUITY else 0)
            ).toByte()
        epoch.sPllTimerMs[s] = when (obsLockTime) {
            LOCKTIME_UNKNOWN -> 0
            0L -> 10 // a hard 0 means "unknown", so assume 10 ms instead
            else -> (obsLockTime * 1000).toInt()
        }
        return true
    }

    /** `sbfread_PrepareTrackMeasEpoch`. */
    private fun prepareEpoch(epoch: MeasEpoch, block: SbfBlock, cumClkJumpsAvailable: Boolean, cumClkJumps: Int, commonFlags: Int) {
        epoch.clear()
        epoch.totalClockJumpMs = if (cumClkJumpsAvailable) MeasEpoch.clockJump(cumClkJumps) else Dnu.I32
        epoch.setTime(block.tow, block.wnc)
        epoch.dopplerVarFactor = MeasEpoch.DEFAULT_DOPPLER_VARIANCE_FACTOR
        epoch.setCommonFlagsFromSbf(commonFlags)
    }

    private fun invalidateType1() {
        t1Wavelength = Dnu.F64
        t1Doppler = Dnu.F64
        t1Pr = Dnu.F64
    }

    // ---------------------------------------------------------------- MeasEpoch

    /** `sbfread_MeasEpoch_Decode`: decodes a MeasEpoch v2 or GenMeasEpoch v1 block into [epoch]. */
    fun decodeMeasEpoch(block: SbfBlock, epoch: MeasEpoch) {
        // CumClkJumps only exists from block revision 1.
        prepareEpoch(epoch, block, block.revision >= 1, block.u8(18), block.u8(17))

        val n = block.u8(14)
        val sb1Size = block.u8(15)
        val sb2Size = block.u8(16)
        val isMeasEpoch2 = block.number == BlockNumber.MEAS_EPOCH_2
        val isGenMeasEpoch = block.number == BlockNumber.GEN_MEAS_EPOCH_1
        var p = 20
        var chNr = 0

        for (t1Counter in 0 until n) {
            // Fix vs C: stop at the end of the block and at the channel array size; the C
            // code trusts N and the sub-block sizes and can read and write out of bounds.
            if (p >= block.length || chNr >= MeasLimits.LOGICAL_CHANNELS) break

            epoch.touch(chNr)
            var atLeastOneMeas = decodeMeasEpochType1(block, p, epoch, chNr, isMeasEpoch2, isGenMeasEpoch)
            val nType2 = block.u8(p + 19)
            for (j in 0 until nType2) {
                atLeastOneMeas = decodeMeasEpochType2(block, p, p + sb1Size + j * sb2Size, epoch, chNr, isMeasEpoch2) or atLeastOneMeas
            }

            // An invalid channel number (e.g. data converted from RINEX): use the counter.
            if (epoch.chChannel[chNr] == Dnu.U8) epoch.chChannel[chNr] = chNr
            if (atLeastOneMeas) chNr++

            p += sb1Size + nType2 * sb2Size
        }
        epoch.channelCount = chNr
    }

    private fun decodeMeasEpochType1(
        block: SbfBlock, p: Int, epoch: MeasEpoch, ch: Int, isMeasEpoch2: Boolean, isGenMeasEpoch: Boolean,
    ): Boolean {
        val rxChannel = block.u8(p)
        val type = block.u8(p + 1)
        val svid = block.u8(p + 2)
        val misc = block.u8(p + 3)
        val obsInfo = block.u8(p + 18)

        val prn = Svid.fromSbfSvid(svid)
        epoch.chPrn[ch] = prn
        epoch.chChannel[ch] = if (rxChannel != 0 && rxChannel != Dnu.U8) rxChannel - 1 else Dnu.U8

        val signal = if (isGenMeasEpoch && svid in 120..140) {
            SignalType.SBS_L1CA
        } else {
            val s = type and 0x1F
            if (s == 31) (obsInfo ushr 3) + 32 else s
        }
        epoch.chFnPlus8[ch] = if (SignalType.isGloFdma(signal)) obsInfo ushr 3 else 0

        // The flags are only decoded from MeasEpoch v2, not from the deprecated GenMeasEpoch.
        val smoothing = isMeasEpoch2 && obsInfo and 1 != 0
        val reserved = isMeasEpoch2 && obsInfo and 2 != 0
        val hca = isMeasEpoch2 && obsInfo and 4 != 0

        t1Wavelength = SignalType.wavelength(signal, (obsInfo ushr 3) - 8)
        if (prn == 0 || t1Wavelength == Dnu.F64) {
            invalidateType1()
            return false
        }
        type1Obs(
            signal, t1Wavelength, misc and 0xF, block.u32(p + 4), block.i32(p + 8),
            block.u16(p + 12), block.i8(p + 14), block.u8(p + 15), block.u16(p + 16),
        )
        t1Pr = obsPr
        t1Doppler = obsDoppler
        return addMeasSet(epoch, ch, signal, type ushr 5, smoothing, reserved, hca)
    }

    private fun decodeMeasEpochType2(block: SbfBlock, p1: Int, q: Int, epoch: MeasEpoch, ch: Int, isMeasEpoch2: Boolean): Boolean {
        val type = block.u8(q)
        val obsInfo = block.u8(q + 5)

        var signal = type and 0x1F
        if (signal == 31) signal = (obsInfo ushr 3) + 32

        val smoothing = isMeasEpoch2 && obsInfo and 1 != 0
        val reserved = isMeasEpoch2 && obsInfo and 2 != 0
        val hca = isMeasEpoch2 && obsInfo and 4 != 0

        // The GLONASS frequency number is carried by the Type-1 sub-block.
        if (SignalType.isGloFdma(signal)) epoch.chFnPlus8[ch] = block.u8(p1 + 18) ushr 3

        val wavelength = SignalType.wavelength(signal, epoch.chFnPlus8[ch] - 8)
        if (wavelength == Dnu.F64) return false

        val offsetsMsb = block.u8(q + 3)
        type2Obs(
            signal, wavelength,
            block.u16(q + 6), signExtend(offsetsMsb, 3),
            block.u16(q + 10), signExtend(offsetsMsb ushr 3, 5),
            block.u16(q + 8), block.i8(q + 4), block.u8(q + 2), block.u8(q + 1),
        )
        return addMeasSet(epoch, ch, signal, type ushr 5, smoothing, reserved, hca)
    }

    // ---------------------------------------------------------------- SISMeas

    /** `sbfread_SISMeas_Decode`: decodes a SISMeas block into [epoch]. */
    fun decodeSisMeas(block: SbfBlock, epoch: MeasEpoch) {
        prepareEpoch(epoch, block, true, block.u8(18), block.u8(17))

        val n1 = block.u8(14)
        val sb1Length = block.u8(15)
        val sb2Length = block.u8(16)
        var p = 20
        var chNr = 0

        for (t1Counter in 0 until n1) {
            // Fix vs C: bounds checks, as in decodeMeasEpoch.
            if (p >= block.length || chNr >= MeasLimits.LOGICAL_CHANNELS) break

            epoch.touch(chNr)
            var atLeastOneMeas = decodeSisMeasType1(block, p, epoch, chNr)
            val n2 = block.u16(p + 18) ushr 11
            for (j in 0 until n2) {
                atLeastOneMeas = decodeSisMeasType2(block, p + sb1Length + j * sb2Length, epoch, chNr) or atLeastOneMeas
            }

            if (epoch.chChannel[chNr] == Dnu.U8) epoch.chChannel[chNr] = chNr
            if (atLeastOneMeas) chNr++

            p += sb1Length + n2 * sb2Length
        }
        epoch.channelCount = chNr
    }

    private fun decodeSisMeasType1(block: SbfBlock, p: Int, epoch: MeasEpoch, ch: Int): Boolean {
        val rxChannel = block.u8(p)
        val st = block.u8(p + 1)
        val prnIdx = block.u8(p + 2)
        val obsInfo = block.u8(p + 3)
        val misc = block.u16(p + 18)

        val signal: Int
        val fnPlus8: Int
        when {
            st <= 7 || st in 12..SignalType.LAST -> { signal = st; fnPlus8 = 0 }
            st in 128..141 -> { signal = SignalType.GLO_L1CA; fnPlus8 = st - 135 + 8 }
            st in 142..155 -> { signal = SignalType.GLO_L1P; fnPlus8 = st - 149 + 8 }
            st in 156..169 -> { signal = SignalType.GLO_L2CA; fnPlus8 = st - 163 + 8 }
            st in 170..183 -> { signal = SignalType.GLO_L2P; fnPlus8 = st - 177 + 8 }
            else -> { signal = SignalType.LAST; fnPlus8 = 0 }
        }
        epoch.chFnPlus8[ch] = fnPlus8

        val prn = when {
            prnIdx == 0 -> Svid.GLO_UNKNOWN
            signal < SignalType.LAST -> SIS_MIN_PRN[signal] + prnIdx - 1
            else -> 0
        }
        epoch.chPrn[ch] = prn
        epoch.chChannel[ch] = if (rxChannel != 0 && rxChannel != Dnu.U8) rxChannel - 1 else Dnu.U8

        val reserved = obsInfo and 2 != 0
        val hca = obsInfo and 1 != 0

        t1Wavelength = SignalType.wavelength(signal, fnPlus8 - 8)
        if (prn == 0 || t1Wavelength == Dnu.F64) {
            invalidateType1()
            return false
        }
        type1Obs(
            signal, t1Wavelength, misc and 0xF, block.u32(p + 4), block.i32(p + 8),
            block.u16(p + 12), block.i8(p + 14), block.u8(p + 15), block.u16(p + 16),
        )
        t1Pr = obsPr
        t1Doppler = obsDoppler
        return addMeasSet(epoch, ch, signal, (misc ushr 4) and 1, false, reserved, hca)
    }

    private fun decodeSisMeasType2(block: SbfBlock, q: Int, epoch: MeasEpoch, ch: Int): Boolean {
        val type = block.u8(q)
        val obsInfo = block.u8(q + 5)
        val sigIdx = type and 0x1F
        val prn = epoch.chPrn[ch]

        // In SISMeas Type-2 sub-blocks the signal is an index within the constellation.
        val table = when {
            Svid.isGps(prn) -> SIS_GPS
            Svid.isGlonass(prn) -> SIS_GLO
            Svid.isGalileo(prn) -> SIS_GAL
            Svid.isBeiDou(prn) -> SIS_BDS
            Svid.isSbas(prn) -> SIS_SBS
            Svid.isNavIC(prn) -> SIS_IRN
            Svid.isQzss(prn) -> SIS_QZS
            else -> SIS_NONE
        }
        val signal = if (sigIdx < table.size) table[sigIdx] else SignalType.LAST

        val reserved = obsInfo and 2 != 0
        val hca = obsInfo and 1 != 0

        val wavelength = SignalType.wavelength(signal, epoch.chFnPlus8[ch] - 8)
        if (wavelength == Dnu.F64) return false

        val offsetsMsb = block.u8(q + 3)
        type2Obs(
            signal, wavelength,
            block.u16(q + 6), signExtend(offsetsMsb, 3),
            block.u16(q + 10), signExtend(offsetsMsb ushr 3, 5),
            block.u16(q + 8), block.i8(q + 4), block.u8(q + 2), block.u8(q + 1),
        )
        return addMeasSet(epoch, ch, signal, (type ushr 5) and 1, false, reserved, hca)
    }

    // ---------------------------------------------------------------- MeasExtra / MeasFullRange

    /**
     * Number of sub-blocks, allowing more than 255 with an 8-bit N: the block length tells
     * how many multiples of 256 were dropped.
     */
    private fun subBlockCount(block: SbfBlock, n: Int, sbSize: Int): Int =
        ((block.length / sbSize - n) / 256) * 256 + n

    /**
     * Finds the channel with `channel == rxChannel - 1`, as the C do/while loop does
     * (it inspects channel 0 even when the epoch is empty). Returns -1 if none.
     */
    private fun findChannel(epoch: MeasEpoch, rxChannel: Int): Int {
        val target = rxChannel - 1
        val channels = epoch.chChannel
        var chIdx = 0
        do {
            if (channels[chIdx] == target) return chIdx
            chIdx++
        } while (chIdx < epoch.channelCount)
        return -1
    }

    /** First set of channel [ch] / antenna [ant] whose signal type is [signal]; -1 if none. */
    private fun findSignal(epoch: MeasEpoch, ch: Int, ant: Int, signal: Int): Int {
        val first = MeasEpoch.setIndex(ch, ant, 0)
        val types = epoch.sSignalType
        for (s in first until first + MeasLimits.SIGNALS_PER_SATELLITE) {
            if ((types[s].toInt() and 0xFF) == signal) return s
        }
        return -1
    }

    /** `sbfread_MeasExtra_Decode`: overlays a MeasExtra block onto an already decoded [epoch]. */
    fun decodeMeasExtra(block: SbfBlock, epoch: MeasEpoch) {
        val n = block.u8(14)
        val sbSize = block.u8(15)
        // Fix vs C: SBSize 0 divides by zero in the C code.
        if (sbSize == 0) return
        val nrSb = subBlockCount(block, n, sbSize)
        val rev = block.revision

        val dopplerVarFactor = block.f32(16)
        if (dopplerVarFactor != 0f) epoch.dopplerVarFactor = dopplerVarFactor

        for (i in 0 until nrSb) {
            val base = 20 + i * sbSize
            // Fields beyond the wire sub-block size read as 0 (the C code re-strides with AlignSubBlockSize).
            val type = sbU8(block, base, sbSize, 1)
            val ant = (type ushr 5) and 7
            val misc = sbU8(block, base, sbSize, 15)
            var signal = type and 0x1F
            if (signal == 31) signal = (misc ushr 3) + 32

            // Signals from antennas this platform does not support are skipped.
            if (ant >= MeasLimits.ANTENNAS) continue
            val ch = findChannel(epoch, sbU8(block, base, sbSize, 0))
            if (ch < 0) continue
            val s = findSignal(epoch, ch, ant, signal)
            if (s < 0) continue
            epoch.touch(ch)

            val carrierVar = sbU16(block, base, sbSize, 8)
            val codeVar = sbU16(block, base, sbSize, 6)
            val lockTime = sbU16(block, base, sbSize, 10)
            epoch.sLVar[s] = if (carrierVar != Dnu.U16) (carrierVar * 1e-6).toFloat() else Dnu.F32
            epoch.sPrVar[s] = if (codeVar != Dnu.U16) (codeVar * 1e-4).toFloat() else Dnu.F32
            epoch.sPllTimerMs[s] = when {
                lockTime == 0 -> 10
                lockTime < Dnu.U16 -> lockTime * 1000
                else -> 0
            }
            epoch.sMpMm[s] = sbU16(block, base, sbSize, 2).toShort()
            epoch.sSmoothingCorrMm[s] = sbU16(block, base, sbSize, 4).toShort()
            epoch.sLockCount[s] = (if (rev >= 1) sbU8(block, base, sbSize, 12) else 0).toByte()

            // Carrier multipath and the APME-in-sync bit exist from revision 2.
            if (rev >= 2) {
                val info = sbU8(block, base, sbSize, 14)
                epoch.sInfo[s] = info.toByte()
                if (info and 1 != 0) epoch.sFlags[s] = (epoch.sFlags[s].toInt() or MeasFlag.APME_IN_SYNC).toByte()
                epoch.sCarrierMp[s] = (if (13 < sbSize) block.i8(base + 13) else 0).toByte()
            } else {
                epoch.sInfo[s] = 0
                epoch.sCarrierMp[s] = 0
            }

            // Fractional C/N0 from revision 3.
            if (rev >= 3) epoch.sCn0DbHz[s] += (misc and 0x7) * 0.03125f
        }
    }

    private fun sbU8(block: SbfBlock, base: Int, sbSize: Int, off: Int): Int =
        if (off < sbSize) block.u8(base + off) else 0

    private fun sbU16(block: SbfBlock, base: Int, sbSize: Int, off: Int): Int =
        if (off + 2 <= sbSize) block.u16(base + off) else 0

    /** `sbfread_MeasFullRange_Decode`: overlays full-range code and phase onto [epoch]. */
    fun decodeMeasFullRange(block: SbfBlock, epoch: MeasEpoch) {
        val n = block.u8(14)
        val sbLength = block.u8(15)
        // Fix vs C: SBLength 0 divides by zero in the C code.
        if (sbLength == 0) return
        val nrSb = subBlockCount(block, n, sbLength)

        for (i in 0 until nrSb) {
            val base = 20 + i * sbLength
            val freqNrAnt = block.u8(base + 3)
            val ant = (freqNrAnt ushr 5) and 7
            val fnPlus8 = freqNrAnt and 0x1F
            val signal = block.u8(base + 1)

            if (ant >= MeasLimits.ANTENNAS) continue
            val ch = findChannel(epoch, block.u8(base))
            if (ch < 0) continue
            val s = findSignal(epoch, ch, ant, signal)
            if (s < 0) continue
            epoch.touch(ch)

            val wavelength = SignalType.wavelength(signal, fnPlus8 - 8)
            val prM = block.f64(base + 4)
            epoch.sPrM[s] = prM
            epoch.sLCycles[s] = (block.f32(base + 12) + prM) / wavelength

            // C/N0 is encoded from revision 1.
            if (block.revision > 0 && 18 <= sbLength) {
                epoch.sCn0DbHz[s] = block.i16(base + 16).toFloat() / 100.0f
            }
        }
    }

    private companion object {
        const val LOCKTIME_UNKNOWN = 0xFFFFFFFFL

        /** `SISMeasMinPRN`: first internal PRN of the constellation of each signal. */
        val SIS_MIN_PRN = intArrayOf(
            Svid.GPS_MIN, Svid.GPS_MIN, Svid.GPS_MIN, Svid.GPS_MIN, Svid.GPS_MIN, Svid.GPS_MIN, Svid.QZS_MIN, Svid.QZS_MIN,
            Svid.GLO_MIN, Svid.GLO_MIN, Svid.GLO_MIN, Svid.GLO_MIN, Svid.GLO_MIN, Svid.BDS_MIN, Svid.BDS_MIN, Svid.IRN_MIN,
            0, Svid.GAL_MIN, 0, Svid.GAL_MIN, Svid.GAL_MIN, Svid.GAL_MIN, Svid.GAL_MIN, Svid.LBAND_MIN,
            Svid.SBAS_MIN, Svid.SBAS_MIN, Svid.QZS_MIN, Svid.QZS_MIN, Svid.BDS_MIN, Svid.BDS_MIN, Svid.BDS_MIN, 0,
            Svid.QZS_MIN, Svid.QZS_MIN, Svid.BDS_MIN, 0, Svid.IRN_MIN, Svid.IRN_MIN, Svid.QZS_MIN, Svid.QZS_MIN,
            0, 0, 0, 0, 0, 0, 0, 0,
        )

        val SIS_GPS = intArrayOf(SignalType.GPS_L1CA, SignalType.GPS_L2C, SignalType.GPS_L5, SignalType.GPS_L1P, SignalType.GPS_L2P, SignalType.GPS_L1C)
        val SIS_GLO = intArrayOf(SignalType.GLO_L1CA, SignalType.GLO_L2CA, SignalType.GLO_L1P, SignalType.GLO_L2P, SignalType.GLO_L3)
        val SIS_GAL = intArrayOf(SignalType.GAL_E1BC, SignalType.GAL_E5A, SignalType.GAL_E5B, SignalType.GAL_E6BC, SignalType.GAL_E5)
        val SIS_BDS = intArrayOf(SignalType.BDS_B1I, SignalType.BDS_B2I, SignalType.BDS_B3, SignalType.BDS_B1C, SignalType.BDS_B2A, SignalType.BDS_B2B)
        val SIS_SBS = intArrayOf(SignalType.SBS_L1CA, SignalType.SBS_L5)
        val SIS_IRN = intArrayOf(SignalType.IRN_L5, SignalType.IRN_L1, SignalType.IRN_S1)
        val SIS_QZS = intArrayOf(
            SignalType.QZS_L1CA, SignalType.QZS_L2C, SignalType.QZS_L5, SignalType.QZS_L6,
            SignalType.QZS_L1C, SignalType.QZS_L1S, SignalType.QZS_L5S, SignalType.QZS_L1CB,
        )

        /** Original allocated `IntArray(0)` here on every call. */
        val SIS_NONE = IntArray(0)
    }
}
