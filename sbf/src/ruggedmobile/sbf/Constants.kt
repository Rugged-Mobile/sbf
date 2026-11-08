package ruggedmobile.sbf

/** Size limits of an SBF block, in bytes (sbfdef.h). */
object SbfLimits {
    const val HEADER_SIZE = 8

    /** Smallest `Length` accepted: `sizeof(HeaderAndTimeBlock_t)`, i.e. 14 bytes padded to 16. */
    const val MIN_BLOCK_LENGTH = 16

    const val MAX_BLOCK_LENGTH = 65532
}

/** "Do not use" values (sbfdef.h `*_NOTVALID`). */
object Dnu {
    const val U8 = 0xFF
    const val I8 = -128
    const val U16 = 0xFFFF
    const val I16 = -32768
    const val U32 = 0xFFFFFFFFL
    const val I32 = Int.MIN_VALUE
    const val F32 = -2e10f
    const val F64 = -2e10
}

/** Block numbers (bits 0-12 of the block ID) used by the decoder and by sbf2asc. */
object BlockNumber {
    const val MEAS_EXTRA = 4000
    const val MEAS_EPOCH_2 = 4027
    const val GEN_MEAS_EPOCH_1 = 5944
    const val MEAS_FULL_RANGE = 4098
    const val MEAS3_RANGES = 4109
    const val MEAS3_CN0_HIRES = 4110
    const val MEAS3_DOPPLER = 4111
    const val MEAS3_PP = 4112
    const val MEAS3_MP = 4113
    const val SIS_MEAS = 4115
    const val END_OF_MEAS = 5922

    const val PVT_CARTESIAN_1 = 5903
    const val PVT_CARTESIAN_2 = 4006
    const val PVT_GEODETIC_1 = 5904
    const val PVT_GEODETIC_2 = 4007
    const val POS_COV_CARTESIAN = 5905
    const val DOP_1 = 5909
    const val DOP_2 = 4001
    const val ATT_EULER = 5938
    const val ATT_COV_EULER = 5939
    const val EXT_EVENT = 5924
    const val RECEIVER_STATUS_1 = 5913
    const val RECEIVER_STATUS_2 = 4014
    const val BASE_STATION = 5949
    const val BASE_LINE = 5950
    const val BASE_LINK = 5948
    const val GPS_ALM = 5892
    const val AUX_ANT_POSITIONS = 5942
    const val EXT_SENSOR_MEAS = 4050
    const val INS_NAV_GEOD = 4226

    /** `sbfread_IsGNSSMeasBlock`: blocks that take part in measurement epoch assembly. */
    fun isGnssMeasBlock(number: Int): Boolean = when (number) {
        MEAS3_RANGES, MEAS3_DOPPLER, MEAS3_CN0_HIRES, MEAS3_PP, MEAS3_MP,
        GEN_MEAS_EPOCH_1, MEAS_EPOCH_2, SIS_MEAS, MEAS_EXTRA, MEAS_FULL_RANGE,
        END_OF_MEAS -> true
        else -> false
    }
}

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

    /** Number of internal PRN values (0 .. [LBAND_UNKNOWN]); size of PRN-indexed tables. */
    const val COUNT = LBAND_UNKNOWN + 1

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

/** Array sizes of the decoded model (measepochconfig.h). */
object MeasLimits {
    const val LOGICAL_CHANNELS = 100
    const val ANTENNAS = 3
    const val SIGNALS_PER_SATELLITE = 8
}

/** Bits of [MeasEpoch.flags] (`MEASFLAG_*` in measepoch.h). */
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

/** Values of [MeasEpoch.rxTowStatus]; same order as the original `RxTowStatus` enum. */
object RxTowStatus {
    const val INVALID = 0
    const val NOT_SET = 1
    const val COARSE = 2
    const val PRECISE = 3
}

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
