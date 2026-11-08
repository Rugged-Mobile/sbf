package sbf

/** Meas3 constellation index (`Meas3SatSystem_t`). */
object Meas3System {
    const val GPS = 0
    const val GLO = 1
    const val GAL = 2
    const val BDS = 3
    const val SBAS = 4
    const val QZS = 5
    const val IRN = 6
    const val COUNT = 7
}

/**
 * State kept from the last Meas3 reference epoch of one antenna (`sbfread_Meas3_RefEpoch_t`).
 * Delta-encoded epochs are decoded against it.
 */
internal class Meas3RefEpoch {
    val sigIdx = IntArray(Meas3System.COUNT * SAT_MAX * SIG_MAX)
    val measSet = Array(Meas3System.COUNT * SAT_MAX * SIG_MAX) { MeasSet() }
    val slaveSigMask = IntArray(Meas3System.COUNT * SAT_MAX)
    val prRate64mmS = IntArray(Meas3System.COUNT * SAT_MAX)
    var towMs = Dnu.U32

    // Fix vs C: the C copy is 32 bytes per system, but a GLONASS header can be longer
    // (1 + 8 + 32 + 1 bytes); 64 bytes always fits.
    val satDataCopy = Array(Meas3System.COUNT) { ByteArray(64) }

    fun clear() {
        sigIdx.fill(0)
        for (m in measSet) m.clear()
        slaveSigMask.fill(0)
        prRate64mmS.fill(0)
        towMs = 0L
        for (c in satDataCopy) c.fill(0)
    }

    companion object {
        const val SAT_MAX = 64
        const val SIG_MAX = 16
        fun sat(sys: Int, sat: Int) = sys * SAT_MAX + sat
        fun sig(sys: Int, sat: Int, sig: Int) = (sys * SAT_MAX + sat) * SIG_MAX + sig
    }
}

/**
 * Decoder for Meas3Ranges (4109) and its extension blocks: Meas3Doppler (4111),
 * Meas3CN0HiRes (4110), Meas3PP (4112) and Meas3MP (4113). Ported from the Meas3 section of
 * sbfread_meas.c. Arithmetic is written in the same order as the C code so results are
 * bit-identical.
 */
internal class Meas3Decoder {

    /** Occurrences of each sub-block kind (the `Meas3Ranges_Count_*` globals in C). */
    var countMasterLong = 0L; private set
    var countMasterShort = 0L; private set
    var countMasterDelta = 0L; private set
    var countSlaveLong = 0L; private set
    var countSlaveShort = 0L; private set
    var countSlaveDelta = 0L; private set

    /** The optional extension blocks for one antenna and their read cursors. */
    private class Extensions(
        val doppler: SbfBlock?,
        val cn0HiRes: SbfBlock?,
        val pp: SbfBlock?,
        val mp: SbfBlock?,
    ) {
        var dopplerIdx = 0
        var cn0HiResIdx = 0
        var pp1Idx = 0
        var pp2Idx = 0
        var mpIdx = 0
    }

    /** Outputs of a master sub-block besides its MeasSet. */
    private class MasterInfo {
        var sigIdx = 0
        var slaveSigMask = 0
        var prRate64mmS = 0
    }

    private val master = MeasSet()
    private val slave = MeasSet()
    private val masterInfo = MasterInfo()
    private val sigTable = IntArray(REF_EPOCH_SIG_MAX)
    private val gloFnList = ByteArray(32)

