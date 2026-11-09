package ruggedmobile.sbf

import kotlin.math.sqrt

/*
 * Field accessors for the position, quality and correction blocks that monitoring apps plot:
 * PVTGeodetic, PVTCartesian, PosCovGeodetic, PosCovCartesian, DOP, AttEuler, BaseStation and
 * DiffCorrIn. Satellite directions (ChannelStatus, SatVisibility) are in SatDirections.kt.
 *
 * Each object reads its fields directly from an [SbfBlock] view, so nothing is decoded ahead of
 * time and nothing is allocated: read only the fields you need. Check [SbfBlock.number] first;
 * the accessors do not, and on a block of another type they return meaningless values.
 *
 * Do-not-use values are mapped as the original gnss-plot decoders mapped them: NaN for
 * floating-point fields, -1 for counts and IDs. Offsets follow the SBF Reference Guide.
 */

/** GPS time helpers. */
object GpsTime {
    /** Seconds per GPS week. */
    const val SECONDS_PER_WEEK = 604_800.0
}

/** Block time in seconds since the GPS epoch (1980-01-06 00:00 GPS), or NaN if the block has no valid time. */
val SbfBlock.gpsTimeS: Double
    get() = if (isTimeValid) wnc * GpsTime.SECONDS_PER_WEEK + tow / 1000.0 else Double.NaN

/** PVT solution types: bits 0-3 of the SBF `Mode` field, as plain `Int` codes. */
object PvtMode {
    const val NO_FIX = 0
    const val STANDALONE = 1
    const val DGNSS = 2
    const val FIXED_LOCATION = 3
    const val RTK_FIXED = 4
    const val RTK_FLOAT = 5
    const val SBAS = 6
    const val MOVING_BASE_RTK_FIXED = 7
    const val MOVING_BASE_RTK_FLOAT = 8
    const val PPP = 10

    /** Any value of bits 0-3 that is not a defined solution type. */
    const val UNKNOWN = -1

    /** All defined codes, from [NO_FIX] to [PPP], then [UNKNOWN]. */
    val CODES: IntArray = intArrayOf(
        NO_FIX, STANDALONE, DGNSS, FIXED_LOCATION, RTK_FIXED, RTK_FLOAT, SBAS,
        MOVING_BASE_RTK_FIXED, MOVING_BASE_RTK_FLOAT, PPP, UNKNOWN,
    )

    /** The solution type in a raw `Mode` field: bits 0-3, or [UNKNOWN] if they hold an undefined value. */
    fun of(modeField: Int): Int = normalize(modeField and 0x0F)

    /** [code] if it is a defined solution type, otherwise [UNKNOWN]. */
    fun normalize(code: Int): Int = when (code) {
        NO_FIX, STANDALONE, DGNSS, FIXED_LOCATION, RTK_FIXED, RTK_FLOAT, SBAS,
        MOVING_BASE_RTK_FIXED, MOVING_BASE_RTK_FLOAT, PPP -> code
        else -> UNKNOWN
    }

    fun label(code: Int): String = when (code) {
        NO_FIX -> "No fix"
        STANDALONE -> "Stand-alone"
        DGNSS -> "DGNSS"
        FIXED_LOCATION -> "Fixed location"
        RTK_FIXED -> "RTK fixed"
        RTK_FLOAT -> "RTK float"
        SBAS -> "SBAS"
        MOVING_BASE_RTK_FIXED -> "Moving-base RTK fixed"
        MOVING_BASE_RTK_FLOAT -> "Moving-base RTK float"
        PPP -> "PPP"
        else -> "Unknown"
    }

    /** Higher is better: plots draw better fixes on top of worse ones. */
    fun qualityRank(code: Int): Int = when (code) {
        STANDALONE -> 1
        SBAS -> 2
        DGNSS -> 3
        PPP -> 4
        RTK_FLOAT, MOVING_BASE_RTK_FLOAT -> 5
        RTK_FIXED, MOVING_BASE_RTK_FIXED -> 6
        FIXED_LOCATION -> 7
        else -> 0
    }
}

/** Values of the SBF `Datum` field (PVTGeodetic, PVTCartesian). */
object Datum {
    const val WGS84 = 0
    const val UNKNOWN = -1

