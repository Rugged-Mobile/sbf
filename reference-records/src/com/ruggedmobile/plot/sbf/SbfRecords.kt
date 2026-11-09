package com.ruggedmobile.plot.sbf

import androidx.compose.ui.graphics.Color
import com.ruggedmobile.plot.PlotColors
import sbf.BlockNumber
import sbf.SbfBlock
import sbf.Svid
import kotlin.math.sqrt

/** Seconds per GPS week. */
const val SECONDS_PER_WEEK = 604_800.0

/** Block time in seconds since the GPS epoch (1980-01-06 00:00 GPS), or NaN if not set. */
val SbfBlock.gpsTimeS: Double
    get() = if (isTimeValid) wnc * SECONDS_PER_WEEK + tow / 1000.0 else Double.NaN

/** Block numbers decoded here that `sbf.BlockNumber` does not define. */
object PlotBlockNumber {
    const val SAT_VISIBILITY = 4012
    const val CHANNEL_STATUS = 4013
    const val POS_COV_GEODETIC = 5906
    const val DIFF_CORR_IN = 5919
    const val BASE_STATION = 5949
}

/** Direction of one satellite as seen from the receiver. */
data class SatDirection(
    /** Septentrio internal satellite number (see [sbf.Svid]); the same numbering as `MeasChannel.prn`. */
    val prn: Int,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    /** Used in the PVT solution on any signal; null if the block does not say (SatVisibility). */
    val usedInPvt: Boolean? = null,
    /** Rising (true), setting (false) or unknown (null). */
    val rising: Boolean? = null,
)

/** PVT solution type (bits 0-3 of the SBF `Mode` field). */
enum class PvtMode(val code: Int, val label: String) {
    NO_FIX(0, "No fix"),
    STANDALONE(1, "Stand-alone"),
    DGNSS(2, "DGNSS"),
    FIXED_LOCATION(3, "Fixed location"),
    RTK_FIXED(4, "RTK fixed"),
    RTK_FLOAT(5, "RTK float"),
    SBAS(6, "SBAS"),
    MOVING_BASE_RTK_FIXED(7, "Moving-base RTK fixed"),
    MOVING_BASE_RTK_FLOAT(8, "Moving-base RTK float"),
    PPP(10, "PPP"),
    UNKNOWN(-1, "Unknown");

    /** Status colour by solution quality: fixed = good, float/PPP = warning, DGNSS/SBAS = serious, stand-alone = critical. */
    fun color(colors: PlotColors): Color = when (this) {
        RTK_FIXED, MOVING_BASE_RTK_FIXED, FIXED_LOCATION -> colors.good
        RTK_FLOAT, MOVING_BASE_RTK_FLOAT, PPP -> colors.warning
        DGNSS, SBAS -> colors.serious
        STANDALONE -> colors.critical
        NO_FIX, UNKNOWN -> colors.textSecondary
    }

    /** Higher is better; plots draw better fixes on top of worse ones. */
    val qualityRank: Int
        get() = when (this) {
            NO_FIX, UNKNOWN -> 0
            STANDALONE -> 1
            SBAS -> 2
            DGNSS -> 3
            PPP -> 4
            RTK_FLOAT, MOVING_BASE_RTK_FLOAT -> 5
            RTK_FIXED, MOVING_BASE_RTK_FIXED -> 6
            FIXED_LOCATION -> 7
        }

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): PvtMode = byCode[code] ?: UNKNOWN
    }
}

/** Decoded PVTGeodetic (4007). Unavailable values are NaN, unavailable counts -1. */
data class PvtGeodetic(
    val timeS: Double,
    val mode: PvtMode,
    /** Height held constant (2D mode). */
    val is2D: Boolean,
    val error: Int,
    val latitudeRad: Double,
    val longitudeRad: Double,
    val heightM: Double,
    val undulationM: Float,
    val vnMps: Float,
    val veMps: Float,
    val vuMps: Float,
    val cogDeg: Float,
    val nrSv: Int,
    val meanCorrAgeS: Float,
    /** 2DRMS horizontal accuracy (rev 2+). */
    val hAccuracyM: Float,
    /** 2-sigma vertical accuracy (rev 2+). */
    val vAccuracyM: Float,
    /** Base station ID of the corrections used (DGNSS/RTK), -1 if none. */
    val referenceId: Int = -1,
    /** Number of base stations used (rev 1+). */
    val nrBases: Int = 0,
    /** Datum of the position, see [Datums]; -1 if unknown. */
    val datum: Int = Datums.UNKNOWN,
)

/** Decoded PVTCartesian (4006): the same epoch as PVTGeodetic, as ECEF X/Y/Z. Unavailable values are NaN. */
data class PvtCartesian(
    val timeS: Double,
    val mode: PvtMode,
    val error: Int,
    val xM: Double,
    val yM: Double,
    val zM: Double,
    val undulationM: Float,
    val vxMps: Float,
    val vyMps: Float,
    val vzMps: Float,
    val nrSv: Int,
    /** See [Datums]; -1 if unknown. */
    val datum: Int,
)

