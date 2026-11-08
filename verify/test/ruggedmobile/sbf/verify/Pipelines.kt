package ruggedmobile.sbf.verify

import ruggedmobile.sbf.MeasEpoch
import ruggedmobile.sbf.MeasEpochCollector
import ruggedmobile.sbf.MeasEpochSink
import ruggedmobile.sbf.SbfBlockReader

/**
 * The workloads compared in the allocation, memory and speed tests. Each one reads every byte,
 * decodes every measurement epoch, and folds a few values into a checksum so the JIT cannot
 * drop any of the work.
 */
object Pipelines {
    enum class Stage { FRAMING, MEASUREMENTS }

    /** Consumer for the new library: reads every channel and every valid set, allocation-free. */
    class NewSink : MeasEpochSink {
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

    /** The same consumer for the original library. */
    class RefSink : (sbf.MeasEpoch) -> Unit {
        var epochs = 0L
        var checksum = 0.0

        override fun invoke(epoch: sbf.MeasEpoch) {
            epochs++
            var acc = epoch.towMs.toDouble()
            for (ch in 0 until epoch.nbrElements) {
                val c = epoch.channelData[ch]
                for (ant in c.measSet) for (m in ant) {
                    if (m.isValid) acc += m.prM + m.cn0DbHz + m.lockTimeMs
                }
            }
            checksum += acc
        }
    }

    /** New library over [source]; returns a checksum. */
    fun runNew(source: ruggedmobile.sbf.ByteSource, stage: Stage, sink: NewSink = NewSink()): Double {
        val reader = SbfBlockReader(source)
        if (stage == Stage.FRAMING) {
            var acc = 0L
            while (true) {
                val b = reader.next() ?: break
                acc += b.length + b.number
            }
            return acc.toDouble()
        }
        val collector = MeasEpochCollector(reader, MeasEpochCollector.ALL, sink)
        while (true) {
            val b = reader.next() ?: break
            collector.add(b)
        }
        collector.flush()
        return sink.checksum + sink.epochs
    }

    /** Original library over [source], used the way sbf2asc uses it; returns a checksum. */
    fun runRef(source: sbf.ByteSource, stage: Stage, sink: RefSink = RefSink()): Double {
        val reader = sbf.SbfBlockReader(source)
        if (stage == Stage.FRAMING) {
            var acc = 0L
            while (true) {
                val b = reader.next() ?: break
                acc += b.length + b.number
            }
            return acc.toDouble()
        }
        val collector = sbf.MeasEpochCollector(sbf.MeasEpochCollector.ALL)
        val lookahead = sbf.BlockLookahead { reader.peek() }
        while (true) {
            val b = reader.next() ?: break
            collector.add(b, lookahead, sink)
        }
        collector.flush(sink)
        return sink.checksum + sink.epochs
    }
}
