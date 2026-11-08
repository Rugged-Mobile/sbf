package ruggedmobile.sbf.verify

import com.ruggedmobile.plot.sbf.CorrectionSources
import com.ruggedmobile.plot.sbf.Datums
import com.ruggedmobile.plot.sbf.SatDirection
import com.ruggedmobile.plot.sbf.SbfRecords
import ruggedmobile.sbf.AttEuler
import ruggedmobile.sbf.BaseStation
import ruggedmobile.sbf.BlockNumber
import ruggedmobile.sbf.ByteArraySource
import ruggedmobile.sbf.CorrectionSource
import ruggedmobile.sbf.Datum
import ruggedmobile.sbf.DiffCorrIn
import ruggedmobile.sbf.Dop
import ruggedmobile.sbf.PosCovCartesian
import ruggedmobile.sbf.PosCovGeodetic
import ruggedmobile.sbf.PvtCartesian
import ruggedmobile.sbf.PvtGeodetic
import ruggedmobile.sbf.PvtMode
import ruggedmobile.sbf.SatDirections
import ruggedmobile.sbf.SbfBlock
import ruggedmobile.sbf.SbfBlockReader
import ruggedmobile.sbf.gpsTimeS
import java.io.File
import java.io.FileInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import com.ruggedmobile.plot.sbf.PvtMode as RefPvtMode
import com.ruggedmobile.plot.sbf.gpsTimeS as refGpsTimeS

/**
 * Parity of the PVT/status decoders (`PvtBlocks.kt`, `SatDirections.kt`) with gnss-plot's
 * `SbfRecords`, the code they were ported from (copied unchanged into `reference-records`).
 *
 * Every field of every decoded block is compared bit for bit: on every real log, on blocks with
 * random payload damage, and on truncated blocks.
 */
class RecordsParityTest {
    private val dirs = SatDirections()

    /** Blocks compared per block number. */
    private val compared = sortedMapOf<Int, Long>()

    private val types = intArrayOf(
        BlockNumber.PVT_GEODETIC_2, BlockNumber.PVT_CARTESIAN_2, BlockNumber.POS_COV_GEODETIC,
        BlockNumber.POS_COV_CARTESIAN, BlockNumber.DOP_2, BlockNumber.ATT_EULER, BlockNumber.BASE_STATION,
        BlockNumber.DIFF_CORR_IN, BlockNumber.CHANNEL_STATUS, BlockNumber.SAT_VISIBILITY,
    )

    @Test
    fun realLogs() {
        val files = DataFiles.forParity()
        org.junit.jupiter.api.Assumptions.assumeTrue(files.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)")
        for (f in files) {
            val before = compared.values.sum()
            FileInputStream(f).use { ins ->
                val reader = SbfBlockReader(ins.newSource())
                while (true) {
                    val b = reader.next() ?: break
                    compare(f.name, b, sbf.SbfBlock(b.toByteArray()))
                }
            }
            println("records %-70s compared=%d".format(f.name, compared.values.sum() - before))
        }
        println("records per block number: $compared")
        // The logs in demos/SBF/data contain all of these; a missing type would mean the test proves nothing for it.
        for (t in types) assertTrue((compared[t] ?: 0) > 0, "no block $t found in the logs")
    }

    @Test
    fun decodersAllocateNothing() {
        val data = DataFiles.load(DataFiles.require(DataFiles.LEGACY_LOG), 16L shl 20)
        var sink = 0.0
        // As in MemoryTest: one replay vs six replays through one reader, so fixed costs cancel out.
        fun replay(passes: Int): Long {
            val start = Jvm.allocatedBytes()
            val reader = SbfBlockReader(LoopingSource(data, passes).asNew())
            while (true) {
                val b = reader.next() ?: break
                sink += b.gpsTimeS
                when (b.number) {
                    BlockNumber.PVT_GEODETIC_2 -> sink += PvtGeodetic.latitudeRad(b) + PvtGeodetic.hAccuracyM(b) + PvtGeodetic.mode(b)
                    BlockNumber.PVT_CARTESIAN_2 -> sink += PvtCartesian.xM(b) + PvtCartesian.datum(b)
                    BlockNumber.POS_COV_GEODETIC -> sink += PosCovGeodetic.heightSigmaM(b)
                    BlockNumber.POS_COV_CARTESIAN -> sink += PosCovCartesian.zSigmaM(b)
                    BlockNumber.DOP_2 -> sink += Dop.pdop(b)
                    BlockNumber.ATT_EULER -> sink += AttEuler.headingDeg(b)
                    BlockNumber.BASE_STATION -> sink += BaseStation.zM(b)
                    BlockNumber.DIFF_CORR_IN -> sink += DiffCorrIn.rtcm3Type(b)
                    BlockNumber.CHANNEL_STATUS -> sink += dirs.readChannelStatus(b)
                    BlockNumber.SAT_VISIBILITY -> sink += dirs.readSatVisibility(b)
                }
            }
            return Jvm.allocatedBytes() - start
        }
        repeat(3) { replay(2) } // warm-up (class loading, JIT)
        val one = replay(1)
        val perPass = (replay(6) - one) / 5
        println("records alloc: $perPass B per pass of 16 MB, setup ${mb(one)} (sink $sink)")
        assertTrue(perPass < 4096, "decoding allocated $perPass bytes per pass")
    }

