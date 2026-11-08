package sbf

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
