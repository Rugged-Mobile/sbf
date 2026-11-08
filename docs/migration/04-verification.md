# 4. Verification

The goal: confidence that the new library **does everything the original does, with the same
results**, while using less memory, keeping memory stable, and never running slower. Every
check below compares the new library with the original running **in the same JVM on the same
bytes**. The original sources are in the `reference` module, unmodified.

## Test suites

| Suite | Module | What it proves |
|---|---|---|
| Unit tests | `sbf/test` (JVM + Android) | The original unit tests ported to the new API, plus tests for new code: slicing CRC, zero-copy reader (current block intact while `peek()` compacts the buffer), `MeasEpoch` clear/copy, wavelength table |
| `TablesParityTest` | `verify` | The reimplemented pure functions equal the original over their whole domain: wavelength (signal −10..300 × fn −12..12, bit-exact), SVID mapping, block classification (all 8,192 numbers), CRC (2,000 random ranges), `EpochFilter` (4 configurations × 2 × 508 TOWs × 3 WNc), every block accessor at every offset including out of range |
| `ParityTest` | `verify` | **Real logs**: block stream and every decoded epoch field identical, bit for bit. Every log in the data folder, file mode and live mode, `MEAS3`-only and `MEAS_EPOCH`-only, 4 epoch filters (each checked to select part of the log), minimum buffer with 1,000-byte reads |
| `FuzzParityTest` | `verify` | **Damaged input**: 40 seeds × 3 logs × 2 modes of stream damage (bit flips, dropped/duplicated/inserted runs, truncation, injected `$`), plus 40 seeds × 3 logs × 2 modes of payload damage with the CRC recomputed, so corrupt contents reach the decoders. Both libraries must agree, or fail identically |
| `MemoryTest` | `verify` | Exact steady-state allocation per pass, retained heap compared with the original, and a long replay with no heap growth and no GC |
| `SpeedTest` | `verify` | Median time of the new library ≤ the original's, framing and full decoding, Meas3 and legacy logs |

## How parity is compared (`Lockstep`)

Both libraries read the same input one block at a time, in one loop:

1. Each side reads a block. The **bytes, length and reader position** must be equal.
2. Both sides apply the same `EpochFilter` and must reach the same decision.
3. The block goes to both collectors, the original first. Its epoch callback takes a full
   snapshot of each epoch (its epoch object is reused), and the new collector's callback compares
   the snapshot with its own epoch: the header, then for every used channel `prn`/`fnPlus8`/
   `channel` and all 24 sets × 16 fields. Doubles and floats are compared as raw bits, so even
   −0.0, NaN payloads and the last ulp count.
4. Both must produce the same number of epochs at the same block, and the same CRC error count
   at the end.
5. If either side throws, both must throw the same exception type at the same block.

Live-mode runs have no look-ahead and call `flush()` at the end, like a serial or TCP consumer.

## How memory is measured

- **Allocation**: `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` counts the
  bytes the test thread allocates, exactly (it reads the TLAB counters). Each workload runs
  1 pass and 6 passes of the same data through **one** reader and collector (`LoopingSource`).
  `(alloc(6) − alloc(1)) / 5` is the steady-state cost per pass, with the one-time setup removed.
  Threshold: **< 4 KB per pass**. Any per-block or per-epoch allocation would show as megabytes.
  The test also checks that the original allocates at least half the input size per pass, so the
  measurement can tell the two apart.
- **Retained heap**: heap in use after repeated full GCs, before and after building and running
  a reader and collector that are kept reachable. Must be lower than the original's.
- **Stability (soak)**: replays the Meas3 log 40 times (about 500 MB) through one reader and
  collector. Over the replay: allocation < 64 KB, **0 garbage collections**, and heap after GC
  grows by less than 1 MB. Raise `-Dsbf.soak.passes` for longer runs.

## How speed is measured

`SpeedTest` (the gate) and the `bench` app (the report) use the same method:

- The input is loaded into memory first, so disk I/O is not timed.
- The libraries **alternate** (new, original, new, original, ...), so JIT state, CPU boost and
  background noise affect both equally.
- 3 warm-up rounds, then 7 timed rounds (configurable). **Medians** are compared.
- Each run computes a checksum over every decoded epoch, the same function on both sides, and
  the two must be equal. The JIT cannot remove work, and neither side can skip any.
- Gate: `new median ≤ original median`, for framing only and for full measurement decoding.

## Running

From the project root:

```bash
./kotlin test -m sbf
```

```bash
./kotlin test -m verify
```

```bash
./kotlin run -m bench -- --csv bench-results/latest.csv
```

The parity suite reads about 1.2 GB of logs in two modes and takes about 1.5 minutes on the
reference machine. The memory and speed tests take about 20 seconds.

## What is not covered

- The 1.1 GB `SupP1851.26_` is left out of the default parity run to keep it short; it is
  included in the benchmark. Run `-Dsbf.parity.maxBytes=2000000000` to add it to the parity run.
- Block types in neither the logs nor the unit tests (SISMeas, GenMeasEpoch, MeasFullRange, and
  the Meas3 extension blocks other than those in `supp1001`) are covered only by the fuzz tests
  (damaged real blocks) and code review, as in the original project. A recording that contains
  them, dropped into the data folder, is picked up by `ParityTest` automatically.
- Android: the unit tests run on the Android target (local JVM tests). The allocation and speed
  figures are JVM (HotSpot) figures. ART has different JIT/GC behaviour, but code that allocates
  nothing per block allocates nothing on ART either.
