package sbf

/** Lets the collector look at the next block in the input without consuming it. */
fun interface BlockLookahead {
    /** The next valid block, or null at end of input. */
    fun peekNext(): SbfBlock?
}

/**
 * Collects the measurement blocks of one epoch and decodes them together
 * (`sbfread_MeasCollectAndDecode` in sbfread_meas.c).
 *
 * Feed it every block in input order with [add]. Each completed epoch goes to the
 * `onEpoch` callback. The [MeasEpoch] passed there is reused, so read it before
 * returning.
 *
 * End of epoch is detected in one of two ways:
 * - With a [BlockLookahead] (file input): the epoch ends when the next block has another
 *   TOW, is not a measurement block, is EndOfMeas, or there is no next block.
 * - Without one (live stream): when [predictEndOfEpoch] is true, the epoch ends as soon as
 *   the same set of block kinds as in the previous epoch has been received. Call [flush]
 *   at the end of the stream.
 *
 * @param enabledTypes which block families to decode: [MEAS3], [MEAS_EPOCH] or [ALL].
 */
class MeasEpochCollector(private val enabledTypes: Int = ALL) {

    var predictEndOfEpoch = true

    private val ranges = arrayOfNulls<SbfBlock>(MeasLimits.ANTENNAS)
    private val doppler = arrayOfNulls<SbfBlock>(MeasLimits.ANTENNAS)
    private val cn0HiRes = arrayOfNulls<SbfBlock>(MeasLimits.ANTENNAS)
    private val pp = arrayOfNulls<SbfBlock>(MeasLimits.ANTENNAS)
    private val mp = arrayOfNulls<SbfBlock>(MeasLimits.ANTENNAS)
    private var measEpochBlock: SbfBlock? = null
    private var measExtraBlock: SbfBlock? = null
    private var measFullRangeBlock: SbfBlock? = null
    private var sisMeasBlock: SbfBlock? = null

    private var currentTow = Dnu.U32
    private var towAtLastMeasEpoch = Dnu.U32
    private var blocksSeenAtLastEpoch = 0
    private var blocksSeenAtThisEpoch = 0

    private val refEpochs = Array(MeasLimits.ANTENNAS) { Meas3RefEpoch() }
    private val meas3 = Meas3Decoder()
    private val lockTimes = LockTimes()
    private val epoch = MeasEpoch()

    /** Processes one block; calls [onEpoch] for each measurement epoch it completes. */
    fun add(block: SbfBlock, lookahead: BlockLookahead?, onEpoch: (MeasEpoch) -> Unit) {
        val number = block.number

        // A block from another epoch, or a non-measurement block, closes the collected epoch.
        if ((block.tow != currentTow || !BlockNumber.isGnssMeasBlock(number) || number == BlockNumber.END_OF_MEAS) &&
            blocksSeenAtThisEpoch != 0
        ) {
            // Fix vs C: the C code decodes this epoch into the caller's buffer without updating
            // lock times, and a second epoch completed in the same call overwrites it. Here it
            // is delivered like any other epoch.
            if (processEpoch()) {
                lockTimes.update(epoch)
                onEpoch(epoch)
            }
            blocksSeenAtLastEpoch = blocksSeenAtThisEpoch
            blocksSeenAtThisEpoch = 0
        }

        currentTow = block.tow
        store(block, number)

        var endOfEpoch = false
        if (lookahead != null) {
            if (blocksSeenAtThisEpoch != 0) {
                val next = lookahead.peekNext()
                endOfEpoch = next == null ||
                    next.tow != block.tow ||
                    !BlockNumber.isGnssMeasBlock(next.number) ||
                    next.number == BlockNumber.END_OF_MEAS
            }
        } else if (predictEndOfEpoch && blocksSeenAtThisEpoch != 0 && blocksSeenAtThisEpoch == blocksSeenAtLastEpoch) {
            // All block kinds seen at the previous epoch have arrived.
            endOfEpoch = true
        }

        if (endOfEpoch && processEpoch()) {
            lockTimes.update(epoch)
            blocksSeenAtLastEpoch = blocksSeenAtThisEpoch
            blocksSeenAtThisEpoch = 0
            onEpoch(epoch)
        }
    }

    /** `sbfread_FlushMeasEpoch`: decodes whatever has been collected so far. */
    fun flush(onEpoch: (MeasEpoch) -> Unit) {
        if (processEpoch()) {
            lockTimes.update(epoch)
            onEpoch(epoch)
        }
        blocksSeenAtLastEpoch = blocksSeenAtThisEpoch
        blocksSeenAtThisEpoch = 0
    }

    private fun store(block: SbfBlock, number: Int) {
        when (number) {
            BlockNumber.MEAS3_RANGES -> storeMeas3(ranges, block, block.u8(18) and 7, SEEN_MEAS3_RANGES)
            BlockNumber.MEAS3_DOPPLER -> storeMeas3(doppler, block, block.u16(14) and 7, SEEN_MEAS3_DOPPLER)
            BlockNumber.MEAS3_CN0_HIRES -> storeMeas3(cn0HiRes, block, block.u16(14) and 7, SEEN_MEAS3_CN0_HIRES)
            BlockNumber.MEAS3_PP -> storeMeas3(pp, block, block.u16(14) and 7, SEEN_MEAS3_PP)
            BlockNumber.MEAS3_MP -> storeMeas3(mp, block, block.u16(14) and 7, SEEN_MEAS3_MP)
            BlockNumber.MEAS_EPOCH_2, BlockNumber.GEN_MEAS_EPOCH_1 -> {
                measEpochBlock = block
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_EPOCH
            }
            BlockNumber.MEAS_EXTRA -> {
                measExtraBlock = block
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_EXTRA
            }
            BlockNumber.MEAS_FULL_RANGE -> {
                measFullRangeBlock = block
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_FULL_RANGE
            }
            BlockNumber.SIS_MEAS -> {
                sisMeasBlock = block
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_SIS_MEAS
            }
        }
    }