    @Test
    fun damagedBlocks() {
        val samples = samples(perType = 300)
        org.junit.jupiter.api.Assumptions.assumeTrue(samples.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)")
        val seeds = System.getProperty("sbf.fuzz.seeds")?.toInt() ?: 40
        var runs = 0
        var refThrew = 0
        val view = SbfBlock()
        for (seed in 0 until seeds) {
            val rnd = Random(seed)
            for (raw in samples) {
                // Payload damage: the header (ID, length) stays intact, so the decoder still runs.
                val damaged = raw.copyOf()
                repeat(1 + rnd.nextInt(8)) {
                    val at = 14 + rnd.nextInt(damaged.size - 14)
                    damaged[at] = rnd.nextInt(256).toByte()
                }
                // Truncation: shorter than its length field says, as from a short read.
                val cut = 14 + rnd.nextInt(raw.size - 14)
                for (bytes in listOf(damaged, raw.copyOf(cut))) {
                    runs++
                    val ref = sbf.SbfBlock(bytes)
                    val refOk = try {
                        refDecode(ref)
                        true
                    } catch (e: IndexOutOfBoundsException) {
                        refThrew++
                        false
                    }
                    view.wrap(bytes)
                    if (refOk) compare("seed $seed", view, ref) else newDecode(view) // must not throw either way
                }
            }
        }
        println("records fuzz: $runs damaged blocks, reference threw on $refThrew (out-of-range read), all others identical")
        assertTrue(runs > refThrew)
    }

    @Test
    fun codeTables() {
        for (code in -1..15) {
            val ref = RefPvtMode.fromCode(code)
            assertEquals(ref.code, PvtMode.normalize(code), "mode $code")
            assertEquals(ref.label, PvtMode.label(PvtMode.normalize(code)), "label $code")
            assertEquals(ref.qualityRank, PvtMode.qualityRank(PvtMode.normalize(code)), "rank $code")
        }
        assertEquals(RefPvtMode.entries.map { it.code }, PvtMode.CODES.toList())
        for (v in -1..300) {
            assertEquals(Datums.name(v), Datum.name(v), "datum $v")
            assertEquals(CorrectionSources.name(v), CorrectionSource.name(v), "source $v")
            assertEquals(CorrectionSources.format(v), CorrectionSource.format(v), "format $v")
        }
    }

    /** Up to [perType] raw blocks of each decoded type from the fuzz excerpt logs. */
    private fun samples(perType: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val counts = HashMap<Int, Int>()
        for (name in listOf(DataFiles.LEGACY_LOG, DataFiles.MEAS3_LOG, DataFiles.ALL_BLOCKS_LOG)) {
            val f = DataFiles.dir?.let { File(it, name) }?.takeIf { it.isFile } ?: continue
            val reader = SbfBlockReader(ByteArraySource(DataFiles.load(f, 32L shl 20)))
            while (true) {
                val b = reader.next() ?: break
                if (b.number !in types) continue
                val n = counts.getOrDefault(b.number, 0)
                if (n < perType) {
                    counts[b.number] = n + 1
                    out += b.toByteArray()
                }
            }
        }
        return out
    }

    private fun refDecode(r: sbf.SbfBlock) {
        r.refGpsTimeS
        SbfRecords.pvtGeodetic(r); SbfRecords.pvtCartesian(r); SbfRecords.posCovGeodetic(r)
        SbfRecords.posCovCartesian(r); SbfRecords.dop(r); SbfRecords.attEuler(r); SbfRecords.baseStation(r)
        SbfRecords.diffCorrIn(r); SbfRecords.channelStatus(r); SbfRecords.satVisibility(r)
    }

