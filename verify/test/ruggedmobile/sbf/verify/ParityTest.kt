package ruggedmobile.sbf.verify

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ruggedmobile.sbf.MeasEpochCollector
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Functional parity on real receiver logs: the new library must produce exactly the same
 * blocks and exactly the same decoded epochs (every field, bit for bit) as the original.
 *
 * Covers both decoder paths (Meas3 in supp1001, MeasEpoch v2 + MeasExtra in the RB3 dual-antenna
 * log, plus whatever else is in the data folder), file mode and live mode, each decoder family
 * on its own, the epoch filter, and a minimum-size reader buffer fed in small chunks.
 */
class ParityTest {

    private fun runFile(file: File, lockstep: Lockstep, chunk: Int? = null): Lockstep.Result {
        fun open() = BufferedInputStream(FileInputStream(file), 1 shl 16)
        return open().use { a ->
            open().use { b ->
                if (chunk == null) {
                    lockstep.run(a.refSource(), b.newSource())
                } else {
                    // Deliver at most `chunk` bytes per read, to stress buffer refills/compaction.
                    lockstep.run(
                        sbf.ByteSource { buf, off, len -> a.read(buf, off, minOf(len, chunk)) },
                        ruggedmobile.sbf.ByteSource { buf, off, len -> b.read(buf, off, minOf(len, chunk)) },
                    )
                }
            }
        }
    }

    private fun report(name: String, mode: String, r: Lockstep.Result) {
        println("parity %-70s %-22s blocks=%,d epochs=%,d crcErrors=%d".format(name, mode, r.blocks, r.epochs, r.crcErrors))
    }

    @TestFactory
    fun fileModeOnEveryLog(): List<DynamicTest> {
        val files = DataFiles.forParity()
        assumeTrue(files.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)")
        return files.map { f ->
            DynamicTest.dynamicTest(f.name) {
                val r = runFile(f, Lockstep(f.name))
                report(f.name, "file", r)
                assertNull(r.failure)
            }
        }
    }

    @TestFactory
    fun liveModeOnEveryLog(): List<DynamicTest> {
        val files = DataFiles.forParity()
        assumeTrue(files.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)")
        return files.map { f ->
            DynamicTest.dynamicTest(f.name) {
                val r = runFile(f, Lockstep("${f.name} (live)", fileMode = false))
                report(f.name, "live", r)
                assertNull(r.failure)
            }
        }
    }

    @Test
    fun meas3Only() {
        val f = DataFiles.require(DataFiles.MEAS3_LOG)
        val r = runFile(f, Lockstep("meas3 only", enabledTypes = MeasEpochCollector.MEAS3))
        report(f.name, "MEAS3 only", r)
        assertTrue(r.epochs > 0)
    }

    @Test
    fun measEpochOnly() {
        val f = DataFiles.require(DataFiles.LEGACY_LOG)
        val r = runFile(f, Lockstep("measEpoch only", enabledTypes = MeasEpochCollector.MEAS_EPOCH))
        report(f.name, "MEAS_EPOCH only", r)
        assertTrue(r.epochs > 0)
    }

    @Test
    fun withEpochFilters() {
        val f = DataFiles.require(DataFiles.MEAS3_LOG)
        // Windows placed inside the log, so they actually select part of it.
        val (tow, wnc) = firstTime(f)
        val tod = tow % 86_400_000L
        val gps = wnc * 604_800_000L + tow
        val filters = listOf(
            Lockstep.Filter(intervalMs = 5000),
            Lockstep.Filter(first = tod + 60_000, last = tod + 300_000), // time of day
            Lockstep.Filter(first = gps + 120_000, last = gps + 600_000), // absolute GPS time
            Lockstep.Filter(acceptInvalidTime = false, intervalMs = 1000),
        )
        for ((i, flt) in filters.withIndex()) {
            for (fileMode in listOf(true, false)) {
                val r = runFile(f, Lockstep("filter $i fileMode=$fileMode", fileMode = fileMode, filter = flt))
                report(f.name, "filter $i ${if (fileMode) "file" else "live"}", r)
                assertTrue(r.epochs > 0, "filter $i selects no epochs; the case would prove nothing")
            }
        }
    }

    private fun firstTime(file: File): Pair<Long, Long> =
        FileInputStream(file).use { ins ->
            val reader = ruggedmobile.sbf.SbfBlockReader(ins.newSource())
            while (true) {
                val b = reader.next() ?: error("no block with a valid time in ${file.name}")
                if (b.isTimeValid) return b.tow to b.wnc.toLong()
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /** Minimum buffer (2 × 65532) and 1000-byte reads: many compactions while a block is live. */
    @Test
    fun smallBufferAndSmallReads() {
        for (name in listOf(DataFiles.MEAS3_LOG, DataFiles.ALL_BLOCKS_LOG)) {
            val f = DataFiles.require(name)
            val r = runFile(f, Lockstep("$name small buffer", bufferSize = 0), chunk = 1000)
            report(f.name, "small buffer/reads", r)
        }
    }
}