    private fun storeMeas3(slots: Array<SbfBlock?>, block: SbfBlock, ant: Int, seenBit: Int) {
        if (ant < MeasLimits.ANTENNAS) {
            slots[ant] = block
            blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or (seenBit shl (ant * SEEN_BITS_PER_ANTENNA))
        }
    }

    /** `sbfread_ProcessEpoch`: decodes the collected blocks, then forgets them. */
    private fun processEpoch(): Boolean {
        var ready = false
        val newEpoch = currentTow != towAtLastMeasEpoch || currentTow == Dnu.U32

        if (enabledTypes and MEAS3 != 0 && ranges[0] != null && newEpoch) {
            meas3.decode(ranges, doppler, cn0HiRes, pp, mp, refEpochs, epoch)
            towAtLastMeasEpoch = currentTow
            ready = true
        } else if (enabledTypes and MEAS_EPOCH != 0 && (measEpochBlock != null || sisMeasBlock != null) && newEpoch) {
            // SISMeas is preferred when present.
            val sis = sisMeasBlock
            if (sis != null) LegacyMeasDecoder.decodeSisMeas(sis, epoch)
            else LegacyMeasDecoder.decodeMeasEpoch(measEpochBlock!!, epoch)

            measExtraBlock?.let { LegacyMeasDecoder.decodeMeasExtra(it, epoch) }
            measFullRangeBlock?.let { LegacyMeasDecoder.decodeMeasFullRange(it, epoch) }

            towAtLastMeasEpoch = currentTow
            ready = true
        }

        ranges.fill(null)
        doppler.fill(null)
        cn0HiRes.fill(null)
        pp.fill(null)
        mp.fill(null)
        measEpochBlock = null
        measExtraBlock = null
        measFullRangeBlock = null
        // Fix vs C: sbfread_ProcessEpoch never clears the SISMeas buffer, so once a SISMeas has
        // been seen, that stale block is re-decoded (and preferred over MeasEpoch) in later epochs.
        sisMeasBlock = null

        return ready
    }

    companion object {
        /** Decode Meas3 blocks. */
        const val MEAS3 = 0x1

        /** Decode MeasEpoch / GenMeasEpoch / SISMeas (+ MeasExtra, MeasFullRange). */
        const val MEAS_EPOCH = 0x2

        /** Decode whatever is available (recommended). */
        const val ALL = 0x3

        private const val SEEN_MEAS3_RANGES = 1 shl 0
        private const val SEEN_MEAS3_DOPPLER = 1 shl 1
        private const val SEEN_MEAS3_CN0_HIRES = 1 shl 2
        private const val SEEN_MEAS3_PP = 1 shl 3
        private const val SEEN_MEAS3_MP = 1 shl 4
        private const val SEEN_MEAS_EPOCH = 1 shl 5
        private const val SEEN_MEAS_EXTRA = 1 shl 6
        private const val SEEN_MEAS_FULL_RANGE = 1 shl 7
        private const val SEEN_SIS_MEAS = 1 shl 8
        private const val SEEN_BITS_PER_ANTENNA = 9
    }
}

/**
 * `sbfread_UpdateLockTimes`: derives [MeasSet.lockTimeMs] (lock time since the last cycle
 * slip) from the PLL timer and the lock counter.
 *
 * Fix vs C: the C code keys this history by logical channel number. That overflows its
 * 100-entry table for receiver channels >= 100. On the Meas3 path the channel number is only
 * the satellite's position in the epoch, so one satellite's history leaks into another's.
 * Here the history is keyed by satellite (internal PRN), so the "different PRN on this
 * channel" reset of the C code never triggers.
 */
internal class LockTimes {
    private val lockCountAtLastEpoch = IntArray(PRN_COUNT * SLOTS)
    private val pllTimerAtLockCountIncrementS = LongArray(PRN_COUNT * SLOTS)

    fun update(epoch: MeasEpoch) {
        for (i in 0 until epoch.nbrElements) {
            val chan = epoch.channelData[i]
            val prn = chan.prn
            if (prn !in 0 until PRN_COUNT) continue
            for (ant in 0 until MeasLimits.ANTENNAS) {
                for (sig in 0 until MeasLimits.SIGNALS_PER_SATELLITE) {
                    val m = chan.measSet[ant][sig]
                    if (m.flags == 0 || m.lCycles == Dnu.F64) continue
                    val k = (prn * MeasLimits.ANTENNAS + ant) * MeasLimits.SIGNALS_PER_SATELLITE + sig
                    if (lockCountAtLastEpoch[k] != m.lockCount) {
                        // A lockCount change means a cycle slip or PLL restart.
                        pllTimerAtLockCountIncrementS[k] = (m.pllTimerMs / 1000) and 0xFFFF
                    }
                    // uint32 arithmetic, as in C.
                    m.lockTimeMs = (m.pllTimerMs - pllTimerAtLockCountIncrementS[k] * 1000) and 0xFFFFFFFFL
                    if (m.lockTimeMs == 0L) m.lockTimeMs = 10 // 0 would mean "unknown"
                    lockCountAtLastEpoch[k] = m.lockCount
                }
            }
        }
    }

    private companion object {
        const val PRN_COUNT = Svid.LBAND_UNKNOWN + 1
        const val SLOTS = MeasLimits.ANTENNAS * MeasLimits.SIGNALS_PER_SATELLITE
    }
}
