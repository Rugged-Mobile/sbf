// Test-only stand-in, NOT part of the copied sources: in gnss-plot this class is declared in
// GnssLog.kt (line 15), which is not copied because it needs the whole recording model.
// The declaration below is identical to the original.
package com.ruggedmobile.plot.sbf

/** A geodetic position (WGS84 ellipsoid). */
data class GeodeticPoint(val latitudeRad: Double, val longitudeRad: Double, val heightM: Double)
