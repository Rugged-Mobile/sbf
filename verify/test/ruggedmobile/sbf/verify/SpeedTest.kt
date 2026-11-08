package ruggedmobile.sbf.verify

import ruggedmobile.sbf.verify.Pipelines.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Speed gate: the new library must not be slower than the original.
 *
 * Both run in the same JVM on the same in-memory data (no disk I/O in the timing). Runs
 * alternate between the two libraries after a warm-up, so JIT state, CPU frequency and
 * background noise affect both equally, and the medians are compared. The checksums of both
 * must agree, so neither side can skip work.
 *
 * For detailed numbers on every log use the `bench` app; this test is the CI gate.
 */
class SpeedTest {
    private val rounds = System.getProperty("sbf.speed.rounds")?.toInt() ?: 7
    private val warmup = 3

    private fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

    private fun compare(name: String, data: ByteArray, stage: Stage) {
        val newTimes = ArrayList<Long>()
        val refTimes = ArrayList<Long>()
        var newSum = 0.0
        var refSum = 0.0
        repeat(warmup + rounds) { i ->
            val t0 = System.nanoTime()
            newSum = Pipelines.runNew(ruggedmobile.sbf.ByteArraySource(data), stage)
            val t1 = System.nanoTime()
            refSum = Pipelines.runRef(sbf.ByteSource(ruggedmobile.sbf.ByteArraySource(data)::read), stage)
            val t2 = System.nanoTime()
            if (i >= warmup) {
                newTimes += t1 - t0
                refTimes += t2 - t1
            }
        }
        assertEquals(refSum, newSum, "$name $stage: checksums differ")
        val n = median(newTimes)
        val r = median(refTimes)
        println(
            "speed %-70s %-12s new=%s (%.1f ms) original=%s (%.1f ms) speed-up x%.2f"
                .format(name, stage, mbps(data.size.toLong(), n), n / 1e6, mbps(data.size.toLong(), r), r / 1e6, r.toDouble() / n),
        )
        assertTrue(n <= r, "$name $stage: new median ${n / 1e6} ms is slower than original ${r / 1e6} ms")
    }

    @Test
    fun meas3Log() {
        val data = DataFiles.load(DataFiles.require(DataFiles.MEAS3_LOG))
        for (stage in Stage.entries) compare(DataFiles.MEAS3_LOG, data, stage)
    }

    @Test
    fun legacyLog() {
        val data = DataFiles.load(DataFiles.require(DataFiles.LEGACY_LOG), 48L shl 20)
        for (stage in Stage.entries) compare(DataFiles.LEGACY_LOG + " (first 48 MB)", data, stage)
    }
}
