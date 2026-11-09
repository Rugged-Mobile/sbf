package ruggedmobile.sbf

/**
 * The satellite directions in one ChannelStatus (4013) or SatVisibility (4012) block, as
 * columns: entry `i` is satellite [prn]`[i]` at [azimuthDeg]`[i]` / [elevationDeg]`[i]`.
 *
 * One instance is meant to be reused for every block. [readChannelStatus] and [readSatVisibility]
 * overwrite the previous contents and allocate nothing; the arrays are sized for the largest
 * block (255 entries), so they never grow.
 *
 * Results are identical to the original gnss-plot `SbfRecords.channelStatus` / `satVisibility`:
 * entries with an unknown satellite, azimuth or elevation are skipped, and in ChannelStatus a
 * satellite tracked on several channels (e.g. by two antennas) appears once, at the position of
 * its first channel, with the direction of its last channel and "used" if any channel used it.
 */
class SatDirections {
    /** Number of valid entries. */
    var size: Int = 0
        private set

    /** Internal PRN ([Svid]); the same numbering as [MeasEpoch.prn]. */
    val prn = IntArray(CAPACITY)
    val azimuthDeg = FloatArray(CAPACITY)
    val elevationDeg = FloatArray(CAPACITY)

    /** [YES], [NO] or [UNKNOWN] (SatVisibility does not report PVT usage). */
    val usedInPvt = ByteArray(CAPACITY)

    /** [YES] rising, [NO] setting, or [UNKNOWN]. */
    val rising = ByteArray(CAPACITY)

    /** Position of each PRN in the current block during [readChannelStatus], -1 if absent. */
    private val indexOfPrn = IntArray(Svid.COUNT) { -1 }

    fun clear() {
        size = 0
    }

    /** Reads a ChannelStatus block; returns [size]. A block of another type leaves this empty. */
    fun readChannelStatus(b: SbfBlock): Int {
        size = 0
        if (b.number != BlockNumber.CHANNEL_STATUS) return 0
        val n = b.u8(14)
        val sb1 = b.u8(15)
        val sb2 = b.u8(16)
        if (sb1 == 0) return 0
        var off = 20
        for (i in 0 until n) {
            if (off + sb1 > b.length) break
            val svid = b.u8(off).let { if (it == 0) b.u16(off + 2) else it }
            val azRiseSet = b.u16(off + 4)
            val az = azRiseSet and 0x1FF
            val riseSet = azRiseSet ushr 14
            val el = b.i8(off + 8)
            val n2 = b.u8(off + 9)
            var used = false
            var s = off + sb1
            for (j in 0 until n2) {
                if (sb2 == 0 || s + sb2 > b.length) break
                val pvtStatus = b.u16(s + 4)
                for (k in 0 until 8) if ((pvtStatus ushr (2 * k)) and 3 == 2) used = true
                s += sb2
            }
            off = s
            val p = if (svid in 1..255) Svid.fromSbfSvid(svid) else 0
            if (p == 0 || az == 511 || el == -128) continue
            var at = indexOfPrn[p]
            if (at < 0) {
                at = size++
                indexOfPrn[p] = at
                prn[at] = p
                usedInPvt[at] = NO
            }
            azimuthDeg[at] = az.toFloat()
            elevationDeg[at] = el.toFloat()
            if (used) usedInPvt[at] = YES
            rising[at] = riseSetOf(riseSet)
        }
        for (i in 0 until size) indexOfPrn[prn[i]] = -1
        return size
    }

    /** Reads a SatVisibility block (directions in 0.01 degree); returns [size]. */
    fun readSatVisibility(b: SbfBlock): Int {
        size = 0
        if (b.number != BlockNumber.SAT_VISIBILITY) return 0
        val n = b.u8(14)
        val sb = b.u8(15)
        if (sb == 0) return 0
        for (i in 0 until n) {
            val off = 16 + i * sb
            if (off + sb > b.length) break
            val p = Svid.fromSbfSvid(b.u8(off))
            val az = b.u16(off + 2)
            val el = b.i16(off + 4)
            if (p == 0 || az == Dnu.U16 || el == Dnu.I16) continue
            val at = size++
            prn[at] = p
            azimuthDeg[at] = az / 100f
            elevationDeg[at] = el / 100f
            usedInPvt[at] = UNKNOWN
            rising[at] = riseSetOf(b.u8(off + 6))
        }
        return size
    }

    private fun riseSetOf(v: Int): Byte = when (v) {
        0 -> NO
        1 -> YES
        else -> UNKNOWN
    }

    companion object {
        /** N is an 8-bit field, so a block holds at most 255 entries. */
        const val CAPACITY = 255

        const val NO: Byte = 0
        const val YES: Byte = 1
        const val UNKNOWN: Byte = -1
    }
}