    /**
     * `sbfread_Meas3_Decode`: decodes the Meas3 blocks of all antennas into [epoch].
     * Array index = antenna. [ranges] entries are mandatory per antenna, the others optional.
     */
    fun decode(
        ranges: Array<SbfBlock?>,
        doppler: Array<SbfBlock?>,
        cn0HiRes: Array<SbfBlock?>,
        pp: Array<SbfBlock?>,
        mp: Array<SbfBlock?>,
        refEpochs: Array<Meas3RefEpoch>,
        epoch: MeasEpoch,
    ) {
        epoch.clear()

        for (ant in 0 until MeasLimits.ANTENNAS) {
            // Meas3Ranges is the master block: nothing can be decoded without it.
            val r = ranges[ant] ?: continue

            val misc = r.u8(18)
            val refInterval = REF_INTERVAL_MS.getOrElse(misc ushr 4) { 1L }

            epoch.totalClockJumpMs = MeasEpoch.clockJump(r.u8(15))
            epoch.setTime(r.tow, r.wnc)
            epoch.dopplerVarFactor = MeasEpoch.DEFAULT_DOPPLER_VARIANCE_FACTOR
            epoch.setCommonFlagsFromSbf(r.u8(14))

            // The Reserved field is a format version; this decoder handles versions < 32.
            if (r.u8(19) > 31) {
                epoch.clear()
                return
            }

            val ref = refEpochs[ant]
            val tow = epoch.towMs
            if (tow % refInterval == 0L) {
                // New reference epoch: forget the previous one.
                ref.clear()
                ref.towMs = tow
            }

            // Only decode if this is a reference epoch or the matching reference is available.
            if (tow % refInterval == 0L || ref.towMs == (tow / refInterval) * refInterval) {
                val ext = Extensions(doppler[ant], cn0HiRes[ant], pp[ant], mp[ant])
                val constellations = r.u16(16)
                val prRateAvailable = misc and 8 != 0
                var p = 20
                for (sys in 0 until Meas3System.COUNT) {
                    if (constellations and (1 shl sys) == 0) continue
                    val n = decodeConstellation(r, p, ext, ant, sys, epoch, ref, refInterval, prRateAvailable)
                    if (n == 0) {
                        // Invalid format: discard everything.
                        epoch.clear()
                        return
                    }
                    p += n
                }
            }
        }
    }