/** Names of the SBF `Datum` field values (PVTGeodetic, PVTCartesian). */
object Datums {
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

/** A base station position received in corrections (BaseStation, 5949). */
data class BaseStationInfo(
    val timeS: Double,
    val id: Int,
    /** 0 fixed, 1 moving, 255 unknown. */
    val type: Int,
    /** 8 = RTCM 3 message 1005/1006; see the SBF reference for the others. */
    val source: Int,
    val xM: Double,
    val yM: Double,
    val zM: Double,
) {
    /** The position as latitude/longitude/height (WGS84 ellipsoid). */
    val geodetic: GeodeticPoint get() = Wgs84.geodetic(xM, yM, zM)
}

/** One correction message received by the receiver (DiffCorrIn, 5919). */
data class CorrectionMessage(
    val timeS: Double,
    /** 0 RTCM 2, 1 CMR 2, 2 RTCM 3, 3 RTCMV, 4 SPARTN. */
    val mode: Int,
    /** Connection it arrived on; see [CorrectionSources.name]. */
    val source: Int,
    /** RTCM 3 message type, or -1. */
    val rtcm3Type: Int,
)

/** Names of DiffCorrIn sources. */
object CorrectionSources {
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

/** Decoded DOP (4001). DOPs are NaN when unavailable. */
data class Dop(
    val timeS: Double,
    val nrSv: Int,
    val pdop: Float,
    val tdop: Float,
    val hdop: Float,
    val vdop: Float,
    val hplM: Float,
    val vplM: Float,
)

/** Decoded AttEuler (5938). Angles are NaN when unavailable. */
data class Attitude(
    val timeS: Double,
    val nrSv: Int,
    val error: Int,
    /** 0 = no attitude; see the SBF reference for the other codes. */
    val mode: Int,
    val headingDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
)

/** Standard deviations from PosCovGeodetic (5906). */
data class PositionSigma(
    val timeS: Double,
    val mode: PvtMode,
    val latitudeM: Float,
    val longitudeM: Float,
    val heightM: Float,
)

/** Standard deviations from PosCovCartesian (5905). */
data class CartesianSigma(
    val timeS: Double,
    val mode: PvtMode,
    val xM: Float,
    val yM: Float,
    val zM: Float,
)

/** Decoders for the SBF blocks the plots use. Each returns null (or empty) for a block of another type. */
object SbfRecords {

    /** ChannelStatus (4013): azimuth/elevation and PVT usage of every tracked satellite. */
    fun channelStatus(b: SbfBlock): List<SatDirection> {
        if (b.number != PlotBlockNumber.CHANNEL_STATUS) return emptyList()
        val n = b.u8(14)
        val sb1 = b.u8(15)
        val sb2 = b.u8(16)
        if (sb1 == 0) return emptyList()
        val byPrn = LinkedHashMap<Int, SatDirection>()
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
            val prn = if (svid in 1..255) Svid.fromSbfSvid(svid) else 0
            if (prn == 0 || az == 511 || el == -128) continue
            val previous = byPrn[prn]
            byPrn[prn] = SatDirection(
                prn, az.toFloat(), el.toFloat(),
                usedInPvt = used || previous?.usedInPvt == true,
                rising = when (riseSet) { 0 -> false; 1 -> true; else -> null },
            )
        }
        return byPrn.values.toList()
    }

    /** SatVisibility (4012): azimuth/elevation (0.01 degree) of satellites above the horizon. */
    fun satVisibility(b: SbfBlock): List<SatDirection> {
        if (b.number != PlotBlockNumber.SAT_VISIBILITY) return emptyList()
        val n = b.u8(14)
        val sb = b.u8(15)
        if (sb == 0) return emptyList()
        val out = ArrayList<SatDirection>(n)
        for (i in 0 until n) {
            val off = 16 + i * sb
            if (off + sb > b.length) break
            val prn = Svid.fromSbfSvid(b.u8(off))
            val az = b.u16(off + 2)
            val el = b.i16(off + 4)
            if (prn == 0 || az == 65535 || el == -32768) continue
            val riseSet = b.u8(off + 6)
            out += SatDirection(
                prn, az / 100f, el / 100f,
                usedInPvt = null,
                rising = when (riseSet) { 0 -> false; 1 -> true; else -> null },
            )
        }
        return out
    }

