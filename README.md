# sbf

A Kotlin Multiplatform (JVM + Android) library for Septentrio Binary Format (SBF) receiver
data. It provides block framing and CRC, stream resynchronisation, all measurement decoders
(MeasEpoch, GenMeasEpoch, SISMeas, MeasExtra, MeasFullRange, Meas3Ranges/Doppler/CN0HiRes/PP/MP),
epoch assembly and time filtering. It also decodes the position and status blocks that monitoring
apps plot: PVTGeodetic, PVTCartesian, PosCovGeodetic/Cartesian, DOP, AttEuler, BaseStation,
DiffCorrIn, ChannelStatus and SatVisibility.

It is a data-oriented rewrite of the `sbf` library in `demos/SBF/ns/sbf2asc-kotlin`, built with
the Kotlin Toolchain:

- **Same results:** bit-identical to the original on every test log and on fuzzed input.
- **No allocation per block or per epoch**, so long-running apps keep a flat heap and see no GC
  from this library.
- **About 3× faster** than the original.

Details: [docs/migration](docs/migration/README.md).

```kotlin
import ruggedmobile.sbf.*

val reader = SbfBlockReader(File("log.sbf").inputStream().asByteSource())
val collector = MeasEpochCollector(reader) { e ->
    for (ch in 0 until e.channelCount) {
        val s = MeasEpoch.setIndex(ch, antenna = 0, slot = 0)
        if (e.isValid(s)) println("${e.towMs} PRN ${e.prn(ch)} PR ${e.prM(s)}")
    }
}
while (true) collector.add(reader.next() ?: break)
collector.flush()
```

Objects are reused: the block from `next()` is valid until the next `next()`, and the epoch is
valid during the callback. Copy with `SbfBlock.copyTo` / `MeasEpoch.copyFrom` if you need data
longer.

## Modules

| Module | Purpose |
|---|---|
| `sbf` | the library (`ruggedmobile.sbf`) |
| `testkit` | SBF block encoder for tests |
| `reference` | unmodified copy of the original library, used as the test baseline |
| `reference-records` | unmodified copy of gnss-plot's PVT/status decoders, used as the test baseline |
| `verify` | parity, allocation, memory and speed tests against `reference` |
| `bench` | throughput/allocation benchmark app |

## Build

```bash
./kotlin build
```

```bash
./kotlin test
```

```bash
./kotlin run -m bench
```

Logs for `verify`/`bench`: `SBF_DATA_DIR`, default `../../../demos/SBF/data`.

## Use from another project

Publish to Maven local:

```bash
./kotlin publish mavenLocal -m sbf
```

Then, in the consumer's `module.yaml`:

```yaml
repositories:
  - mavenLocal
dependencies:
  - za.co.ruggedmobile:sbf:0.1.0
```
