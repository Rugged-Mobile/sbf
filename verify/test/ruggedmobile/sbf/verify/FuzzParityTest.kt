package ruggedmobile.sbf.verify

import ruggedmobile.sbf.ByteArraySource
import ruggedmobile.sbf.SbfBlockReader
import ruggedmobile.sbf.testkit.SbfEncoder
import kotlin.random.Random
import kotlin.test.Test

/**
 * Parity on damaged input. Real log excerpts are corrupted in deterministic, seeded ways, and
 * both libraries must still agree block for block and field for field (or fail identically).
 *
 * Two kinds of damage:
 * - **Stream damage** (bit flips, dropped/duplicated/inserted bytes, truncation): exercises
 *   resynchronisation, CRC rejection and the zero-copy reader's buffer handling.
 * - **Payload damage with the CRC recomputed**: the blocks still pass the CRC, so the corrupt
 *   contents reach the decoders. This exercises every bounds check and odd field value in the
 *   MeasEpoch/MeasExtra/MeasFullRange/SISMeas and Meas3 decoders.
 */
class FuzzParityTest {
    private val seeds = (System.getProperty("sbf.fuzz.seeds")?.toInt() ?: 40)
    private val excerptBytes = 1 shl 20

    private fun excerpts(): List<Pair<String, ByteArray>> = listOf(DataFiles.MEAS3_LOG, DataFiles.LEGACY_LOG, DataFiles.ALL_BLOCKS_LOG)
        .mapNotNull { name -> DataFiles.dir?.resolve(name)?.takeIf { it.isFile }?.let { name to DataFiles.load(it, excerptBytes.toLong()) } }
        .also { org.junit.jupiter.api.Assumptions.assumeTrue(it.isNotEmpty(), "no SBF logs found (set SBF_DATA_DIR)") }

    private var runs = 0
    private var epochs = 0L
    private var crcErrors = 0L
    private var bothFailed = 0

    private fun check(label: String, data: ByteArray) {
        for (fileMode in listOf(true, false)) {
            val r = Lockstep("$label fileMode=$fileMode", fileMode = fileMode)
                .run(ref(data), ByteArraySource(data))
            runs++
            epochs += r.epochs
            crcErrors += r.crcErrors
            if (r.failure != null) {
                bothFailed++
                println("fuzz $label fileMode=$fileMode: both libraries failed identically: ${r.failure}")
            }
        }
    }

    @org.junit.jupiter.api.AfterEach
    fun summary() {
        println("fuzz summary: $runs runs, $epochs epochs compared, $crcErrors CRC rejections, $bothFailed identical failures")
        runs = 0; epochs = 0; crcErrors = 0; bothFailed = 0
    }

    private fun ref(data: ByteArray): sbf.ByteSource {
        var pos = 0
        return sbf.ByteSource { b, o, l ->
            if (pos >= data.size) -1 else minOf(l, data.size - pos).also { n -> data.copyInto(b, o, pos, pos + n); pos += n }
        }
    }

    @Test
    fun streamDamage() {
        for ((name, original) in excerpts()) {
            for (seed in 0 until seeds) {
                val rnd = Random(seed)
                val data = when (seed % 4) {
                    0 -> original.copyOf().also { d -> repeat(1 + rnd.nextInt(200)) { val i = rnd.nextInt(d.size); d[i] = (d[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() } }
                    1 -> splice(original, rnd) // drop / duplicate / insert runs
                    2 -> original.copyOf(rnd.nextInt(original.size)) // truncation
                    else -> splice(original.copyOf().also { d -> repeat(50) { d[rnd.nextInt(d.size)] = '$'.code.toByte() } }, rnd)
                }
                check("$name stream seed=$seed", data)
            }
        }
    }

    @Test
    fun payloadDamageWithValidCrc() {
        for ((name, original) in excerpts()) {
            val blocks = blockRanges(original)
            for (seed in 0 until seeds) {
                val rnd = Random(1000 + seed)
                val d = original.copyOf()
                // Damage ~2 % of the blocks; skip the 8-byte header so the framing stays valid.
                for ((off, len) in blocks) {
                    if (rnd.nextInt(50) != 0 || len <= 14) continue
                    repeat(1 + rnd.nextInt(6)) {
                        val i = off + 8 + rnd.nextInt(len - 8)
                        d[i] = when (rnd.nextInt(3)) {
                            0 -> rnd.nextInt(256).toByte()
                            1 -> 0xFF.toByte()
                            else -> 0
                        }
                    }
                    val crc = SbfEncoder.crc16(d, off + 4, len - 4)
                    d[off + 2] = crc.toByte()
                    d[off + 3] = (crc ushr 8).toByte()
                }
                check("$name payload seed=$seed", d)
            }
        }
    }

    /** (offset, length) of every valid block, found with the new reader. */
    private fun blockRanges(data: ByteArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val r = SbfBlockReader(ByteArraySource(data))
        while (true) {
            val b = r.next() ?: break
            out += (r.position - b.length).toInt() to b.length
        }
        return out
    }

    private fun splice(src: ByteArray, rnd: Random): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size + 4096)
        var i = 0
        while (i < src.size) {
            val run = minOf(src.size - i, 1 + rnd.nextInt(20_000))
            when (rnd.nextInt(10)) {
                0 -> {} // drop the run
                1 -> { out.write(src, i, run); out.write(src, i, minOf(run, 300)) } // duplicate part
                2 -> { out.write(src, i, run); out.write(ByteArray(rnd.nextInt(100)) { rnd.nextInt(256).toByte() }) }
                else -> out.write(src, i, run)
            }
            i += run
        }
        return out.toByteArray()
    }
}
