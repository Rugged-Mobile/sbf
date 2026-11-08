package sbf

/**
 * Time-window and decimation filter applied to every block (`IncludeThisEpoch` in sbfread.c).
 *
 * @param firstEpochMs keep blocks at or after this time. A value below 86 400 000 is a GPS
 *   time of day, compared on every day; otherwise it is ms since the GPS epoch
 *   (1980-01-06). [DONT_CARE_FIRST] disables the check.
 * @param lastEpochMs same, for the end of the window. [DONT_CARE_LAST] disables it.
 * @param intervalMs keep only blocks with `TOW % intervalMs == 0`; must be > 0.
 * @param acceptInvalidTime keep blocks whose TOW or WNc is do-not-use.
 */
class EpochFilter(
    val firstEpochMs: Long = DONT_CARE_FIRST,
    val lastEpochMs: Long = DONT_CARE_LAST,
    val intervalMs: Int = 1,
    val acceptInvalidTime: Boolean = true,
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
    }

    fun includes(block: SbfBlock): Boolean {
        var ret = acceptInvalidTime || block.isTimeValid
        // C casts TOW to int, so a do-not-use TOW becomes -1.
        val towMs = block.tow.toInt()
        val gpsTimeMs = block.wnc.toLong() * (86400L * 7L * 1000L) + towMs

        if (towMs % intervalMs != 0) ret = false

        if (firstEpochMs != DONT_CARE_FIRST) {
            if (firstEpochMs < MS_PER_DAY) {
                if (towMs % MS_PER_DAY < firstEpochMs) ret = false
            } else if (gpsTimeMs < firstEpochMs) {
                ret = false
            }
        }

        if (lastEpochMs != DONT_CARE_LAST) {
            if (lastEpochMs < MS_PER_DAY) {
                if (towMs % MS_PER_DAY > lastEpochMs) ret = false
            } else if (gpsTimeMs > lastEpochMs) {
                ret = false
            }
        }

        return ret
    }

    companion object {
        const val DONT_CARE_FIRST = -1L
        const val DONT_CARE_LAST = 1L shl 62
        private const val MS_PER_DAY = 86400000
    }
}
