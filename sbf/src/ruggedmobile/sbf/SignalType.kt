package ruggedmobile.sbf

/** Signal types (`SignalType_t` in sbfsigtypes.h). Kept as Int so unknown values survive decoding. */
object SignalType {
    const val GPS_L1CA = 0
    const val GPS_L1P = 1
    const val GPS_L2P = 2
    const val GPS_L2C = 3
    const val GPS_L5 = 4
    const val GPS_L1C = 5
    const val QZS_L1CA = 6
    const val QZS_L2C = 7
    const val GLO_L1CA = 8
    const val GLO_L1P = 9
    const val GLO_L2P = 10
    const val GLO_L2CA = 11
    const val GLO_L3 = 12
    const val BDS_B1C = 13
    const val BDS_B2A = 14
    const val IRN_L5 = 15
    const val RES16 = 16
    const val GAL_E1BC = 17
    const val GAL_E6BC = 19
    const val GAL_E5A = 20
    const val GAL_E5B = 21
    const val GAL_E5 = 22
    const val MSS = 23
    const val SBS_L1CA = 24
    const val SBS_L5 = 25
    const val QZS_L5 = 26
    const val QZS_L6 = 27
    const val BDS_B1I = 28
    const val BDS_B2I = 29
    const val BDS_B3 = 30
    const val UNUSED = 31
    const val QZS_L1C = 32
    const val QZS_L1S = 33
    const val BDS_B2B = 34
    const val IRN_S1 = 36
    const val IRN_L1 = 37
    const val QZS_L1CB = 38
    const val QZS_L5S = 39

    /** First value past the known signals; also used as "unknown signal". */
    const val LAST = 48
    const val INVALID = 255

    private const val C84 = 299792458.0

    private const val E5_FREQ = 1191.795e6
    private const val E5A_FREQ = 1176.45e6
    private const val E5B_FREQ = 1207.14e6
    private const val E6_FREQ = 1278.75e6
    private const val L1_FREQ = 1575.42e6
    private const val L2_FREQ = 1227.60e6
    private const val L5_FREQ = 1176.45e6
    private const val E2_FREQ = 1561.098e6
    private const val L1_GLO_FREQ = 1602.00e6
    private const val L2_GLO_FREQ = 1246.00e6
    private const val L3_GLO_FREQ = 1202.025e6
    private const val B3_FREQ = 1268.52e6
    private const val S1_FREQ = 2492.028e6

    private const val E5_WAVELENGTH = C84 / E5_FREQ
    private const val E5A_WAVELENGTH = C84 / E5A_FREQ
    private const val E5B_WAVELENGTH = C84 / E5B_FREQ
    private const val E6_WAVELENGTH = C84 / E6_FREQ
    private const val L1_WAVELENGTH = C84 / L1_FREQ
    private const val L2_WAVELENGTH = C84 / L2_FREQ
    private const val L5_WAVELENGTH = C84 / L5_FREQ
    private const val E2_WAVELENGTH = C84 / E2_FREQ
    private const val L3_WAVELENGTH = C84 / L3_GLO_FREQ
    private const val B3_WAVELENGTH = C84 / B3_FREQ
    private const val S1_WAVELENGTH = C84 / S1_FREQ

    private const val GLO_FN_MIN = -7
    private const val GLO_FN_COUNT = 14

    /**
     * `GetWavelength_m` as a `when`, kept as the single definition of the mapping. It is only
     * used to fill the lookup tables below, so the table values are exactly what this returns.
     */
    private fun wavelengthByCase(signal: Int, gloFn: Int): Double = when (signal) {
        GPS_L1CA, GPS_L1P, GPS_L1C, GAL_E1BC, SBS_L1CA, QZS_L1CA, QZS_L1CB, QZS_L1C, QZS_L1S,
        BDS_B1C, IRN_L1 -> L1_WAVELENGTH

        GPS_L2P, GPS_L2C, QZS_L2C -> L2_WAVELENGTH
        GAL_E5 -> E5_WAVELENGTH
        GAL_E5A, RES16 -> E5A_WAVELENGTH
        GAL_E5B, BDS_B2I, BDS_B2B -> E5B_WAVELENGTH
        GAL_E6BC, QZS_L6 -> E6_WAVELENGTH
        GPS_L5, SBS_L5, QZS_L5, QZS_L5S, IRN_L5, BDS_B2A -> L5_WAVELENGTH
        BDS_B1I -> E2_WAVELENGTH
        BDS_B3 -> B3_WAVELENGTH
        GLO_L1CA, GLO_L1P -> if (gloFn in -7..6) C84 / (L1_GLO_FREQ + gloFn * 562500) else Dnu.F64
        GLO_L2CA, GLO_L2P -> if (gloFn in -7..6) C84 / (L2_GLO_FREQ + gloFn * 437500) else Dnu.F64
        GLO_L3 -> L3_WAVELENGTH
        IRN_S1 -> S1_WAVELENGTH
        // L-band: sbfread_meas.c returns the L1 wavelength so MeasEpoch sub-blocks decode.
        MSS -> L1_WAVELENGTH
        else -> Dnu.F64
    }

    /** Wavelength of every non-FDMA signal value 0..255 (GLONASS FDMA entries unused). */
    private val WAVELENGTH = DoubleArray(256) { wavelengthByCase(it, 0) }

    /** GLONASS FDMA wavelengths: `[L1 group | L2 group][fn + 7]`. */
    private val GLO_WAVELENGTH = DoubleArray(2 * GLO_FN_COUNT) {
        val fn = it % GLO_FN_COUNT + GLO_FN_MIN
        wavelengthByCase(if (it < GLO_FN_COUNT) GLO_L1CA else GLO_L2CA, fn)
    }

    /**
     * Carrier wavelength in metres (`GetWavelength_m`), or [Dnu.F64] if unknown.
     * [gloFn] is the GLONASS frequency number (-7..6), ignored for other signals.
     *
     * Table lookup instead of the original `when`: same values, bit for bit.
     */
    fun wavelength(signal: Int, gloFn: Int): Double {
        // GLO_L1CA 8, GLO_L1P 9 -> L1 group; GLO_L2P 10, GLO_L2CA 11 -> L2 group.
        val glo = signal - GLO_L1CA
        if (glo in 0..3) {
            val fn = gloFn - GLO_FN_MIN
            return if (fn in 0 until GLO_FN_COUNT) GLO_WAVELENGTH[(glo ushr 1) * GLO_FN_COUNT + fn] else Dnu.F64
        }
        return if (signal in 0..255) WAVELENGTH[signal] else Dnu.F64
    }

    /** GPS P(Y) signals have no +10 dB-Hz offset in their C/N0 encoding. */
    internal fun isGpsPCode(signal: Int): Boolean = signal == GPS_L1P || signal == GPS_L2P

    /** GLONASS FDMA signals (frequency number carried by the sub-block). */
    internal fun isGloFdma(signal: Int): Boolean = signal in GLO_L1CA..GLO_L2CA

    /**
     * `DefaultSigIdxInConstellation`: measurement-set slot of each signal in the legacy
     * (MeasEpoch/SISMeas) path, -1 if the signal has no slot.
     */
    internal val DEFAULT_SLOT = intArrayOf(
        0, 3, 4, 1, 2, 5, 0, 1,
        0, 2, 3, 1, 4, 3, 4, 0,
        5, 0, -1, 3, 1, 2, 4, 0,
        0, 1, 2, 3, 0, 1, 2, -1,
        4, 5, 5, -1, 2, 1, 0, 6,
        0, 1, 2, 3, 4, 5, 0, 1,
    )
}