    /** `sbfread_Meas3_DecodeConstellation`. Returns the bytes consumed, 0 for invalid data. */
    private fun decodeConstellation(
        r: SbfBlock, start: Int, ext: Extensions, ant: Int, sys: Int,
        epoch: MeasEpoch, ref: Meas3RefEpoch, refInterval: Long, prRateAvailable: Boolean,
    ): Int {
        val data = r.bytes
        val isRefEpoch = epoch.towMs % refInterval == 0L

        // A first byte of 0 means: reuse the M3SatData header of the reference epoch.
        val reuseHeader = data.u8(start) == 0
        val hdr = if (reuseHeader) ref.satDataCopy[sys] else data
        val hdrOff = if (reuseHeader) 0 else start

        var n = 0
        val bf1 = hdr.u8(hdrOff + n)
        var nb = bf1 and 0x7
        if (nb == 7) nb = 8
        val sigIdxMasterShort = (bf1 ushr 3) and 0xF
        val sigExcludedPresent = (bf1 ushr 7) != 0
        n++

        var satMask = 0L
        var nSats = 0
        for (ii in 0 until nb) {
            val b = hdr.u8(hdrOff + n)
            satMask = satMask or (b.toLong() shl (ii * 8))
            nSats += Integer.bitCount(b)
            n++
        }

        gloFnList.fill(0)
        if (sys == Meas3System.GLO) {
            val len = (nSats + 1) / 2
            for (i in 0 until len) gloFnList[i] = hdr.u8(hdrOff + n + i).toByte()
            n += len
        }

        var bdsLongRange = 0
        if (sys == Meas3System.BDS) {
            bdsLongRange = hdr.u16(hdrOff + n)
            n += 2
        }

        val sigExcluded: Int
        if (sigExcludedPresent) {
            sigExcluded = hdr.u8(hdrOff + n)
            n++
        } else {
            sigExcluded = 0
        }

        var p = if (reuseHeader) start + 1 else start + n

        // Remember this header if this is a reference epoch (copied from the block, as in C).
        if (isRefEpoch) {
            val copy = ref.satDataCopy[sys]
            for (i in 0 until minOf(n, copy.size)) copy[i] = data.u8(start + i).toByte()
        }

        prepareSigTable(sys, sigExcluded)

        var satCnt = 0
        var satIdx = 0
        while (satIdx < Meas3RefEpoch.SAT_MAX && satCnt < nSats) {
            if (satMask and (1L shl satIdx) != 0L) {
                val gloFn = ((gloFnList[satCnt / 2].toInt() ushr (4 * (satCnt % 2))) and 0xF) - 8
                val chan = measChannel(epoch, sys, satIdx)
                chan.prn = SVID_BASE[sys] + satIdx
                chan.fnPlus8 = gloFn + 8

                // As in C, `1 shl satCnt` wraps at 32 (JVM and x86 both mask the shift count).
                val shortPrBase = if (bdsLongRange and (1 shl satCnt) != 0) 34e6 else PR_BASE_M[sys]
                val masterSize = decodeMaster(
                    data, p, sys, satIdx, gloFn, shortPrBase, sigIdxMasterShort, ref,
                    epoch.towMs % refInterval, prRateAvailable,
                )
                if (masterSize == 0) return 0
                p += masterSize

                val mi = masterInfo
                if (isRefEpoch) {
                    ref.sigIdx[Meas3RefEpoch.sig(sys, satIdx, 0)] = mi.sigIdx
                    ref.slaveSigMask[Meas3RefEpoch.sat(sys, satIdx)] = mi.slaveSigMask
                    ref.prRate64mmS[Meas3RefEpoch.sat(sys, satIdx)] = mi.prRate64mmS
                    ref.measSet[Meas3RefEpoch.sig(sys, satIdx, mi.sigIdx)].copyFrom(master)
                }
                val masterRefSet = ref.measSet[Meas3RefEpoch.sig(sys, satIdx, mi.sigIdx)]
                if (master.pllTimerMs > masterRefSet.pllTimerMs) masterRefSet.pllTimerMs = master.pllTimerMs

                val wavelengthMaster = wavelength(mi.sigIdx, gloFn)
                addMasterDoppler(master, ext, wavelengthMaster, ref.prRate64mmS[Meas3RefEpoch.sat(sys, satIdx)])
                addPpInfo(master, ext)
                addMpInfo(master, ext)

                // Keep the master's C/N0 adjustment until its slaves are decoded, as they
                // need the unadjusted master value.
                var cn0MasterHiRes = 0f
                if (ext.cn0HiRes != null) {
                    cn0MasterHiRes = cn0HiResNibble(ext.cn0HiRes, ext.cn0HiResIdx).toFloat() * .0625f - 0.5f
                    ext.cn0HiResIdx++
                }

                var slaveSigMask = mi.slaveSigMask
                var slaveCnt = 0
                var sigIdx = 1
                while (sigIdx < Meas3RefEpoch.SIG_MAX && slaveSigMask != 0) {
                    if (slaveSigMask and (1 shl sigIdx) != 0) {
                        val masterRef = ref.measSet[Meas3RefEpoch.sig(sys, satIdx, ref.sigIdx[Meas3RefEpoch.sig(sys, satIdx, 0)])]
                        // Fix vs C: SlaveCnt + 1 can reach 16 (past the 16-entry array) for malformed data.
                        val refSlot = minOf(slaveCnt + 1, Meas3RefEpoch.SIG_MAX - 1)
                        val slaveRef = ref.measSet[Meas3RefEpoch.sig(sys, satIdx, ref.sigIdx[Meas3RefEpoch.sig(sys, satIdx, refSlot)])]

                        p += decodeSlave(data, p, sigIdx, gloFn, master, mi.sigIdx, masterRef, slaveRef)

                        addSlaveDoppler(slave, master, ext, wavelengthMaster, wavelength(sigIdx, gloFn))
                        addPpInfo(slave, ext)
                        addMpInfo(slave, ext)

                        if (isRefEpoch) {
                            ref.sigIdx[Meas3RefEpoch.sig(sys, satIdx, refSlot)] = sigIdx
                            ref.measSet[Meas3RefEpoch.sig(sys, satIdx, sigIdx)].copyFrom(slave)
                        }
                        val slaveRefSet = ref.measSet[Meas3RefEpoch.sig(sys, satIdx, sigIdx)]
                        if (slave.pllTimerMs > slaveRefSet.pllTimerMs) slaveRefSet.pllTimerMs = slave.pllTimerMs

                        if (ext.cn0HiRes != null) {
                            slave.cn0DbHz += cn0HiResNibble(ext.cn0HiRes, ext.cn0HiResIdx) * .0625f - 0.5f
                            ext.cn0HiResIdx++
                        }

                        if (sigIdx < MeasLimits.SIGNALS_PER_SATELLITE) chan.measSet[ant][sigIdx].copyFrom(slave)

                        slaveCnt++
                        slaveSigMask = slaveSigMask xor (1 shl sigIdx)
                    }
                    sigIdx++
                }

                // Now apply the C/N0 adjustment and store the master.
                master.cn0DbHz += cn0MasterHiRes
                if (mi.sigIdx < MeasLimits.SIGNALS_PER_SATELLITE) chan.measSet[ant][mi.sigIdx].copyFrom(master)

                satCnt++

                // Signals unknown to this decoder get SignalType.LAST: invalidate them.
                for (m in chan.measSet[ant]) {
                    if (m.signalType >= SignalType.LAST) m.flags = 0
                }
            }
            satIdx++
        }

        return p - start
    }

