package com.ruggedmobile.plot.sbf

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 ellipsoid conversions. */
object Wgs84 {
    const val A = 6378137.0
    const val E2 = 6.69437999014e-3

    /** Geodetic (radians, metres) to Earth-centred, Earth-fixed X/Y/Z (metres). */
    fun ecef(latRad: Double, lonRad: Double, heightM: Double): DoubleArray {
        val sl = sin(latRad)
        val n = A / sqrt(1 - E2 * sl * sl)
        return doubleArrayOf(
            (n + heightM) * cos(latRad) * cos(lonRad),
            (n + heightM) * cos(latRad) * sin(lonRad),
            (n * (1 - E2) + heightM) * sl,
        )
    }

    /** ECEF to geodetic (iterative; converges to well below a millimetre in a few steps). */
    fun geodetic(x: Double, y: Double, z: Double): GeodeticPoint {
        val p = hypot(x, y)
        val lon = atan2(y, x)
        var lat = atan2(z, p * (1 - E2))
        var h = 0.0
        repeat(6) {
            val sl = sin(lat)
            val n = A / sqrt(1 - E2 * sl * sl)
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - E2 * n / (n + h)))
        }
        return GeodeticPoint(lat, lon, h)
    }
}