    fun pvtGeodetic(b: SbfBlock): PvtGeodetic? {
        if (b.number != BlockNumber.PVT_GEODETIC_2) return null
        val modeBits = b.u8(14)
        return PvtGeodetic(
            timeS = b.gpsTimeS,
            mode = PvtMode.fromCode(modeBits and 0x0F),
            is2D = modeBits and 0x80 != 0,
            error = b.u8(15),
            latitudeRad = dnu(b.f64(16)),
            longitudeRad = dnu(b.f64(24)),
            heightM = dnu(b.f64(32)),
            undulationM = dnu(b.f32(40)),
            vnMps = dnu(b.f32(44)),
            veMps = dnu(b.f32(48)),
            vuMps = dnu(b.f32(52)),
            cogDeg = dnu(b.f32(56)),
            nrSv = b.u8(74).let { if (it == 255) -1 else it },
            meanCorrAgeS = b.u16(78).let { if (it == 65535) Float.NaN else it / 100f },
            hAccuracyM = if (b.length >= 92) b.u16(90).let { if (it == 65535) Float.NaN else it / 100f } else Float.NaN,
            vAccuracyM = if (b.length >= 94) b.u16(92).let { if (it == 65535) Float.NaN else it / 100f } else Float.NaN,
            referenceId = b.u16(76).let { if (it == 65535) -1 else it },
            nrBases = if (b.revision >= 1) b.u8(85) else 0,
            datum = datum(b.u8(73)),
        )
    }

    fun pvtCartesian(b: SbfBlock): PvtCartesian? {
        if (b.number != BlockNumber.PVT_CARTESIAN_2) return null
        return PvtCartesian(
            timeS = b.gpsTimeS,
            mode = PvtMode.fromCode(b.u8(14) and 0x0F),
            error = b.u8(15),
            xM = dnu(b.f64(16)),
            yM = dnu(b.f64(24)),
            zM = dnu(b.f64(32)),
            undulationM = dnu(b.f32(40)),
            vxMps = dnu(b.f32(44)),
            vyMps = dnu(b.f32(48)),
            vzMps = dnu(b.f32(52)),
            nrSv = b.u8(74).let { if (it == 255) -1 else it },
            datum = datum(b.u8(73)),
        )
    }

    fun baseStation(b: SbfBlock): BaseStationInfo? {
        if (b.number != PlotBlockNumber.BASE_STATION) return null
        return BaseStationInfo(
            timeS = b.gpsTimeS,
            id = b.u16(14),
            type = b.u8(16),
            source = b.u8(17),
            xM = b.f64(20), yM = b.f64(28), zM = b.f64(36),
        )
    }

    fun diffCorrIn(b: SbfBlock): CorrectionMessage? {
        if (b.number != PlotBlockNumber.DIFF_CORR_IN) return null
        val mode = b.u8(14)
        // RTCM 3: the message follows as a complete frame (0xD3, length, payload, CRC).
        val type = if (mode == 2 && b.u8(16) == 0xD3) (b.u8(19) shl 4) or (b.u8(20) ushr 4) else -1
        return CorrectionMessage(b.gpsTimeS, mode, b.u8(15), type)
    }

    fun dop(b: SbfBlock): Dop? {
        if (b.number != BlockNumber.DOP_2) return null
        fun d(off: Int) = b.u16(off).let { if (it == 0) Float.NaN else it / 100f }
        return Dop(
            timeS = b.gpsTimeS,
            nrSv = b.u8(14),
            pdop = d(16), tdop = d(18), hdop = d(20), vdop = d(22),
            hplM = dnu(b.f32(24)), vplM = dnu(b.f32(28)),
        )
    }

    fun attEuler(b: SbfBlock): Attitude? {
        if (b.number != BlockNumber.ATT_EULER) return null
        return Attitude(
            timeS = b.gpsTimeS,
            nrSv = b.u8(14).let { if (it == 255) -1 else it },
            error = b.u8(15),
            mode = b.u16(16),
            headingDeg = dnu(b.f32(20)),
            pitchDeg = dnu(b.f32(24)),
            rollDeg = dnu(b.f32(28)),
        )
    }

    fun posCovGeodetic(b: SbfBlock): PositionSigma? {
        if (b.number != PlotBlockNumber.POS_COV_GEODETIC) return null
        fun sigma(off: Int) = dnu(b.f32(off)).let { if (it >= 0f) sqrt(it) else Float.NaN }
        return PositionSigma(
            timeS = b.gpsTimeS,
            mode = PvtMode.fromCode(b.u8(14) and 0x0F),
            latitudeM = sigma(16),
            longitudeM = sigma(20),
            heightM = sigma(24),
        )
    }

    fun posCovCartesian(b: SbfBlock): CartesianSigma? {
        if (b.number != BlockNumber.POS_COV_CARTESIAN) return null
        fun sigma(off: Int) = dnu(b.f32(off)).let { if (it >= 0f) sqrt(it) else Float.NaN }
        return CartesianSigma(
            timeS = b.gpsTimeS,
            mode = PvtMode.fromCode(b.u8(14) and 0x0F),
            xM = sigma(16),
            yM = sigma(20),
            zM = sigma(24),
        )
    }

    private fun datum(v: Int) = if (v == 255) Datums.UNKNOWN else v
    private fun dnu(v: Double) = if (v < -1e10) Double.NaN else v
    private fun dnu(v: Float) = if (v < -1e10f) Float.NaN else v
}
