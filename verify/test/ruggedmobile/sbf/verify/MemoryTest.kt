package ruggedmobile.sbf.verify

import ruggedmobile.sbf.ByteArraySource
import ruggedmobile.sbf.MeasEpochCollector
import ruggedmobile.sbf.SbfBlockReader
import ruggedmobile.sbf.verify.Pipelines.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory behaviour for long-running use.
 *
 * - **Allocation per block** (exact): the JVM's per-thread allocation counter shows how many
 *   bytes a workload allocates. Running the same input 1× and 6× through one reader+collector
 *   isolates the steady-state cost per pass from the one-time setup. The new library must
 *   allocate (practically) nothing per pass; the original allocates a copy of every block and
 *   several small objects per epoch.
 * - **Retained heap**: the memory a used reader+collector keeps alive.
 * - **Stability**: a long replay must not grow the heap and, for the new library, must not
 *   trigger any garbage collection at all.
 */
class MemoryTest {

    private fun allocationFor(data: ByteArray, passes: Int, new: Boolean, stage: Stage): Long {
        val src = LoopingSource(data, passes)
        val before = Jvm.allocatedBytes()
        if (new) Pipelines.runNew(src.asNew(), stage) else Pipelines.runRef(src.asRef(), stage)
        return Jvm.allocatedBytes() - before
    }

    /** Steady-state bytes allocated per pass over [data], and the one-time setup cost. */
    private fun steadyState(data: ByteArray, new: Boolean, stage: Stage): Pair<Long, Long> {
        repeat(3) { allocationFor(data, 2, new, stage) } // JIT warm-up
        val one = allocationFor(data, 1, new, stage)
        val six = allocationFor(data, 6, new, stage)
        return (six - one) / 5 to one
    }

    @Test
    fun steadyStateAllocationIsZero() {
        val inputs = listOf(DataFiles.MEAS3_LOG, DataFiles.LEGACY_LOG)
            .mapNotNull { n -> DataFiles.dir?.resolve(n)?.takeIf { it.isFile }?.let { n to DataFiles.load(it, 16L shl 20) } }
        org.junit.jupiter.api.Assumptions.assumeTrue(inputs.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)")

        for ((name, data) in inputs) {
            for (stage in Stage.entries) {
                val (newPerPass, newSetup) = steadyState(data, new = true, stage)
                val (refPerPass, refSetup) = steadyState(data, new = false, stage)
                println(
                    "alloc %-70s %-12s per pass of %s: new=%,d B, original=%,d B | setup: new=%s original=%s"
                        .format(name, stage, mb(data.size.toLong()), newPerPass, refPerPass, mb(newSetup), mb(refSetup)),
                )
                // A few hundred bytes of noise are tolerated (e.g. JIT deoptimisation stubs); a
                // per-block or per-epoch allocation would show up as megabytes.
                assertTrue(newPerPass < 4096, "$name $stage: new library allocates $newPerPass B per pass")
                assertTrue(refPerPass > data.size / 2, "$name $stage: sanity check, original should copy every block")
            }
        }
    }

    @Test
    fun retainedHeapIsSmallerThanOriginal() {
        val data = DataFiles.load(DataFiles.require(DataFiles.MEAS3_LOG), 8L shl 20)

        fun retained(build: () -> Any): Long {
            val base = Jvm.usedHeapAfterGc()
            keepAlive = build()
            val used = Jvm.usedHeapAfterGc() - base
            keepAlive = null
            return used
        }

        val newBytes = retained {
            val reader = SbfBlockReader(ByteArraySource(data))
            val collector = MeasEpochCollector(reader, MeasEpochCollector.ALL, Pipelines.NewSink())
            while (true) collector.add(reader.next() ?: break)
            listOf(reader, collector)
        }
        val refBytes = retained {
            val reader = sbf.SbfBlockReader(sbf.ByteSource(ByteArraySource(data)::read))
            val collector = sbf.MeasEpochCollector()
            val sink = Pipelines.RefSink()
            while (true) collector.add(reader.next() ?: break, { reader.peek() }, sink)
            listOf(reader, collector)
        }
        println("retained heap after decoding: new=${mb(newBytes)} original=${mb(refBytes)}")
        assertTrue(newBytes < refBytes, "new retains ${mb(newBytes)}, original ${mb(refBytes)}")
    }

    @Test
    fun longReplayKeepsHeapFlatWithoutGc() {
        val data = DataFiles.load(DataFiles.require(DataFiles.MEAS3_LOG))
        val passes = System.getProperty("sbf.soak.passes")?.toInt() ?: 40
        Pipelines.runNew(LoopingSource(data, 2).asNew(), Stage.MEASUREMENTS) // warm-up

        val src = LoopingSource(data, passes)
        val reader = SbfBlockReader(src.asNew())
        val sink = Pipelines.NewSink()
        val collector = MeasEpochCollector(reader, MeasEpochCollector.ALL, sink)

        // Run the first pass, then measure the remaining ones.
        val passBytes = data.size.toLong()
        while (reader.position < passBytes) collector.add(reader.next() ?: break)
        val heapStart = Jvm.usedHeapAfterGc()
        val gcStart = Jvm.gcCount()
        val allocStart = Jvm.allocatedBytes()
        var blocks = 0L
        while (true) {
            collector.add(reader.next() ?: break)
            blocks++
        }
        collector.flush()
        val alloc = Jvm.allocatedBytes() - allocStart
        val gcs = Jvm.gcCount() - gcStart
        val heapEnd = Jvm.usedHeapAfterGc()

        println(
            "soak: %d passes, %s, %,d blocks, %,d epochs: allocated %,d B, GCs %d, heap %s -> %s"
                .format(passes - 1, mb(passBytes * (passes - 1)), blocks, sink.epochs, alloc, gcs, mb(heapStart), mb(heapEnd)),
        )
        assertTrue(alloc < 64 * 1024, "allocated $alloc B over the replay")
        assertEquals(0, gcs, "garbage collections during replay")
        assertTrue(heapEnd - heapStart < 1 shl 20, "heap grew from ${mb(heapStart)} to ${mb(heapEnd)}")
    }

    private companion object {
        /** Holds the measured objects so the GC cannot free them while their size is taken. */
        @Volatile
        var keepAlive: Any? = null
    }
}