    fun name(code: Int): String = when (code) {
        0 -> "WGS84/ITRS"
        19 -> "Base station datum"
        30 -> "ETRS89"
        31 -> "NAD83(2011)"
        32 -> "NAD83(PA11)"
        33 -> "NAD83(MA11)"
        34 -> "GDA94"
        35 -> "GDA2020"
        36 -> "JGD2011"
        250 -> "User datum 1"
        251 -> "User datum 2"
        UNKNOWN -> "Unknown"
        else -> "Datum $code"
    }
}

/** Names of the DiffCorrIn `Source` and `Mode` fields. */
object CorrectionSource {
    fun name(source: Int): String = when (source) {
        0 -> "COM1"; 1 -> "COM2"; 2 -> "COM3"; 3 -> "COM4"
        4 -> "USB1"; 5 -> "USB2"; 6 -> "IP"; 7 -> "SBF file"
        8 -> "L-band"; 9 -> "NTRIP"; 10 -> "OTG1"; 11 -> "OTG2"
        12 -> "Bluetooth"; 15 -> "UHF"; 16 -> "IPR"; 17 -> "Data call"; 18 -> "IPS"
        19 -> "SSR2OBS"
        else -> "Source $source"
    }

    fun format(mode: Int): String = when (mode) {
        0 -> "RTCM 2"; 1 -> "CMR"; 2 -> "RTCM 3"; 3 -> "RTCMV"; 4 -> "SPARTN"
        else -> "Format $mode"
    }
}

/** PVTGeodetic (4007): position as latitude/longitude/height. */
object PvtGeodetic {
    /** Solution type, see [PvtMode]. */
    fun mode(b: SbfBlock): Int = PvtMode.of(b.u8(14))

    /** Height held constant (2D mode). */
    fun is2D(b: SbfBlock): Boolean = b.u8(14) and 0x80 != 0
    fun error(b: SbfBlock): Int = b.u8(15)
    fun latitudeRad(b: SbfBlock): Double = dnu(b.f64(16))
    fun longitudeRad(b: SbfBlock): Double = dnu(b.f64(24))
    fun heightM(b: SbfBlock): Double = dnu(b.f64(32))
    fun undulationM(b: SbfBlock): Float = dnu(b.f32(40))
    fun vnMps(b: SbfBlock): Float = dnu(b.f32(44))
    fun veMps(b: SbfBlock): Float = dnu(b.f32(48))
    fun vuMps(b: SbfBlock): Float = dnu(b.f32(52))
    fun cogDeg(b: SbfBlock): Float = dnu(b.f32(56))

    /** See [Datum]. */
    fun datum(b: SbfBlock): Int = datumOf(b.u8(73))
    fun nrSv(b: SbfBlock): Int = count(b.u8(74))

    /** Base station ID of the corrections used (DGNSS/RTK), -1 if none. */
    fun referenceId(b: SbfBlock): Int = b.u16(76).let { if (it == Dnu.U16) -1 else it }
    fun meanCorrAgeS(b: SbfBlock): Float = centi(b.u16(78))

    /** Number of base stations used (rev 1+), 0 in rev 0. */
    fun nrBases(b: SbfBlock): Int = if (b.revision >= 1) b.u8(85) else 0

    /** 2DRMS horizontal accuracy (rev 2+), NaN if absent. */
    fun hAccuracyM(b: SbfBlock): Float = if (b.length >= 92) centi(b.u16(90)) else Float.NaN

    /** 2-sigma vertical accuracy (rev 2+), NaN if absent. */
    fun vAccuracyM(b: SbfBlock): Float = if (b.length >= 94) centi(b.u16(92)) else Float.NaN
}

/** PVTCartesian (4006): the same epoch as PVTGeodetic, as ECEF X/Y/Z. */
object PvtCartesian {
    fun mode(b: SbfBlock): Int = PvtMode.of(b.u8(14))
    fun error(b: SbfBlock): Int = b.u8(15)
    fun xM(b: SbfBlock): Double = dnu(b.f64(16))
    fun yM(b: SbfBlock): Double = dnu(b.f64(24))
    fun zM(b: SbfBlock): Double = dnu(b.f64(32))
    fun undulationM(b: SbfBlock): Float = dnu(b.f32(40))
    fun vxMps(b: SbfBlock): Float = dnu(b.f32(44))
    fun vyMps(b: SbfBlock): Float = dnu(b.f32(48))
    fun vzMps(b: SbfBlock): Float = dnu(b.f32(52))
    fun datum(b: SbfBlock): Int = datumOf(b.u8(73))
    fun nrSv(b: SbfBlock): Int = count(b.u8(74))
}

