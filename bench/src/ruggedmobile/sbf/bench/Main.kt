package ruggedmobile.sbf.bench

import ruggedmobile.sbf.ByteArraySource
import ruggedmobile.sbf.MeasEpoch
import ruggedmobile.sbf.MeasEpochCollector
import ruggedmobile.sbf.MeasEpochSink
import ruggedmobile.sbf.SbfBlockReader
import java.io.File
import java.io.FileInputStream
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

/**
 * Throughput and allocation benchmark: new library vs the original (reference copy).
 *
 * ```
 * ./kotlin run -m bench -- [--rounds N] [--warmup N] [--max-mb N] [--stage framing|meas|all] [--csv out.csv] [file|dir ...]
 * ```
 * Without paths, every log in SBF_DATA_DIR (or ../../../demos/SBF/data from the project root)
 * is used. Each file is loaded into memory (up to --max-mb, default 256), then both libraries
 * run alternately: `--warmup` untimed rounds, `--rounds` timed rounds. Reported per file and
 * stage: median time, throughput, speed-up, bytes allocated per run (exact, per-thread counter)
 * and GC count during the timed rounds.
 */
fun main(args: Array<String>) {
    // '.' as decimal separator, so the CSV stays valid in every locale.
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    var rounds = 7
    var warmup = 3
    var maxMb = 256L
    var stages = Stage.entries.toList()
    var csv: File? = null
    val paths = ArrayList<File>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i++]) {
            "--rounds" -> rounds = args[i++].toInt()
            "--warmup" -> warmup = args[i++].toInt()
            "--max-mb" -> maxMb = args[i++].toLong()
            "--stage" -> stages = when (args[i++]) {
                "framing" -> listOf(Stage.FRAMING)
                "meas" -> listOf(Stage.MEASUREMENTS)
                else -> Stage.entries.toList()
            }
            "--csv" -> csv = File(args[i++])
            "-h", "--help" -> { println("usage: bench [--rounds N] [--warmup N] [--max-mb N] [--stage framing|meas|all] [--csv file] [file|dir ...]"); return }
            else -> paths += File(a)
        }
    }
    val files = (if (paths.isEmpty()) listOfNotNull(defaultDataDir()) else paths)
        .flatMap { f -> if (f.isDirectory) f.listFiles()!!.filter(::isSbf) else listOf(f) }
        .sortedBy { it.length() }
    if (files.isEmpty()) {
        System.err.println("No SBF files. Pass paths or set SBF_DATA_DIR.")
        exitProcess(1)
    }

    val rt = Runtime.getRuntime()
    println("JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, max heap ${rt.maxMemory() shr 20} MB, ${rt.availableProcessors()} CPUs")
    println("rounds=$rounds warmup=$warmup max-mb=$maxMb")
    println()
    println("%-62s %-12s %9s %12s %12s %8s %14s %14s %5s %5s".format("file", "stage", "MB", "new MB/s", "orig MB/s", "speedup", "new alloc", "orig alloc", "nGC", "oGC"))

    val rows = ArrayList<String>()
    rows += "file,bytes,stage,new_median_ms,orig_median_ms,new_mb_s,orig_mb_s,speedup,new_alloc_bytes,orig_alloc_bytes,new_gcs,orig_gcs,epochs"
    for (f in files) {
        val data = load(f, maxMb shl 20)
        for (stage in stages) {
            val r = measure(data, stage, rounds, warmup)
            val mbs = data.size / 1048576.0
            println(
                "%-62s %-12s %9.1f %12.1f %12.1f %7.2fx %14s %14s %5d %5d".format(
                    f.name.take(62), stage, mbs, mbs / (r.newMs / 1000), mbs / (r.refMs / 1000), r.refMs / r.newMs,
                    human(r.newAlloc), human(r.refAlloc), r.newGcs, r.refGcs,
                ),
            )
            rows += listOf(f.name, data.size, stage, "%.3f".format(r.newMs), "%.3f".format(r.refMs),
                "%.1f".format(mbs / (r.newMs / 1000)), "%.1f".format(mbs / (r.refMs / 1000)), "%.3f".format(r.refMs / r.newMs),
                r.newAlloc, r.refAlloc, r.newGcs, r.refGcs, r.epochs).joinToString(",")
        }
    }
    csv?.let { it.absoluteFile.parentFile?.mkdirs(); it.writeText(rows.joinToString("\n") + "\n"); println("\nwrote $it") }
}

enum class Stage { FRAMING, MEASUREMENTS }

class Result(val newMs: Double, val refMs: Double, val newAlloc: Long, val refAlloc: Long, val newGcs: Long, val refGcs: Long, val epochs: Long)

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
private fun allocated() = threads.currentThreadAllocatedBytes
private fun gcs() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) }