    /** `sbfread_PrepareSigTable`: removes the excluded signals and compacts the table. */
    private fun prepareSigTable(sys: Int, sigExcluded: Int) {
        var nrKept = 0
        for (i in 0 until Meas3RefEpoch.SIG_MAX) {
            // Only the first 8 signals can be excluded.
            if (sigExcluded and (1 shl i) == 0) {
                sigTable[nrKept++] = DEFAULT_SIG_TABLE[sys][i]
            }
        }
        for (i in nrKept until Meas3RefEpoch.SIG_MAX) sigTable[i] = SignalType.LAST
    }

    private fun signalOf(sigIdx: Int): Int = if (sigIdx < Meas3RefEpoch.SIG_MAX) sigTable[sigIdx] else SignalType.LAST

    private fun wavelength(sigIdx: Int, gloFn: Int): Double = SignalType.wavelength(signalOf(sigIdx), gloFn)

    /** `sbfread_Meas3_GetMeasChannel`: channel of the satellite, appended if new. */
    private fun measChannel(epoch: MeasEpoch, sys: Int, satIdx: Int): MeasChannel {
        val prn = SVID_BASE[sys] + satIdx
        for (i in 0 until epoch.nbrElements) {
            if (epoch.channelData[i].prn == prn) return epoch.channelData[i]
        }
        if (epoch.nbrElements < MeasLimits.LOGICAL_CHANNELS) epoch.nbrElements++
        val chan = epoch.channelData[epoch.nbrElements - 1]
        // The channel number is just a counter.
        chan.channel = epoch.nbrElements - 1
        return chan
    }

