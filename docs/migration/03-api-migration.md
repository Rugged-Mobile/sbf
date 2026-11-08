# 3. API migration

Package `sbf` → **`ruggedmobile.sbf`**. Constants objects (`BlockNumber`, `Dnu`, `Svid`,
`SignalType`, `MeasFlag`, `CommonFlag`, `MeasLimits`, `Meas3System`, `SbfLimits`) keep their
names and values. Only types whose shape changed are listed here.

## Quick reference

| Original (`sbf`) | New (`ruggedmobile.sbf`) |
|---|---|
| `SbfBlock(bytes)` | `SbfBlock().wrap(bytes, offset = 0, length = bytes.size)`. The view can be reused with another `wrap` |
| `block.bytes` (exactly the block) | `block.bytes`, `block.offset`, `block.length` (a window), or `block.toByteArray()` / `block.copyTo(dest)` |
| `reader.next()`: new object per block, valid forever | `reader.next()`: **same object every call**, valid until the next `next()` |
| `reader.peek()` and `next()` return the same object | `peek()` and `next()` return two different view objects with the same bytes |
| `BlockLookahead { reader.peek() }` | pass `reader` itself (`SbfBlockReader` is a `BlockLookahead`) |
| `MeasEpochCollector(enabledTypes)` | `MeasEpochCollector(lookahead, enabledTypes = ALL, sink)` |
| `collector.add(block, lookahead) { epoch -> }` | `collector.add(block)` (sink and look-ahead given once) |
| `collector.flush { epoch -> }` | `collector.flush()` |
| `epoch.nbrElements` | `epoch.channelCount` |
| `epoch.channels` / `epoch.channelData[i]` | channel index `ch` in `0 until channelCount` |
| `chan.prn`, `chan.fnPlus8`, `chan.channel` | `epoch.prn(ch)`, `epoch.fnPlus8(ch)`, `epoch.channel(ch)` |
| `chan.measSet[ant][slot]` | set index `s = MeasEpoch.setIndex(ch, ant, slot)` |
| `m.prM`, `m.lCycles`, `m.cn0DbHz`, ... | `epoch.prM(s)`, `epoch.lCycles(s)`, `epoch.cn0DbHz(s)`, ... |
| `m.isValid` | `epoch.isValid(s)` |
| `epoch.rxTowStatus` (`enum RxTowStatus`) | `epoch.rxTowStatus` (`Int`, constants in `object RxTowStatus`, same order) |
| (none) | `epoch.copyFrom(other)` keeps a copy without allocating |
| (none) | `ByteArraySource(bytes)` with `rewind()`; `EpochFilter.includes(tow, wnc)` |

All accessor values are the original field values: same type for `Double`/`Float`/`Int` fields,
and `Long` for `pllTimerMs` and `lockTimeMs`.

## Reading a file

Before:

```kotlin
import sbf.*

val reader = SbfBlockReader(File("log.sbf").inputStream().buffered().asByteSource())
val collector = MeasEpochCollector()
while (true) {
    val block = reader.next() ?: break
    collector.add(block, { reader.peek() }) { epoch ->
        for (ch in epoch.channels) {
            val l1 = ch.measSet[0].firstOrNull { it.isValid && it.signalType == SignalType.GPS_L1CA }
            if (l1 != null) println("${epoch.towMs} PRN ${ch.prn} PR ${l1.prM}")
        }
    }
}
```

After:

```kotlin
import ruggedmobile.sbf.*

val reader = SbfBlockReader(File("log.sbf").inputStream().asByteSource())
val collector = MeasEpochCollector(reader) { e ->
    for (ch in 0 until e.channelCount) {
        val first = MeasEpoch.setIndex(ch, antenna = 0, slot = 0)
        for (s in first until first + MeasLimits.SIGNALS_PER_SATELLITE) {
            if (e.isValid(s) && e.signalType(s) == SignalType.GPS_L1CA) {
                println("${e.towMs} PRN ${e.prn(ch)} PR ${e.prM(s)}")
                break
            }
        }
    }
}
while (true) collector.add(reader.next() ?: break)
collector.flush()
```

The reader already buffers, so `.buffered()` is not needed. The original example also called
`firstOrNull { ... }` on a list for each channel; the loop above does the same scan with no
allocation.

## Live stream (serial, USB, TCP)

```kotlin
val reader = SbfBlockReader(socket.getInputStream().asByteSource())
val collector = MeasEpochCollector(lookahead = null) { e -> publish(e) }
while (running) collector.add(reader.next() ?: break)
collector.flush()
```

Without a look-ahead the collector predicts the end of an epoch from the set of block kinds seen
in the previous one, as before (`predictEndOfEpoch`, default `true`).

## With an epoch filter (as sbf2asc does)

```kotlin
val filter = EpochFilter(firstEpochMs = first, lastEpochMs = last, intervalMs = 1000)
while (true) {
    val b = reader.next() ?: break
    if (filter.includes(b)) collector.add(b)
}
```

## Keeping data beyond the callback or the next block

The new library reuses objects. If you need data later:

| You need | Do |
|---|---|
| a block's bytes after the next `reader.next()` | `block.copyTo(myBuffer)` (no allocation if you reuse `myBuffer`) or `block.toByteArray()` |
| an epoch after the callback returns | `myEpoch.copyFrom(epoch)` into a `MeasEpoch()` you own (allocate it once, reuse it) |
| a few values | read them into your own fields or arrays inside the callback |

## Non-measurement blocks

As before, any block can be read with the accessors at SBF Reference Guide offsets. For example,
PVTGeodetic v2:

```kotlin
when (b.number) {
    BlockNumber.PVT_GEODETIC_2 -> {
        val lat = b.f64(16); val lon = b.f64(24); val h = b.f64(32)
        val nrSv = b.u8(74)
    }
}
```

These reads go straight into the reader's buffer, with no copy and no allocation.