private fun measure(data: ByteArray, stage: Stage, rounds: Int, warmup: Int): Result {
    val newT = ArrayList<Long>(); val refT = ArrayList<Long>()
    var newAlloc = 0L; var refAlloc = 0L; var newG = 0L; var refG = 0L
    var newSum = 0.0; var refSum = 0.0; var epochs = 0L
    repeat(warmup + rounds) { round ->
        val g0 = gcs(); val a0 = allocated(); val t0 = System.nanoTime()
        val n = runNew(data, stage)
        val t1 = System.nanoTime(); val a1 = allocated(); val g1 = gcs()
        val r = runRef(data, stage)
        val t2 = System.nanoTime(); val a2 = allocated(); val g2 = gcs()
        newSum = n.first; epochs = n.second; refSum = r
        if (round >= warmup) {
            newT += t1 - t0; refT += t2 - t1
            newAlloc = a1 - a0; refAlloc = a2 - a1
            newG += g1 - g0; refG += g2 - g1
        }
    }
    check(newSum == refSum) { "checksum mismatch: new=$newSum original=$refSum" }
    return Result(newT.sorted()[newT.size / 2] / 1e6, refT.sorted()[refT.size / 2] / 1e6, newAlloc, refAlloc, newG, refG, epochs)
}

private class NewSink : MeasEpochSink {
    var epochs = 0L
    var checksum = 0.0
    override fun onEpoch(epoch: MeasEpoch) {
        epochs++
        var acc = epoch.towMs.toDouble()
        for (ch in 0 until epoch.channelCount) {
            val first = ch * MeasEpoch.SETS_PER_CHANNEL
            for (s in first until first + MeasEpoch.SETS_PER_CHANNEL) {
                if (epoch.isValid(s)) acc += epoch.prM(s) + epoch.cn0DbHz(s) + epoch.lockTimeMs(s)
            }
        }
        checksum += acc
    }
}

private class RefSink : (sbf.MeasEpoch) -> Unit {
    var epochs = 0L
    var checksum = 0.0
    override fun invoke(epoch: sbf.MeasEpoch) {
        epochs++
        var acc = epoch.towMs.toDouble()
        for (ch in 0 until epoch.nbrElements) {
            for (ant in epoch.channelData[ch].measSet) for (m in ant) {
                if (m.isValid) acc += m.prM + m.cn0DbHz + m.lockTimeMs
            }
        }
        checksum += acc
    }
}

private fun runNew(data: ByteArray, stage: Stage): Pair<Double, Long> {
    val reader = SbfBlockReader(ByteArraySource(data))
    if (stage == Stage.FRAMING) {
        var acc = 0L
        while (true) { val b = reader.next() ?: break; acc += b.length + b.number }
        return acc.toDouble() to 0L
    }
    val sink = NewSink()
    val collector = MeasEpochCollector(reader, MeasEpochCollector.ALL, sink)
    while (true) collector.add(reader.next() ?: break)
    collector.flush()
    return sink.checksum + sink.epochs to sink.epochs
}

private fun runRef(data: ByteArray, stage: Stage): Double {
    var pos = 0
    val reader = sbf.SbfBlockReader(sbf.ByteSource { b, o, l ->
        if (pos >= data.size) -1 else minOf(l, data.size - pos).also { n -> data.copyInto(b, o, pos, pos + n); pos += n }
    })
    if (stage == Stage.FRAMING) {
        var acc = 0L
        while (true) { val b = reader.next() ?: break; acc += b.length + b.number }
        return acc.toDouble()
    }
    val sink = RefSink()
    val collector = sbf.MeasEpochCollector(sbf.MeasEpochCollector.ALL)
    val lookahead = sbf.BlockLookahead { reader.peek() }
    while (true) collector.add(reader.next() ?: break, lookahead, sink)
    collector.flush(sink)
    return sink.checksum + sink.epochs
}

private fun isSbf(f: File) = f.isFile && (f.name.endsWith(".sbf", true) || f.name.matches(Regex(".*\\.\\d\\d_")))

private fun defaultDataDir(): File? {
    System.getenv("SBF_DATA_DIR")?.let { return File(it) }
    var d: File? = File(System.getProperty("user.dir")).absoluteFile
    while (d != null && !File(d, "project.yaml").isFile) d = d.parentFile
    return d?.let { File(it, "../../../demos/SBF/data").canonicalFile }?.takeIf { it.isDirectory }
}

private fun load(f: File, max: Long): ByteArray {
    val n = minOf(f.length(), max).toInt()
    val out = ByteArray(n)
    FileInputStream(f).use { ins -> var p = 0; while (p < n) { val r = ins.read(out, p, n - p); if (r < 0) break; p += r } }
    return out
}

private fun human(b: Long): String = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1 shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}