    /** `sbfread_Meas3_DecodeMaster`: decodes into [master] and [masterInfo]; returns the size, 0 if invalid. */
    private fun decodeMaster(
        buf: ByteArray, p: Int, sys: Int, satIdx: Int, gloFn: Int, shortPrBase: Double,
        sigIdxMasterShort: Int, ref: Meas3RefEpoch, timeSinceRefMs: Long, prRateAvailable: Boolean,
    ): Int {
        val m = master
        val mi = masterInfo
        m.clear()
        val b0 = buf.u8(p)

        when {
            b0 and 1 == 1 -> {
                // MasterShort
                val bf1 = buf.i32(p)
                val prLsb = buf.u32(p + 4)
                val cmc = (bf1 ushr 1) and 0x3FFFF
                val prMsb = (bf1 ushr 19) and 1
                val lti3 = (bf1 ushr 20) and 0x7
                val cn0 = (bf1 ushr 23) and 0x1F
                val sigList = (bf1 ushr 28) and 0xF

                mi.sigIdx = sigIdxMasterShort
                val wl = wavelength(mi.sigIdx, gloFn)

                m.signalType = signalOf(mi.sigIdx)
                m.flags = MeasFlag.VALIDITY
                m.prM = shortPrBase + (prLsb.toDouble() + 4294967296.0 * prMsb.toDouble()) * .001
                m.lCycles = if (cmc == 0) Dnu.F64 else m.prM / wl - 131.072 + cmc.toDouble() * .001
                m.cn0DbHz = cn0.toFloat() + 24.0f
                m.pllTimerMs = LTI_TO_PLL_TIMER_MS[lti3]
                if (lti3 == 0) m.flags = m.flags or MeasFlag.HALF_CYCLE_AMBIGUITY

                mi.prRate64mmS = if (prRateAvailable) buf.i16(p + 8) else 0
                mi.slaveSigMask = sigList shl (mi.sigIdx + 1)
                countMasterShort++
                return if (prRateAvailable) 10 else 8
            }

            b0 and 3 == 0 -> {
                // MasterLong
                val bf1 = buf.i32(p)
                val prLsb = buf.u32(p + 4)
                val bf2 = buf.u16(p + 8)
                val bf3 = buf.u8(p + 10)
                val prMsb = (bf1 ushr 2) and 0xF
                val cmc = (bf1 ushr 6) and 0x3FFFFF
                val lti4 = (bf1 ushr 28) and 0xF
                val cn0 = bf2 and 0x3F
                var sigMask = (bf2 ushr 6) and 0x1FF
                val cont = (bf2 ushr 15) and 0x1

                if (cont != 0) sigMask = sigMask or ((bf3 and 0x7F) shl 9)
                if (sigMask == 0) return 0

                mi.sigIdx = Integer.numberOfTrailingZeros(sigMask)
                val wl = wavelength(mi.sigIdx, gloFn)
                val signal = signalOf(mi.sigIdx)

                m.signalType = signal
                m.flags = MeasFlag.VALIDITY
                m.prM = (prLsb.toDouble() + 4294967296.0 * prMsb.toDouble()) * .001
                m.lCycles = if (cmc == 0) Dnu.F64 else m.prM / wl - 2097.152 + cmc.toDouble() * .001
                m.cn0DbHz = if (SignalType.isGpsPCode(signal)) cn0.toFloat() else cn0.toFloat() + 10.0f
                m.pllTimerMs = LTI_TO_PLL_TIMER_MS[lti4]
                if (lti4 == 0) m.flags = m.flags or MeasFlag.HALF_CYCLE_AMBIGUITY

                // Fix vs C: sbfread_meas.c reads PRR at offset 10 regardless of Cont. BF3 is
                // only present when Cont = 1, so PRR follows it at offset 10 + Cont (the C code
                // computes the sub-block size as 12 + Cont, consistent with this).
                mi.prRate64mmS = if (prRateAvailable) buf.i16(p + 10 + cont) else 0
                mi.slaveSigMask = sigMask xor (1 shl mi.sigIdx)
                countMasterLong++
                return (if (prRateAvailable) 12 else 10) + cont
            }

            b0 and 0xC == 0xC -> {
                // MasterDeltaL (long delta)
                val bf2 = buf.i32(p + 1)
                val pr = ((b0 ushr 4) shl 13) or (bf2 and 0x1FFF)
                val cn0 = (bf2 ushr 13) and 0x7
                val cmc = bf2 ushr 16
                decodeMasterDelta(sys, satIdx, gloFn, ref, timeSinceRefMs, pr, cn0, cmc, 65.536, 32.768, 4.0f)
                return 5
            }

            else -> {
                // MasterDeltaS (short delta)
                val bf1 = buf.i32(p)
                val pr = (bf1 ushr 4) and 0x3FFF
                val cmc = (bf1 ushr 18) and 0x3FFF
                val cn0 = (bf1 ushr 2) and 0x3
                decodeMasterDelta(sys, satIdx, gloFn, ref, timeSinceRefMs, pr, cn0, cmc, 8.192, 8.192, 1.0f)
                return 4
            }
        }
    }

