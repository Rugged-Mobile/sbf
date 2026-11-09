package ruggedmobile.sbf

import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PvtBlocksTest {
    private fun view(raw: ByteArray) = SbfBlock().wrap(raw)

    /** PVTGeodetic rev 2: RTK fixed, 2D flag set, 21 satellites, base 1234, 2 bases, accuracies present. */
    private fun pvtGeodetic(nrSv: Int = 21, hAcc: Int = 123) = SbfEncoder.block(BlockNumber.PVT_GEODETIC_2, revision = 2, tow = 345_600_500, wnc = 2390) {
        u8(0x80 or PvtMode.RTK_FIXED); u8(0) // Mode, Error
        f64(-0.4528); f64(0.4918); f64(1612.25) // Lat, Lon, Height
        f32(27.5f); f32(0.01f); f32(-0.02f); f32(Dnu.F32); f32(Dnu.F32) // Undulation, Vn, Ve, Vu, COG
        f64(0.001); f32(1.5f) // RxClkBias, RxClkDrift
        u8(0); u8(0); u8(nrSv); u8(0) // TimeSystem, Datum, NrSV, WACorrInfo
        u16(1234); u16(150) // ReferenceID, MeanCorrAge
        u32(0); u8(0); u8(2) // SignalInfo, AlertFlag, NrBases
        u16(0); u16(0) // PPPInfo, Latency
        u16(hAcc); u16(Dnu.U16) // HAccuracy, VAccuracy
        u8(0); u8(0) // Misc, padding
    }

    @Test
    fun pvtGeodeticFields() {
        val b = view(pvtGeodetic())
        assertEquals(2390 * GpsTime.SECONDS_PER_WEEK + 345_600.5, b.gpsTimeS)
        assertEquals(PvtMode.RTK_FIXED, PvtGeodetic.mode(b))
        assertTrue(PvtGeodetic.is2D(b))
        assertEquals(-0.4528, PvtGeodetic.latitudeRad(b))
        assertEquals(1612.25, PvtGeodetic.heightM(b))
        assertEquals(27.5f, PvtGeodetic.undulationM(b))
        assertTrue(PvtGeodetic.vuMps(b).isNaN())
        assertTrue(PvtGeodetic.cogDeg(b).isNaN())
        assertEquals(Datum.WGS84, PvtGeodetic.datum(b))
        assertEquals(21, PvtGeodetic.nrSv(b))
        assertEquals(1234, PvtGeodetic.referenceId(b))
        assertEquals(1.5f, PvtGeodetic.meanCorrAgeS(b))
        assertEquals(2, PvtGeodetic.nrBases(b))
        assertEquals(1.23f, PvtGeodetic.hAccuracyM(b))
        assertTrue(PvtGeodetic.vAccuracyM(b).isNaN())
    }

    @Test
    fun unavailableCountsAndTime() {
        val b = view(pvtGeodetic(nrSv = 255))
        assertEquals(-1, PvtGeodetic.nrSv(b))
        val noTime = view(SbfEncoder.block(BlockNumber.PVT_GEODETIC_2, tow = Dnu.U32, wnc = Dnu.U16) { repeat(72) { u8(0) } })
        assertTrue(noTime.gpsTimeS.isNaN())
        // A short rev 0 block has no NrBases and no accuracies.
        assertEquals(0, PvtGeodetic.nrBases(noTime))
        assertTrue(PvtGeodetic.hAccuracyM(noTime).isNaN())
    }

    @Test
    fun pvtModeCodes() {
        assertEquals(PvtMode.PPP, PvtMode.of(0xA0 or 10))
        assertEquals(PvtMode.UNKNOWN, PvtMode.of(9))
        assertEquals(PvtMode.UNKNOWN, PvtMode.of(15))
        assertEquals("RTK float", PvtMode.label(PvtMode.RTK_FLOAT))
        assertTrue(PvtMode.qualityRank(PvtMode.RTK_FIXED) > PvtMode.qualityRank(PvtMode.RTK_FLOAT))
        assertEquals(0, PvtMode.qualityRank(PvtMode.UNKNOWN))
        assertEquals(11, PvtMode.CODES.size)
    }

    @Test
    fun covarianceGivesSigmas() {
        val b = view(SbfEncoder.block(BlockNumber.POS_COV_GEODETIC, tow = 1000, wnc = 2390) {
            u8(PvtMode.DGNSS); u8(0)
            f32(0.04f); f32(0.09f); f32(-1f) // Cov_latlat, Cov_lonlon, Cov_hgthgt (negative: invalid)
        })
        assertEquals(PvtMode.DGNSS, PosCovGeodetic.mode(b))
        assertEquals(sqrt(0.04f), PosCovGeodetic.latitudeSigmaM(b))
        assertEquals(sqrt(0.09f), PosCovGeodetic.longitudeSigmaM(b))
        assertTrue(PosCovGeodetic.heightSigmaM(b).isNaN())
    }

    @Test
    fun dopAndAttitude() {
        val dop = view(SbfEncoder.block(BlockNumber.DOP_2, tow = 1000, wnc = 2390) {
            u8(14); u8(0); u16(180); u16(0); u16(95); u16(150); f32(2.5f); f32(Dnu.F32)
        })
        assertEquals(14, Dop.nrSv(dop))
        assertEquals(1.8f, Dop.pdop(dop))
        assertTrue(Dop.tdop(dop).isNaN())
        assertEquals(0.95f, Dop.hdop(dop))
        assertEquals(2.5f, Dop.hplM(dop))
        assertTrue(Dop.vplM(dop).isNaN())

        val att = view(SbfEncoder.block(BlockNumber.ATT_EULER, tow = 1000, wnc = 2390) {
            u8(255); u8(0); u16(2); u16(0); f32(123.5f); f32(-1.25f); f32(Dnu.F32)
        })
        assertEquals(-1, AttEuler.nrSv(att))
        assertEquals(2, AttEuler.mode(att))
        assertEquals(123.5f, AttEuler.headingDeg(att))
        assertTrue(AttEuler.rollDeg(att).isNaN())
    }

    @Test
    fun correctionsAndBaseStation() {
        // DiffCorrIn carrying an RTCM 3 message 1077: D3, 10-bit length, then the 12-bit type.
        val rtcm = view(SbfEncoder.block(BlockNumber.DIFF_CORR_IN, tow = 1000, wnc = 2390) {
            u8(2); u8(9); u8(0xD3); u8(0x00); u8(0x10); u8(1077 ushr 4); u8((1077 and 0xF) shl 4); u8(0)
        })
        assertEquals(2, DiffCorrIn.mode(rtcm))
        assertEquals("NTRIP", CorrectionSource.name(DiffCorrIn.source(rtcm)))
        assertEquals(1077, DiffCorrIn.rtcm3Type(rtcm))
        val cmr = view(SbfEncoder.block(BlockNumber.DIFF_CORR_IN, tow = 1000, wnc = 2390) { u8(1); u8(0); repeat(6) { u8(0) } })
        assertEquals(-1, DiffCorrIn.rtcm3Type(cmr))

        val base = view(SbfEncoder.block(BlockNumber.BASE_STATION, tow = 1000, wnc = 2390) {
            u16(17); u8(0); u8(8); u8(0); u8(0) // ID, BaseType, Source, Datum, Reserved
            f64(5_000_000.5); f64(2_500_000.25); f64(-2_900_000.0) // X, Y, Z
        })
        assertEquals(17, BaseStation.id(base))
        assertEquals(8, BaseStation.source(base))
        assertEquals(2_500_000.25, BaseStation.yM(base))
        assertFalse(BaseStation.zM(base).isNaN())
    }
}
