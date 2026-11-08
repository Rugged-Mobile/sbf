package ruggedmobile.sbf.verify

import org.junit.jupiter.api.Assumptions.assumeTrue
import ruggedmobile.sbf.ByteSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.management.ManagementFactory

/** Real SBF logs used by the tests, and helpers to read them. */
object DataFiles {
    /**
     * `SBF_DATA_DIR` (env) or `-Dsbf.data.dir`; otherwise `<project root>/../../../demos/SBF/data`,
     * where the project root is the nearest ancestor of the working directory with a `project.yaml`.
     */
    val dir: File? by lazy {
        val explicit = System.getenv("SBF_DATA_DIR") ?: System.getProperty("sbf.data.dir")
        if (explicit != null) return@lazy File(explicit).takeIf { it.isDirectory }
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "project.yaml").isFile) d = d.parentFile
        // A module directory has no project.yaml of its own, so this finds the project root.
        d?.let { File(it, "../../../demos/SBF/data").canonicalFile }?.takeIf { it.isDirectory }
    }

    /** Largest log included in the default parity run (`-Dsbf.parity.maxBytes` to change). */
    val parityMaxBytes: Long = System.getProperty("sbf.parity.maxBytes")?.toLong() ?: (256L shl 20)

    /** All SBF logs in [dir] (by extension or Septentrio RINEX-style name), smallest first. */
    fun all(): List<File> = dir?.listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".sbf", true) || it.name.matches(Regex(".*\\.\\d\\d_"))) }
        ?.sortedBy { it.length() }
        ?: emptyList()

    fun forParity(): List<File> = all().filter { it.length() <= parityMaxBytes }

    /** The named log, or skips the test if it is not available. */
    fun require(name: String): File {
        val f = dir?.let { File(it, name) }
        assumeTrue(f != null && f.isFile, "SBF log '$name' not found (set SBF_DATA_DIR)")
        return f!!
    }

    /** First [maxBytes] of [file] in memory. */
    fun load(file: File, maxBytes: Long = Long.MAX_VALUE): ByteArray {
        val n = minOf(file.length(), maxBytes).toInt()
        val out = ByteArray(n)
        FileInputStream(file).use { ins ->
            var p = 0
            while (p < n) {
                val r = ins.read(out, p, n - p)
                if (r < 0) break
                p += r
            }
        }
        return out
    }

    const val MEAS3_LOG = "supp1001.26_"
    const val LEGACY_LOG = "20260903_RB3_410563_Dual_Polant_7346_7347_Office_Nasrec_0000.sbf"
    const val ALL_BLOCKS_LOG = "all_blocks_0000.sbf"
}

fun InputStream.newSource(): ByteSource = ByteSource { b, o, l -> read(b, o, l) }
fun InputStream.refSource(): sbf.ByteSource = sbf.ByteSource { b, o, l -> read(b, o, l) }

/** In-memory source that replays [data] [passes] times, then reports end of input. */
class LoopingSource(private val data: ByteArray, private val passes: Int) {
    private var pass = 0
    private var pos = 0

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) {
            pass++
            pos = 0
        }
        if (pass >= passes || data.isEmpty()) return -1
        val n = minOf(length, data.size - pos)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }

    fun asNew(): ByteSource = ByteSource(::read)
    fun asRef(): sbf.ByteSource = sbf.ByteSource(::read)
}

/** JVM counters used by the allocation and memory tests. */
object Jvm {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    init {
        if (threads.isThreadAllocatedMemorySupported) threads.isThreadAllocatedMemoryEnabled = true
    }

    /** Exact bytes allocated so far by the calling thread (TLAB-accurate). */
    fun allocatedBytes(): Long = threads.currentThreadAllocatedBytes

    fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) }

    /** Heap in use after forcing full collections until it settles. */
    fun usedHeapAfterGc(): Long {
        val rt = Runtime.getRuntime()
        var last = Long.MAX_VALUE
        repeat(10) {
            System.gc()
            Thread.sleep(20)
            val used = rt.totalMemory() - rt.freeMemory()
            if (last != Long.MAX_VALUE && kotlin.math.abs(used - last) < 16 * 1024) return used
            last = used
        }
        return last
    }
}

fun mb(bytes: Long): String = "%.2f MB".format(bytes / 1048576.0)
fun mbps(bytes: Long, nanos: Long): String = "%.1f MB/s".format(bytes / 1048576.0 / (nanos / 1e9))