    /** Common part of MasterDeltaL / MasterDeltaS: decode relative to the reference master. */
    private fun decodeMasterDelta(
        sys: Int, satIdx: Int, gloFn: Int, ref: Meas3RefEpoch, timeSinceRefMs: Long,
        pr: Int, cn0: Int, cmc: Int, prOffset: Double, carrierOffset: Double, cn0Offset: Float,
    ) {
        val m = master
        val mi = masterInfo
        mi.sigIdx = ref.sigIdx[Meas3RefEpoch.sig(sys, satIdx, 0)]
        val wl = wavelength(mi.sigIdx, gloFn)
        val masterRef = ref.measSet[Meas3RefEpoch.sig(sys, satIdx, mi.sigIdx)]

        m.signalType = signalOf(mi.sigIdx)
        m.flags = masterRef.flags and (MeasFlag.VALIDITY or MeasFlag.HALF_CYCLE_AMBIGUITY)
        m.pllTimerMs = masterRef.pllTimerMs

        // Predicted range change since the reference epoch, in mm (C integer arithmetic).
        val driftMm = ref.prRate64mmS[Meas3RefEpoch.sat(sys, satIdx)].toLong() * 64 * timeSinceRefMs.toInt() / 1000
        m.prM = masterRef.prM + driftMm.toDouble() * .001 + pr.toDouble() * .001 - prOffset
        m.lCycles = if (cmc == 0) Dnu.F64
        else (m.prM - masterRef.prM) / wl + masterRef.lCycles - carrierOffset + cmc.toDouble() * .001
        m.cn0DbHz = masterRef.cn0DbHz - cn0Offset + cn0.toFloat()

        mi.prRate64mmS = 0
        mi.slaveSigMask = ref.slaveSigMask[Meas3RefEpoch.sat(sys, satIdx)]
        countMasterDelta++
    }

    /** `sbfread_Meas3_DecodeSlave`: decodes into [slave]; returns the size. */
    private fun decodeSlave(
        buf: ByteArray, p: Int, sigIdx: Int, gloFn: Int,
        master: MeasSet, masterSigIdx: Int, masterRef: MeasSet, slaveRef: MeasSet,
    ): Int {
        val wlMaster = wavelength(masterSigIdx, gloFn)
        val wlSlave = wavelength(sigIdx, gloFn)
        val m = slave
        val signal = signalOf(sigIdx)

        m.clear()
        m.flags = MeasFlag.VALIDITY
        m.signalType = signal

        val b0 = buf.u8(p)
        when {
            b0 and 1 == 1 -> {
                // SlaveShort
                val bf1 = buf.i32(p)
                val bf2 = buf.u8(p + 4)
                val cmcRes = (bf1 ushr 1) and 0xFFFF
                val prRel = (bf1 ushr 17).toLong()
                val lti3 = bf2 and 0x7
                val cn0 = bf2 ushr 3

                m.prM = if (wlMaster < wlSlave) master.prM + prRel * .001 - 10
                else master.prM - prRel * .001 + 10
                m.lCycles = if (cmcRes == 0) Dnu.F64
                else m.prM / wlSlave + (master.lCycles - master.prM / wlMaster) * wlSlave / wlMaster - 32.768 + cmcRes * .001
                m.cn0DbHz = if (SignalType.isGpsPCode(signal)) master.cn0DbHz - 3.0f - cn0.toFloat()
                else cn0.toFloat() + 24.0f
                m.pllTimerMs = LTI_TO_PLL_TIMER_MS[lti3]
                if (lti3 == 0) m.flags = m.flags or MeasFlag.HALF_CYCLE_AMBIGUITY
                countSlaveShort++
                return 5
            }

            b0 and 3 == 0 -> {
                // SlaveLong
                val bf1 = buf.i32(p)
                val prLsbRel = buf.u16(p + 4)
                val bf3 = buf.u8(p + 6)
                val cmc = (bf1 ushr 2) and 0x3FFFFF
                val lti4 = (bf1 ushr 24) and 0xF
                val prMsbRel = (bf1 ushr 28) and 0x7
                val cn0 = bf3 and 0x3F

                m.prM = master.prM + (prMsbRel * 65536 + prLsbRel) * .001 - 262.144
                m.lCycles = if (cmc == 0) Dnu.F64 else m.prM / wlSlave - 2097.152 + cmc * 0.001
                m.cn0DbHz = if (SignalType.isGpsPCode(signal)) cn0.toFloat() else cn0.toFloat() + 10.0f
                m.pllTimerMs = LTI_TO_PLL_TIMER_MS[lti4]
                if (lti4 == 0) m.flags = m.flags or MeasFlag.HALF_CYCLE_AMBIGUITY
                countSlaveLong++
                return 7
            }

            else -> {
                // SlaveDelta, relative to this slave's value at the reference epoch
                val bf1 = buf.u16(p)
                val dCarrier = buf.u8(p + 2)
                val dPr = (bf1 ushr 2) and 0xFFF
                val cn0 = bf1 ushr 14

                m.flags = slaveRef.flags and (MeasFlag.VALIDITY or MeasFlag.HALF_CYCLE_AMBIGUITY)
                m.lCycles = slaveRef.lCycles + (master.lCycles - masterRef.lCycles) * wlMaster / wlSlave - 0.128 + dCarrier * 0.001
                m.prM = slaveRef.prM + (m.lCycles - slaveRef.lCycles) * wlSlave - 2.048 + dPr * 0.001
                m.cn0DbHz = slaveRef.cn0DbHz - 2.0f + cn0.toFloat()
                m.pllTimerMs = slaveRef.pllTimerMs
                countSlaveDelta++
                return 3
            }
        }
    }

