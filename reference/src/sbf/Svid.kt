package sbf

/**
 * Septentrio internal satellite numbering (sviddef.h). Each constellation gets a
 * contiguous range; this is not the SBF `SVID` numbering used inside blocks.
 */
object Svid {
    const val GPS_MIN = 1
    const val GPS_MAX = 39
    const val GAL_MIN = 64
    const val GAL_MAX = 99
    const val QZS_MIN = 104
    const val QZS_MAX = 113
    const val SBAS_MIN = 120
    const val SBAS_MAX = 158
    const val BDS_MIN = 159
    const val BDS_MAX = 221
    const val GLO_MIN = 222
    const val GLO_MAX_KNOWN = 251

    /** GLONASS satellite whose slot number is not known yet. */
    const val GLO_UNKNOWN = 252
    const val IRN_MIN = 256
    const val IRN_MAX = 269
    const val LBAND_MIN = 270
    const val LBAND_MAX = 281
    const val LBAND_UNKNOWN = 282

    fun isGps(prn: Int) = prn in GPS_MIN..GPS_MAX
    fun isGlonass(prn: Int) = prn in GLO_MIN..GLO_UNKNOWN
    fun isGalileo(prn: Int) = prn in GAL_MIN..GAL_MAX
    fun isBeiDou(prn: Int) = prn in BDS_MIN..BDS_MAX
    fun isSbas(prn: Int) = prn in SBAS_MIN..SBAS_MAX
    fun isQzss(prn: Int) = prn in QZS_MIN..QZS_MAX
    fun isNavIC(prn: Int) = prn in IRN_MIN..IRN_MAX
    fun isLBand(prn: Int) = prn in LBAND_MIN..LBAND_UNKNOWN

    /** `ConvertMeasEpochSVID`: SBF SVID (MeasEpoch sub-blocks) to internal PRN, 0 if unknown. */
    fun fromSbfSvid(svid: Int): Int = when (svid) {
        in 1..37 -> svid + (GPS_MIN - 1)
        in 38..61 -> svid - 37 + (GLO_MIN - 1)
        62 -> GLO_UNKNOWN
        in 63..68 -> svid - 38 + (GLO_MIN - 1)
        in 71..106 -> svid - 70 + (GAL_MIN - 1)
        in 107..119 -> svid - 106 + (LBAND_MIN - 1)
        in 120..140 -> svid - 119 + (SBAS_MIN - 1)
        in 141..180 -> svid - 140 + (BDS_MIN - 1)
        in 181..190 -> svid - 180 + (QZS_MIN - 1)
        in 191..197 -> svid - 190 + (IRN_MIN - 1)
        in 198..215 -> svid - 57 - 119 + (SBAS_MIN - 1)
        in 216..222 -> svid - 208 + (IRN_MIN - 1)
        in 223..245 -> svid - 182 + (BDS_MIN - 1)
        else -> 0
    }
}
