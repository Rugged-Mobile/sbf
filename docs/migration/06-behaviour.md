# 6. Behaviour

## Unchanged

Decoded behaviour is the original's, and the `verify` tests check it on real and damaged data:

- Block framing: the `$@` sync check, the `16 <= Length <= 65532` limits, the CRC, and
  byte-by-byte resynchronisation after a rejected candidate. `Length % 4` is still not enforced
  unless `requireLengthMultipleOf4 = true`.
- `position` (file offset after the last block from `next()`) and `crcErrors` (each byte range
  scanned once).
- Epoch assembly: end-of-epoch rules in file mode and live mode, `flush`, the
  `enabledTypes` selection, the "new epoch" test, and the SISMeas preference.
- All decoder arithmetic, including C quirks that are part of the format's reference behaviour:
  - reads past the end of a block return 0;
  - `MeasExtra`/`MeasFullRange` channel lookup inspects channel 0 even in an empty epoch;
  - signal lookup matches the first slot whose type equals the signal, including empty slots
    (type 0);
  - `1 shl satCnt` wraps at 32;
  - a failed Meas3 decode delivers an empty epoch;
  - and the others listed in the original README.
- `EpochFilter`: the same rules, including C's cast of an unknown TOW to −1.

## The original port's fixes to the C code, carried over

All ten "Fix vs C" items of the original port are kept, at the matching place in the new code.
The comments say *"Fix vs C (kept from the original port)"*.

| # | Fix | New location |
|---|---|---|
| 1 | SISMeas buffer cleared after each epoch | `MeasEpochCollector.processEpoch` |
| 2 | Meas3 MasterLong PR rate read at `10 + Cont` | `Meas3Decoder.decodeMaster` |
| 3, 4, 7, 8 | sbf2asc CLI fixes | not part of the library (CLI not migrated) |
| 5 | Lock-time history keyed by satellite | `LockTimes` |
| 6 | Both epochs delivered when a block closes one epoch and completes another | `MeasEpochCollector.add` |
| 9 | Bounds checks: reads, sub-block loops, 100-channel limit, `SBSize = 0`, 64-byte Meas3 header copy, `SlaveCnt + 1` clamp, signal index range | `Bytes.kt`, `LegacyMeasDecoder`, `Meas3Decoder` |
| 10 | Peeked blocks cached, so CRC errors are not counted twice | `SbfBlockReader` |

## What is different (by design)

| Topic | Original | New | Why |
|---|---|---|---|
| Block object lifetime | each `next()` returns a new `SbfBlock` that stays valid forever | `next()` returns the same view each time, valid until the next `next()`; `peek()` returns a second view | no per-block copy or allocation |
| `peek()` / `next()` identity | `next()` returns the very object `peek()` returned | different objects, same bytes | the current block and the peeked block must be valid at the same time |
| Epoch model | `MeasEpoch` → `MeasChannel` → `MeasSet` objects | columns plus index-based accessors | data-oriented layout, see [02-design.md](02-design.md) |
| `MeasEpoch` / `MeasSet` mutability | public `var`s, caller could modify | read-only accessors; setters are internal | the collector owns the epoch |
| Collector callbacks | lambda passed to each `add` / `flush` call | `MeasEpochSink` given once | avoids a lambda per call |
| `RxTowStatus` | enum | `Int` constants, same order (0..3) | primitives only in the epoch |
| Data beyond `channelCount` | readable through `channelData[i]` (stale content) | not part of the API | only used channels are defined |
| Meas3 reference epoch | full `MeasSet` per entry | only the five fields delta decoding reads | the other fields were never read |

## Thread safety

As before, none of these classes are thread-safe. Use one reader and one collector per stream,
on one thread, or hand off copies (`MeasEpoch.copyFrom`) to other threads.

## Known limits (unchanged)

- 100 logical channels, 3 antennas, 8 signal slots per satellite and antenna (`MeasLimits`).
- Meas3 format versions below 32 (the block's Reserved field).