    /** `sbfread_Meas3_GetPRRate_mm_s`: next variable-length PR rate from Meas3Doppler. */
    private fun nextPrRateMmS(ext: Extensions): Int {
        val block = ext.doppler ?: return Dnu.I32
        val v = block.i32(16 + ext.dopplerIdx)
        val abs: Int
        when {
            v and 2 == 0 -> { abs = (v and 0xFF) ushr 2; ext.dopplerIdx += 1 }
            v and 6 == 2 -> { abs = (v and 0xFFFF) ushr 3; ext.dopplerIdx += 2 }
            v and 0xE == 6 -> { abs = (v and 0xFFFFFF) ushr 4; ext.dopplerIdx += 3 }
            else -> { abs = v ushr 4; ext.dopplerIdx += 4 }
        }
        return if (v and 1 == 0) abs else -abs
    }

    private fun addMasterDoppler(m: MeasSet, ext: Extensions, wavelength: Double, refPrRate64mmS: Int) {
        val prr = nextPrRateMmS(ext)
        m.dopplerHz = if (prr == Dnu.I32 || prr == -268435455) Dnu.F32
        else (-(prr + refPrRate64mmS * 64) * 0.001 / wavelength).toFloat()
    }

    private fun addSlaveDoppler(m: MeasSet, master: MeasSet, ext: Extensions, wlMaster: Double, wlSlave: Double) {
        val prr = nextPrRateMmS(ext)
        m.dopplerHz = if (prr == Dnu.I32 || prr == -1048575) Dnu.F32
        else ((master.dopplerHz * wlMaster * 1000.0 - prr) * 0.001 / wlSlave).toFloat()
    }

    private fun cn0HiResNibble(block: SbfBlock, idx: Int): Int =
        (block.u8(16 + idx / 2) ushr ((idx % 2) * 4)) and 0xF

    /** `sbfread_Meas3_AddPPInfo`: APME flag, lock count and raw C/N0 from Meas3PP. */
    private fun addPpInfo(m: MeasSet, ext: Extensions) {
        m.rawCn0DbHz = (m.cn0DbHz.toInt() / 2 * 2) and 0xFF
        val pp = ext.pp ?: return

        val data = 16
        val bits1 = pp.u8(data + ext.pp1Idx / 8) or (pp.u8(data + ext.pp1Idx / 8 + 1) shl 8)
        val v1 = bits1 ushr (ext.pp1Idx % 8)
        val start2 = (pp.u16(14) ushr 8) * 4

        if (v1 and 1 != 0) m.flags = m.flags or MeasFlag.APME_IN_SYNC
        m.lockCount = (v1 ushr 1) and 0xF
        ext.pp1Idx += 5

        // Second section, sub-block type 0: raw C/N0 values.
        if (start2 != 0 && pp.u8(data + start2) and 0xF == 0) {
            val o = data + start2 + 2
            val bits2 = pp.u8(o + ext.pp2Idx / 8) or (pp.u8(o + ext.pp2Idx / 8 + 1) shl 8)
            val v2 = bits2 ushr (ext.pp2Idx % 8)
            if (v2 and 1 == 0) {
                // The raw C/N0 is the same as the standard C/N0.
                ext.pp2Idx++
            } else {
                var raw = ((v2 ushr 1) and 0x1F) * 2
                if (!SignalType.isGpsPCode(m.signalType)) raw += 10
                m.rawCn0DbHz = raw and 0xFF
                ext.pp2Idx += 6
            }
        }
    }