/** PosCovGeodetic (5906): position standard deviations in metres (square roots of the variances). */
object PosCovGeodetic {
    fun mode(b: SbfBlock): Int = PvtMode.of(b.u8(14))
    fun latitudeSigmaM(b: SbfBlock): Float = sigma(b.f32(16))
    fun longitudeSigmaM(b: SbfBlock): Float = sigma(b.f32(20))
    fun heightSigmaM(b: SbfBlock): Float = sigma(b.f32(24))
}

/** PosCovCartesian (5905): ECEF position standard deviations in metres. */
object PosCovCartesian {
    fun mode(b: SbfBlock): Int = PvtMode.of(b.u8(14))
    fun xSigmaM(b: SbfBlock): Float = sigma(b.f32(16))
    fun ySigmaM(b: SbfBlock): Float = sigma(b.f32(20))
    fun zSigmaM(b: SbfBlock): Float = sigma(b.f32(24))
}

/** DOP (4001). DOP values are NaN when unavailable. */
object Dop {
    fun nrSv(b: SbfBlock): Int = b.u8(14)
    fun pdop(b: SbfBlock): Float = dop(b.u16(16))
    fun tdop(b: SbfBlock): Float = dop(b.u16(18))
    fun hdop(b: SbfBlock): Float = dop(b.u16(20))
    fun vdop(b: SbfBlock): Float = dop(b.u16(22))
    fun hplM(b: SbfBlock): Float = dnu(b.f32(24))
    fun vplM(b: SbfBlock): Float = dnu(b.f32(28))

    private fun dop(v: Int): Float = if (v == 0) Float.NaN else v / 100f
}

/** AttEuler (5938): attitude angles, NaN when unavailable. */
object AttEuler {
    fun nrSv(b: SbfBlock): Int = count(b.u8(14))
    fun error(b: SbfBlock): Int = b.u8(15)

    /** 0 = no attitude; see the SBF Reference Guide for the other codes. */
    fun mode(b: SbfBlock): Int = b.u16(16)
    fun headingDeg(b: SbfBlock): Float = dnu(b.f32(20))
    fun pitchDeg(b: SbfBlock): Float = dnu(b.f32(24))
    fun rollDeg(b: SbfBlock): Float = dnu(b.f32(28))
}

/** BaseStation (5949): a base station position received in corrections. */
object BaseStation {
    fun id(b: SbfBlock): Int = b.u16(14)

    /** 0 fixed, 1 moving, 255 unknown. */
    fun type(b: SbfBlock): Int = b.u8(16)

    /** 8 = RTCM 3 message 1005/1006; see the SBF Reference Guide for the others. */
    fun source(b: SbfBlock): Int = b.u8(17)
    fun xM(b: SbfBlock): Double = b.f64(20)
    fun yM(b: SbfBlock): Double = b.f64(28)
    fun zM(b: SbfBlock): Double = b.f64(36)
}

/** DiffCorrIn (5919): one correction message received by the receiver. */
object DiffCorrIn {
    /** 0 RTCM 2, 1 CMR 2, 2 RTCM 3, 3 RTCMV, 4 SPARTN; see [CorrectionSource.format]. */
    fun mode(b: SbfBlock): Int = b.u8(14)

    /** Connection it arrived on; see [CorrectionSource.name]. */
    fun source(b: SbfBlock): Int = b.u8(15)

    /** RTCM 3 message type, or -1. An RTCM 3 message follows as a complete frame: 0xD3, length, payload, CRC. */
    fun rtcm3Type(b: SbfBlock): Int =
        if (b.u8(14) == 2 && b.u8(16) == 0xD3) (b.u8(19) shl 4) or (b.u8(20) ushr 4) else -1
}

private fun dnu(v: Double): Double = if (v < -1e10) Double.NaN else v
private fun dnu(v: Float): Float = if (v < -1e10f) Float.NaN else v
private fun count(v: Int): Int = if (v == Dnu.U8) -1 else v
private fun centi(v: Int): Float = if (v == Dnu.U16) Float.NaN else v / 100f
private fun datumOf(v: Int): Int = if (v == 255) Datum.UNKNOWN else v
private fun sigma(variance: Float): Float = dnu(variance).let { if (it >= 0f) sqrt(it) else Float.NaN }
