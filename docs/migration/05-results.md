# 5. Results

Measured on 2026-11-08: Intel Core i7-6850K (6 cores / 12 threads, 3.6 GHz), Windows 10, Java
HotSpot 21.0.9, G1 GC. Raw benchmark output: [bench-2026-11-08.csv](bench-2026-11-08.csv).

Confirmed with the Kotlin Toolchain 0.13.0 (`./kotlin test`): `sbf` passes 37/37 tests on
both JVM and Android, and `verify` passes 30/30 with none skipped (with the PVT/status decoders added). The Toolchain run reproduced
the parity, allocation, soak and retained-heap figures below, and gave speed-ups of
×3.35–3.74 for framing and ×2.86–3.63 for measurements. The benchmark table comes from the
`bench` app.

## Functional parity

All passed. Each epoch is compared field by field, bit for bit.

| Log | Content | Blocks | Epochs (file and live mode) |
|---|---|---:|---:|
| `all_blocks_0000.sbf` (0.2 MB) | many block types | 281 | 1 |
| `supp1001.26_` (13.6 MB) | Meas3Ranges + extensions | 108,281 | 1,278 |
| `HermanusRB31540.26_` (163 MB) | MeasEpoch v2 + MeasExtra | 606,719 | 15,664 |
| `20260903_RB3_…_0000.sbf` (181 MB) | MeasEpoch v2 + MeasExtra rev 3, dual antenna | 355,402 | 20,869 |
| `WobX5MF7520Ladder.sbf` (185 MB) | no measurement blocks | 104,823 | 0 |

Also identical: `MEAS3`-only and `MEAS_EPOCH`-only decoding; 4 epoch filters (decimation,
time-of-day window, absolute window, invalid-time rejection) × 2 modes; minimum buffer with
1,000-byte reads.

Fuzzing: 480 damaged inputs, each run in both modes.

| Kind | Runs | Epochs compared | CRC rejections | Crashes |
|---|---:|---:|---:|---:|
| Stream damage | 240 | 13,706 | 9,330 | 0 |
| Payload damage, valid CRC | 240 | 17,526 | 0 | 0 |

PVT/status decoders (added later, [07](07-pvt-status-decoders.md)): 289,135 real blocks of 10
types and 216,080 damaged blocks, all identical to gnss-plot's decoders; 0 B allocated per pass.

## Allocation (steady state, per pass over the data)

| Log | Stage | New | Original |
|---|---|---:|---:|
| `supp1001.26_` (13 MB) | framing | **0 B** | 17.4 MB |
| `supp1001.26_` (13 MB) | measurements | **0 B** | 17.4 MB |
| RB3 log (first 16 MB) | framing | **0 B** | 17.9 MB |
| RB3 log (first 16 MB) | measurements | **0 B** | 18.1 MB |

The original allocates about 1.1× the input size: a copy of every block, plus objects per epoch.
The new library allocates 1.0 MB once (the read buffer), plus 0.7 MB once for the collector.

## Long-running stability (soak)

39 replays of `supp1001.26_` through one reader and collector: 507 MB, 4,222,959 blocks,
51,120 epochs.

| Allocated during the replay | GCs | Heap after GC, start → end |
|---:|---:|---|
| 0 B (296 B in the Gradle-built run) | **0** | 22.06 MB → 22.06 MB |

## Retained heap (reader + collector after decoding)

| New | Original |
|---:|---:|
| **2.7 MB** | 4.5 MB |

Both include the 1 MiB read buffer. The difference comes mainly from the epoch and the three
Meas3 reference epochs, which in the original hold about 24,000 `MeasSet` objects.

## Throughput (median of 7 alternating runs, in-memory input)

| Log | MB | Framing: new | Framing: original | × | Measurements: new | Measurements: original | × |
|---|---:|---:|---:|---:|---:|---:|---:|
| `all_blocks_0000.sbf` | 0.2 | 248 MB/s | 149 MB/s | 1.66 | 416 MB/s | 124 MB/s | 3.35 |
| `supp1001.26_` | 13.0 | 965 MB/s | 298 MB/s | 3.24 | 324 MB/s | 106 MB/s | 3.06 |
| `HermanusRB31540.26_` | 155.4 | 1,133 MB/s | 365 MB/s | 3.10 | 307 MB/s | 107 MB/s | 2.86 |
| RB3 dual antenna | 172.3 | 1,140 MB/s | 371 MB/s | 3.07 | 304 MB/s | 117 MB/s | 2.59 |
| `WobX5MF7520Ladder.sbf` | 176.5 | 1,172 MB/s | 365 MB/s | 3.21 | 1,193 MB/s | 365 MB/s | 3.27 |
| `SupP1851.26_` | 1,072.8 | 1,200 MB/s | 371 MB/s | 3.24 | 262 MB/s | 88 MB/s | 2.99 |

From `./kotlin run -m bench -- --max-mb 2048` (default JVM heap, 16 GB on this machine).

*Framing* = reading and validating blocks. *Measurements* = framing + epoch assembly + full
decoding + a consumer that reads every valid set. The new library is faster on every log and
stage, typically about 3 times. The tiny `all_blocks` file is dominated by fixed setup: the
1 MiB buffer is allocated and zeroed once per run.

Where the time went:

- **CRC**: slicing-by-8 instead of one byte per step. Framing is about 3.5× faster.
- **No block copies**: the original copies each block and later collects the garbage.
- **Epoch clear**: only the channels in use are cleared, with `fill` on flat arrays, instead of
  2,400 objects × 16 fields per epoch. Meas3 reference epochs clear only the constellations in
  use, which can be every epoch.
- **Cache locality**: flat columns instead of chasing pointers through `MeasChannel` →
  `Array<Array<MeasSet>>` → `MeasSet`.

GC counts: over the 7 timed rounds, the original triggers up to 11 collections per log, and the
new library 0 or 1. A new-library run allocates only 1.7 MB, so its rare GC comes from garbage
the alternating original runs left in the same JVM. With a 3 GB heap, the same benchmark
counted 21 such collections during new-library runs on the 1.1 GB log. The soak test, which runs
the new library alone, shows 0 GCs.