    /** `sbfread_Meas3_AddMPInfo`: code and carrier multipath corrections from Meas3MP. */
    private fun addMpInfo(m: MeasSet, ext: Extensions) {
        val mp = ext.mp
        if (mp == null) {
            m.mpMm = 0
            m.carrierMp1_512c = 0
            return
        }
        val o = 16 + ext.mpIdx / 8
        val bits = mp.u8(o) or (mp.u8(o + 1) shl 8) or (mp.u8(o + 2) shl 16) or (mp.u8(o + 3) shl 24)
        val v = bits ushr (ext.mpIdx % 8)
        when (v and 3) {
            0 -> {
                m.mpMm = 0
                m.carrierMp1_512c = 0
                ext.mpIdx += 2
            }
            1, 2 -> {
                val c = (v ushr 2) and 0x7F
                val l = (v ushr 9) and 0x1F
                m.mpMm = if (v and 3 == 1) c * 10 else -(c * 10)
                m.carrierMp1_512c = if (l < 16) l else l - 32
                ext.mpIdx += 14
            }
            else -> {
                val c = (v ushr 2) and 0x7FF
                val l = (v ushr 13) and 0xFF
                m.mpMm = if (c < 1024) c * 10 else (c - 2048) * 10
                m.carrierMp1_512c = if (l < 128) l else l - 256
                ext.mpIdx += 21
            }
        }
    }

    companion object {
        private const val REF_EPOCH_SIG_MAX = Meas3RefEpoch.SIG_MAX

        /** Reference epoch interval for each code in Meas3Ranges `Misc` bits 4-7 (other codes: 1 ms). */
        private val REF_INTERVAL_MS = longArrayOf(1, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000)

        /** Base pseudorange per constellation for MasterShort (BeiDou GEO/IGSO use 34e6). */
        private val PR_BASE_M = doubleArrayOf(19e6, 19e6, 22e6, 20e6, 34e6, 34e6, 34e6)

        private val SVID_BASE = intArrayOf(Svid.GPS_MIN, Svid.GLO_MIN, Svid.GAL_MIN, Svid.BDS_MIN, Svid.SBAS_MIN, Svid.QZS_MIN, Svid.IRN_MIN)

        /** Meas3 lock time indicator to PLL timer (ms). */
        private val LTI_TO_PLL_TIMER_MS = longArrayOf(
            0, 60000, 30000, 15000, 10000, 5000, 2000, 1000, 500, 200, 100, 50, 40, 20, 10, 0,
        )

        private const val L = SignalType.LAST

        /** Default Meas3 signal index to signal type, per constellation. */
        private val DEFAULT_SIG_TABLE: Array<IntArray> = arrayOf(
            intArrayOf(SignalType.GPS_L1CA, SignalType.GPS_L2C, SignalType.GPS_L5, SignalType.GPS_L1P, SignalType.GPS_L2P, SignalType.GPS_L1C, L, L, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.GLO_L1CA, SignalType.GLO_L2CA, SignalType.GLO_L1P, SignalType.GLO_L2P, SignalType.GLO_L3, L, L, L, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.GAL_E1BC, SignalType.GAL_E5A, SignalType.GAL_E5B, SignalType.GAL_E6BC, SignalType.GAL_E5, SignalType.RES16, L, L, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.BDS_B1I, SignalType.BDS_B2I, SignalType.BDS_B3, SignalType.BDS_B1C, SignalType.BDS_B2A, SignalType.BDS_B2B, L, L, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.SBS_L1CA, SignalType.SBS_L5, L, L, L, L, L, L, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.QZS_L1CA, SignalType.QZS_L2C, SignalType.QZS_L5, SignalType.QZS_L6, SignalType.QZS_L1C, SignalType.QZS_L1S, SignalType.QZS_L5S, SignalType.QZS_L1CB, L, L, L, L, L, L, L, L),
            intArrayOf(SignalType.IRN_L5, SignalType.IRN_L1, SignalType.IRN_S1, L, L, L, L, L, L, L, L, L, L, L, L, L),
        )
    }
}