    /** Reads the block's fields without comparing, for blocks the reference cannot decode: it must not throw. */
    private fun newDecode(b: SbfBlock) {
        b.gpsTimeS
        dirs.readChannelStatus(b)
        dirs.readSatVisibility(b)
        when (b.number) {
            BlockNumber.PVT_GEODETIC_2 -> {
                PvtGeodetic.mode(b); PvtGeodetic.latitudeRad(b); PvtGeodetic.hAccuracyM(b); PvtGeodetic.nrBases(b)
                PvtGeodetic.vAccuracyM(b); PvtGeodetic.referenceId(b); PvtGeodetic.meanCorrAgeS(b)
            }
            BlockNumber.PVT_CARTESIAN_2 -> { PvtCartesian.zM(b); PvtCartesian.datum(b) }
            BlockNumber.DIFF_CORR_IN -> DiffCorrIn.rtcm3Type(b)
            BlockNumber.BASE_STATION -> BaseStation.zM(b)
            BlockNumber.ATT_EULER -> AttEuler.rollDeg(b)
            BlockNumber.DOP_2 -> Dop.vplM(b)
            BlockNumber.POS_COV_GEODETIC -> PosCovGeodetic.heightSigmaM(b)
            BlockNumber.POS_COV_CARTESIAN -> PosCovCartesian.zSigmaM(b)
        }
    }

    private fun compare(ctx: String, b: SbfBlock, r: sbf.SbfBlock) {
        val at = "$ctx block ${b.number} tow ${b.tow}"
        same(at, "timeS", r.refGpsTimeS, b.gpsTimeS)
        when (b.number) {
            BlockNumber.PVT_GEODETIC_2 -> {
                val e = SbfRecords.pvtGeodetic(r) ?: fail("$at: reference returned null")
                same(at, "timeS", e.timeS, b.gpsTimeS)
                same(at, "mode", e.mode.code, PvtGeodetic.mode(b))
                same(at, "is2D", e.is2D, PvtGeodetic.is2D(b))
                same(at, "error", e.error, PvtGeodetic.error(b))
                same(at, "latitudeRad", e.latitudeRad, PvtGeodetic.latitudeRad(b))
                same(at, "longitudeRad", e.longitudeRad, PvtGeodetic.longitudeRad(b))
                same(at, "heightM", e.heightM, PvtGeodetic.heightM(b))
                same(at, "undulationM", e.undulationM, PvtGeodetic.undulationM(b))
                same(at, "vnMps", e.vnMps, PvtGeodetic.vnMps(b))
                same(at, "veMps", e.veMps, PvtGeodetic.veMps(b))
                same(at, "vuMps", e.vuMps, PvtGeodetic.vuMps(b))
                same(at, "cogDeg", e.cogDeg, PvtGeodetic.cogDeg(b))
                same(at, "nrSv", e.nrSv, PvtGeodetic.nrSv(b))
                same(at, "meanCorrAgeS", e.meanCorrAgeS, PvtGeodetic.meanCorrAgeS(b))
                same(at, "hAccuracyM", e.hAccuracyM, PvtGeodetic.hAccuracyM(b))
                same(at, "vAccuracyM", e.vAccuracyM, PvtGeodetic.vAccuracyM(b))
                same(at, "referenceId", e.referenceId, PvtGeodetic.referenceId(b))
                same(at, "nrBases", e.nrBases, PvtGeodetic.nrBases(b))
                same(at, "datum", e.datum, PvtGeodetic.datum(b))
            }
            BlockNumber.PVT_CARTESIAN_2 -> {
                val e = SbfRecords.pvtCartesian(r) ?: fail("$at: reference returned null")
                same(at, "mode", e.mode.code, PvtCartesian.mode(b))
                same(at, "error", e.error, PvtCartesian.error(b))
                same(at, "xM", e.xM, PvtCartesian.xM(b))
                same(at, "yM", e.yM, PvtCartesian.yM(b))
                same(at, "zM", e.zM, PvtCartesian.zM(b))
                same(at, "undulationM", e.undulationM, PvtCartesian.undulationM(b))
                same(at, "vxMps", e.vxMps, PvtCartesian.vxMps(b))
                same(at, "vyMps", e.vyMps, PvtCartesian.vyMps(b))
                same(at, "vzMps", e.vzMps, PvtCartesian.vzMps(b))
                same(at, "nrSv", e.nrSv, PvtCartesian.nrSv(b))
                same(at, "datum", e.datum, PvtCartesian.datum(b))
            }
            BlockNumber.POS_COV_GEODETIC -> {
                val e = SbfRecords.posCovGeodetic(r) ?: fail("$at: reference returned null")
                same(at, "mode", e.mode.code, PosCovGeodetic.mode(b))
                same(at, "latitudeM", e.latitudeM, PosCovGeodetic.latitudeSigmaM(b))
                same(at, "longitudeM", e.longitudeM, PosCovGeodetic.longitudeSigmaM(b))
                same(at, "heightM", e.heightM, PosCovGeodetic.heightSigmaM(b))
            }
            BlockNumber.POS_COV_CARTESIAN -> {
                val e = SbfRecords.posCovCartesian(r) ?: fail("$at: reference returned null")
                same(at, "mode", e.mode.code, PosCovCartesian.mode(b))
                same(at, "xM", e.xM, PosCovCartesian.xSigmaM(b))
                same(at, "yM", e.yM, PosCovCartesian.ySigmaM(b))
                same(at, "zM", e.zM, PosCovCartesian.zSigmaM(b))
            }
            BlockNumber.DOP_2 -> {
                val e = SbfRecords.dop(r) ?: fail("$at: reference returned null")
                same(at, "nrSv", e.nrSv, Dop.nrSv(b))
                same(at, "pdop", e.pdop, Dop.pdop(b))
                same(at, "tdop", e.tdop, Dop.tdop(b))
                same(at, "hdop", e.hdop, Dop.hdop(b))
                same(at, "vdop", e.vdop, Dop.vdop(b))
                same(at, "hplM", e.hplM, Dop.hplM(b))
                same(at, "vplM", e.vplM, Dop.vplM(b))
            }
            BlockNumber.ATT_EULER -> {
                val e = SbfRecords.attEuler(r) ?: fail("$at: reference returned null")
                same(at, "nrSv", e.nrSv, AttEuler.nrSv(b))
                same(at, "error", e.error, AttEuler.error(b))
                same(at, "mode", e.mode, AttEuler.mode(b))
                same(at, "headingDeg", e.headingDeg, AttEuler.headingDeg(b))
                same(at, "pitchDeg", e.pitchDeg, AttEuler.pitchDeg(b))
                same(at, "rollDeg", e.rollDeg, AttEuler.rollDeg(b))
            }
            BlockNumber.BASE_STATION -> {
                val e = SbfRecords.baseStation(r) ?: fail("$at: reference returned null")
                same(at, "id", e.id, BaseStation.id(b))
                same(at, "type", e.type, BaseStation.type(b))
                same(at, "source", e.source, BaseStation.source(b))
                same(at, "xM", e.xM, BaseStation.xM(b))
                same(at, "yM", e.yM, BaseStation.yM(b))
                same(at, "zM", e.zM, BaseStation.zM(b))
            }
            BlockNumber.DIFF_CORR_IN -> {
                val e = SbfRecords.diffCorrIn(r) ?: fail("$at: reference returned null")
                same(at, "mode", e.mode, DiffCorrIn.mode(b))
                same(at, "source", e.source, DiffCorrIn.source(b))
                same(at, "rtcm3Type", e.rtcm3Type, DiffCorrIn.rtcm3Type(b))
            }
            BlockNumber.CHANNEL_STATUS -> directions(at, SbfRecords.channelStatus(r), dirs.readChannelStatus(b))
            BlockNumber.SAT_VISIBILITY -> directions(at, SbfRecords.satVisibility(r), dirs.readSatVisibility(b))
            else -> return
        }
        compared.merge(b.number, 1L, Long::plus)
    }

