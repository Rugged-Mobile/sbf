package ruggedmobile.sbf

/** Receives each completed measurement epoch. */
fun interface MeasEpochSink {
    /**
     * Called once per decoded epoch. [epoch] is reused for the next epoch: read it here, or
     * keep a copy with [MeasEpoch.copyFrom].
     */
    fun onEpoch(epoch: MeasEpoch)
}

/**
 * Collects the measurement blocks of one epoch and decodes them together
 * (`sbfread_MeasCollectAndDecode` in sbfread_meas.c).
 *
 * Feed it every block in input order with [add]. Each completed epoch goes to [sink].
 *
 * End of epoch is detected in one of two ways:
 * - With a [lookahead] (file input; pass the [SbfBlockReader]): the epoch ends when the next
 *   block has another TOW, is not a measurement block, is EndOfMeas, or there is no next block.
 * - Without one (live stream): when [predictEndOfEpoch] is true, the epoch ends as soon as
 *   the same set of block kinds as in the previous epoch has been received. Call [flush]
 *   at the end of the stream.
 *
 * Memory: everything is allocated up front or grown once to the largest block seen. Blocks
 * of the current epoch are copied into per-slot buffers that are reused, the epoch and the
 * Meas3 reference epochs are flat arrays, and the sink and look-ahead are fixed at
 * construction. After warm-up, [add] and [flush] allocate nothing.
 *
 * @param lookahead the block source's look-ahead, or null for live streams.
 * @param enabledTypes which block families to decode: [MEAS3], [MEAS_EPOCH] or [ALL].
 * @param sink receives every completed epoch.
 */
