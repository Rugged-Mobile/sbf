package ruggedmobile.sbf.verify

import ruggedmobile.sbf.ByteSource
import ruggedmobile.sbf.EpochFilter
import ruggedmobile.sbf.MeasEpochCollector
import ruggedmobile.sbf.SbfBlockReader
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs the reference (original) library and the new library side by side over the same input,
 * one block at a time, and fails on the first difference in:
 * - the block stream (every block's bytes, the reader position after it, the CRC error count);
 * - every decoded epoch, field by field and bit for bit ([EpochSnapshot]);
 * - the number of epochs, and whether either side throws.
 *
 * Mirrors how sbf2asc drives the library: optional [filter] applied before the collector, file
 * mode (look-ahead) or live mode (end-of-epoch prediction + flush).
 */
class Lockstep(
    private val label: String,
    private val fileMode: Boolean = true,
    private val enabledTypes: Int = MeasEpochCollector.ALL,
    private val filter: Filter? = null,
    private val bufferSize: Int = 1 shl 20,
) {
    /** The same filter for both sides. */
    class Filter(val first: Long = EpochFilter.DONT_CARE_FIRST, val last: Long = EpochFilter.DONT_CARE_LAST, val intervalMs: Int = 1, val acceptInvalidTime: Boolean = true)

    class Result(val blocks: Long, val epochs: Long, val crcErrors: Int, val failure: String?)

    fun run(refSource: sbf.ByteSource, newSource: ByteSource): Result {
        val refReader = sbf.SbfBlockReader(refSource, bufferSize)
        val newReader = SbfBlockReader(newSource, bufferSize)
        val refFilter = filter?.let { sbf.EpochFilter(it.first, it.last, it.intervalMs, it.acceptInvalidTime) }
        val newFilter = filter?.let { EpochFilter(it.first, it.last, it.intervalMs, it.acceptInvalidTime) }

        val pool = ArrayDeque<EpochSnapshot>()
        val pending = ArrayDeque<EpochSnapshot>()
        var blockIdx = 0L
        var epochs = 0L

        val refLookahead = sbf.BlockLookahead { refReader.peek() }
        val refCollector = sbf.MeasEpochCollector(enabledTypes)
        val refSink: (sbf.MeasEpoch) -> Unit = { e ->
            pending.addLast((pool.removeFirstOrNull() ?: EpochSnapshot()).fill(e))
        }
        val newCollector = MeasEpochCollector(if (fileMode) newReader else null, enabledTypes) { e ->
            val snap = pending.removeFirstOrNull()
                ?: fail("$label: new library produced an extra epoch (tow=${e.towMs}) at block $blockIdx")
            snap.assertSame(e, "$label, epoch #$epochs, block #$blockIdx")
            pool.addLast(snap)
            epochs++
        }

        while (true) {
            val refThrown = runCatching { refReader.next() }
            val newThrown = runCatching { newReader.next() }
            if (refThrown.isFailure || newThrown.isFailure) {
                return outcome(refThrown.exceptionOrNull(), newThrown.exceptionOrNull(), "reading block #$blockIdx", blockIdx, epochs, refReader.crcErrors)
            }
            val rb = refThrown.getOrNull()
            val nb = newThrown.getOrNull()
            if (rb == null || nb == null) {
                if (rb != null || nb != null) fail("$label: block stream ends early on the ${if (rb == null) "reference" else "new"} side at block #$blockIdx")
                break
            }
            if (rb.length != nb.length) fail("$label: block #$blockIdx length ${rb.length} vs ${nb.length}")
            for (i in 0 until rb.length) {
                if (rb.bytes[i] != nb.bytes[nb.offset + i]) fail("$label: block #$blockIdx byte $i differs")
            }
            assertEquals(refReader.position, newReader.position, "$label: position after block #$blockIdx")

            val refIn = refFilter?.includes(rb) ?: true
            val newIn = newFilter?.includes(nb) ?: true
            assertEquals(refIn, newIn, "$label: filter decision for block #$blockIdx")
            if (refIn) {
                val r = runCatching { refCollector.add(rb, if (fileMode) refLookahead else null, refSink) }
                val n = runCatching { newCollector.add(nb) }
                if (r.isFailure || n.isFailure) {
                    return outcome(r.exceptionOrNull(), n.exceptionOrNull(), "decoding block #$blockIdx", blockIdx, epochs, refReader.crcErrors)
                }
                if (pending.isNotEmpty()) fail("$label: reference produced an epoch the new library did not, at block #$blockIdx")
            }
            blockIdx++
        }

        val r = runCatching { refCollector.flush(refSink) }
        val n = runCatching { newCollector.flush() }
        if (r.isFailure || n.isFailure) return outcome(r.exceptionOrNull(), n.exceptionOrNull(), "flush", blockIdx, epochs, refReader.crcErrors)
        if (pending.isNotEmpty()) fail("$label: reference produced ${pending.size} more epoch(s) at flush")

        assertEquals(refReader.crcErrors, newReader.crcErrors, "$label: CRC error count")
        return Result(blockIdx, epochs, refReader.crcErrors, null)
    }

    /** Both sides must fail the same way at the same point; the run then counts as matching. */
    private fun outcome(ref: Throwable?, new: Throwable?, where: String, blocks: Long, epochs: Long, crc: Int): Result {
        // A mismatch detected inside the new library's epoch callback surfaces as an exception
        // from its add(): report it as is.
        if (new is AssertionError) throw new
        if (ref == null || new == null || ref::class != new::class) {
            val e = AssertionError("$label: $where: reference threw ${ref?.let { it::class.simpleName }}, new threw ${new?.let { it::class.simpleName }}")
            (new ?: ref)?.let(e::initCause)
            throw e
        }
        return Result(blocks, epochs, crc, "${ref::class.simpleName} while $where")
    }
}