    private fun directions(at: String, ref: List<SatDirection>, size: Int) {
        same(at, "size", ref.size, size)
        for ((i, d) in ref.withIndex()) {
            same(at, "prn[$i]", d.prn, dirs.prn[i])
            same(at, "azimuthDeg[$i]", d.azimuthDeg, dirs.azimuthDeg[i])
            same(at, "elevationDeg[$i]", d.elevationDeg, dirs.elevationDeg[i])
            same(at, "usedInPvt[$i]", tri(d.usedInPvt), dirs.usedInPvt[i].toInt())
            same(at, "rising[$i]", tri(d.rising), dirs.rising[i].toInt())
        }
    }

    private fun tri(v: Boolean?): Int = when (v) {
        true -> SatDirections.YES.toInt()
        false -> SatDirections.NO.toInt()
        null -> SatDirections.UNKNOWN.toInt()
    }

    private fun same(at: String, field: String, expected: Double, actual: Double) {
        if (expected.toRawBits() != actual.toRawBits()) fail("$at $field: reference $expected, new $actual")
    }

    private fun same(at: String, field: String, expected: Float, actual: Float) {
        if (expected.toRawBits() != actual.toRawBits()) fail("$at $field: reference $expected, new $actual")
    }

    private fun same(at: String, field: String, expected: Int, actual: Int) {
        if (expected != actual) fail("$at $field: reference $expected, new $actual")
    }

    private fun same(at: String, field: String, expected: Boolean, actual: Boolean) {
        if (expected != actual) fail("$at $field: reference $expected, new $actual")
    }
}