class MeasEpochCollector(
    private val lookahead: BlockLookahead?,
    private val enabledTypes: Int = ALL,
    private val sink: MeasEpochSink,
) {
    var predictEndOfEpoch = true

    // One reusable byte buffer + view per kind of block an epoch can hold.
    private val rangesSlots = Array(MeasLimits.ANTENNAS) { BlockSlot() }
    private val dopplerSlots = Array(MeasLimits.ANTENNAS) { BlockSlot() }
    private val cn0HiResSlots = Array(MeasLimits.ANTENNAS) { BlockSlot() }
    private val ppSlots = Array(MeasLimits.ANTENNAS) { BlockSlot() }
    private val mpSlots = Array(MeasLimits.ANTENNAS) { BlockSlot() }
    private val measEpochSlot = BlockSlot()
    private val measExtraSlot = BlockSlot()
    private val measFullRangeSlot = BlockSlot()
    private val sisMeasSlot = BlockSlot()

    // Which blocks are present in this epoch (null = absent), as the decoders expect them.
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
    private val legacy = LegacyMeasDecoder()
    private val lockTimes = LockTimes()
    private val epoch = MeasEpoch()

    /** Processes one block; calls the sink for each measurement epoch it completes. */
    fun add(block: SbfBlock) {
        val number = block.number
        val tow = block.tow

        // A block from another epoch, or a non-measurement block, closes the collected epoch.
        if ((tow != currentTow || !BlockNumber.isGnssMeasBlock(number) || number == BlockNumber.END_OF_MEAS) &&
            blocksSeenAtThisEpoch != 0
        ) {
            // Fix vs C (kept from the original port): the C code decodes this epoch into the
            // caller's buffer without updating lock times, and a second epoch completed in the
            // same call overwrites it. Here it is delivered like any other epoch.
            if (processEpoch()) {
                lockTimes.update(epoch)
                sink.onEpoch(epoch)
            }
            blocksSeenAtLastEpoch = blocksSeenAtThisEpoch
            blocksSeenAtThisEpoch = 0
        }

        currentTow = tow
        store(block, number)

        var endOfEpoch = false
        if (lookahead != null) {
            if (blocksSeenAtThisEpoch != 0) {
                val next = lookahead.peekNext()
                endOfEpoch = next == null ||
                    next.tow != tow ||
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
            sink.onEpoch(epoch)
        }
    }

    /** `sbfread_FlushMeasEpoch`: decodes whatever has been collected so far. */
    fun flush() {
        if (processEpoch()) {
            lockTimes.update(epoch)
            sink.onEpoch(epoch)
        }
        blocksSeenAtLastEpoch = blocksSeenAtThisEpoch
        blocksSeenAtThisEpoch = 0
    }

    private fun store(block: SbfBlock, number: Int) {
        when (number) {
            BlockNumber.MEAS3_RANGES -> storeMeas3(rangesSlots, ranges, block, block.u8(18) and 7, SEEN_MEAS3_RANGES)
            BlockNumber.MEAS3_DOPPLER -> storeMeas3(dopplerSlots, doppler, block, block.u16(14) and 7, SEEN_MEAS3_DOPPLER)
            BlockNumber.MEAS3_CN0_HIRES -> storeMeas3(cn0HiResSlots, cn0HiRes, block, block.u16(14) and 7, SEEN_MEAS3_CN0_HIRES)
            BlockNumber.MEAS3_PP -> storeMeas3(ppSlots, pp, block, block.u16(14) and 7, SEEN_MEAS3_PP)
            BlockNumber.MEAS3_MP -> storeMeas3(mpSlots, mp, block, block.u16(14) and 7, SEEN_MEAS3_MP)
            BlockNumber.MEAS_EPOCH_2, BlockNumber.GEN_MEAS_EPOCH_1 -> {
                measEpochBlock = measEpochSlot.store(block)
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_EPOCH
            }
            BlockNumber.MEAS_EXTRA -> {
                measExtraBlock = measExtraSlot.store(block)
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_EXTRA
            }
            BlockNumber.MEAS_FULL_RANGE -> {
                measFullRangeBlock = measFullRangeSlot.store(block)
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_MEAS_FULL_RANGE
            }
            BlockNumber.SIS_MEAS -> {
                sisMeasBlock = sisMeasSlot.store(block)
                blocksSeenAtThisEpoch = blocksSeenAtThisEpoch or SEEN_SIS_MEAS
            }
        }
    }

    private fun storeMeas3(slots: Array<BlockSlot>, present: Array<SbfBlock?>, block: SbfBlock, ant: Int, seenBit: Int) {
        if (ant < MeasLimits.ANTENNAS) {
            present[ant] = slots[ant].store(block)
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
            if (sis != null) legacy.decodeSisMeas(sis, epoch)
            else legacy.decodeMeasEpoch(measEpochBlock!!, epoch)

            measExtraBlock?.let { legacy.decodeMeasExtra(it, epoch) }
            measFullRangeBlock?.let { legacy.decodeMeasFullRange(it, epoch) }

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
        // Fix vs C (kept from the original port): sbfread_ProcessEpoch never clears the SISMeas
        // buffer, so once a SISMeas has been seen, that stale block is re-decoded (and preferred
        // over MeasEpoch) in later epochs.
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
 * A reusable copy of one block. The buffer grows to the largest block stored in it (rounded
 * up to a power of two, at most 64 KiB) and is then reused for every later epoch.
 */
internal class BlockSlot {
    private var data = ByteArray(0)
    private val view = SbfBlock()

    fun store(block: SbfBlock): SbfBlock {
        val len = block.length
        if (data.size < len) data = ByteArray(capacityFor(len))
        block.copyTo(data, 0)
        view.bind(data, 0, len)
        return view
    }

    private fun capacityFor(len: Int): Int {
        var c = MIN_CAPACITY
        while (c < len) c = c shl 1
        return c
    }

    private companion object {
        const val MIN_CAPACITY = 512
    }
}

/**
 * `sbfread_UpdateLockTimes`: derives the lock time since the last cycle slip
 * ([MeasEpoch.lockTimeMs]) from the PLL timer and the lock counter.
 *
 * Fix vs C (kept from the original port): the history is keyed by satellite (internal PRN),
 * antenna and slot instead of by logical channel number.
 */
internal class LockTimes {
    private val lockCountAtLastEpoch = IntArray(Svid.COUNT * SLOTS)
    private val pllTimerAtLockCountIncrementS = IntArray(Svid.COUNT * SLOTS)

    fun update(epoch: MeasEpoch) {
        val flags = epoch.sFlags
        val lCycles = epoch.sLCycles
        val pll = epoch.sPllTimerMs
        val lockCount = epoch.sLockCount
        val lockTime = epoch.sLockTimeMs
        for (ch in 0 until epoch.channelCount) {
            val prn = epoch.chPrn[ch]
            if (prn !in 0 until Svid.COUNT) continue
            val firstSet = ch * MeasEpoch.SETS_PER_CHANNEL
            val firstKey = prn * SLOTS
            // Sets of a channel and keys of a PRN share the [antenna][slot] order.
            for (i in 0 until SLOTS) {
                val s = firstSet + i
                if (flags[s].toInt() == 0 || lCycles[s] == Dnu.F64) continue
                val k = firstKey + i
                val count = lockCount[s].toInt() and 0xFF
                val pllMs = pll[s].toLong()
                if (lockCountAtLastEpoch[k] != count) {
                    // A lockCount change means a cycle slip or PLL restart.
                    pllTimerAtLockCountIncrementS[k] = ((pllMs / 1000) and 0xFFFF).toInt()
                }
                // uint32 arithmetic, as in C.
                var t = (pllMs - pllTimerAtLockCountIncrementS[k].toLong() * 1000) and 0xFFFFFFFFL
                if (t == 0L) t = 10 // 0 would mean "unknown"
                lockTime[s] = t.toInt()
                lockCountAtLastEpoch[k] = count
            }
        }
    }

    private companion object {
        const val SLOTS = MeasLimits.ANTENNAS * MeasLimits.SIGNALS_PER_SATELLITE
    }
}
