# 2. Data-oriented design

The original port mirrors the C structs as object graphs and copies data freely. That is easy
to read, but in a long-running app it produces garbage all the time, and the object graphs
scatter data that is always processed together. The new design keeps **exactly the same
algorithms and arithmetic** but changes how data is stored and moved:

1. **Data is stored as flat columns of primitives** (struct of arrays), not as graphs of small
   objects.
2. **Nothing is allocated on the hot path.** All memory is allocated once, or grown once to the
   largest size seen, and then reused.
3. **Bytes are viewed, not copied**, unless they must outlive the read buffer.
4. **Work is limited to the data actually in use** (dirty ranges instead of full clears,
   lookup tables instead of branch chains).

## Where the original allocates

Measured with the JVM's per-thread allocation counter (see [05-results.md](05-results.md)):

| Source | Original | New |
|---|---|---|
| `SbfBlockReader.scan` | `copyOfRange` per block, so the whole input is copied once into garbage | view into the read buffer, no copy |
| `LegacyMeasDecoder.decode*` | `Obs()` + `Type1Ref()` per decoded block | decoder fields, decoder created once |
| `decodeSisMeasType2` | `IntArray(0)` per unknown-constellation sub-block | shared constant |
| `Meas3Decoder.decode` | `Extensions(...)` per antenna per epoch | cursor fields, reset per antenna |
| `MeasEpochCollector.add(block, lookahead, onEpoch)` | capturing lambdas are usually allocated per call by the caller | sink and look-ahead fixed at construction |
| `MeasEpoch` | 100 `MeasChannel` + 2,400 `MeasSet` objects + 300 inner arrays | 19 primitive arrays |
| `Meas3RefEpoch` (× 3 antennas) | 7,168 `MeasSet` objects each, 21,504 in total | 6 primitive arrays each |

## Components

### `SbfBlock`: a view instead of a copy

`SbfBlock` is now a *flyweight view*: `(bytes, offset, length)` plus the same little-endian
accessors (`u8`, `u16`, `u32`, `i32`, `f32`, `f64`, ...) at the same SBF offsets. It does not own
its bytes. The bounds rule is unchanged: a read outside the block returns 0 and never throws.
Each multi-byte read now checks its whole range once and reads the bytes directly. Only a read
that straddles the block edge falls back to byte-by-byte checks, which gives exactly the old
semantics, including 0 for out-of-range bytes.

### `SbfBlockReader`: zero-copy, fixed buffer

- `next()` returns **the same `SbfBlock` object** every time, pointed at the block inside the
  reader's buffer, and `peek()` returns a second one. No per-block allocation.
- The block returned by `next()` stays valid until the next `next()`, **even across `peek()`**.
  When the reader has to compact its buffer to read further ahead, it first moves the current
  block to the front and updates the view (`compact()`). This is why the buffer is at least
  2 × 65,532 bytes: the largest current block plus the largest candidate block.
- The search for the next `$` scans the bytes already buffered in a tight loop, instead of
  calling the refill check for every byte.
- The buffer is allocated once and never grows, so the reader's memory use is fixed.
- The resynchronisation rules, CRC counting and `position` are unchanged. The block stream is
  byte-identical to the original's, which the tests check.

### `Crc16`: slicing-by-8

The CRC is the main cost of framing. The original processes one byte per step, and each step
depends on the previous one. The new code uses 8 tables of 256 entries (8 KB, built once). Table
`k` holds the CRC of a byte followed by `k` zero bytes, so because the CRC is linear, 8 input
bytes are folded with 8 independent lookups and XORs. The result is identical. A byte-wise
implementation is kept as the specification, and the tests compare both, plus an independent
bit-wise CRC, for every length and alignment.

### `MeasEpoch`: struct of arrays

One epoch is a set of columns:

- **Header** (`towMs`, `wnc`, `rxTowStatus`, ...): plain properties.
- **Channel columns** (100 entries): `prn`, `fnPlus8`, `channel`.
- **Measurement-set columns** (2,400 entries = 100 channels × 3 antennas × 8 slots): one array per
  `MeasSet` field (`prM`, `lCycles`, `dopplerHz`, `cn0DbHz`, `flags`, ...).

A set is addressed by `setIndex(ch, antenna, slot) = (ch·3 + antenna)·8 + slot`. A channel's 24
sets are contiguous, so iterating a channel, or one field across all channels, walks memory
sequentially.

Each column uses the narrowest type that holds **every value the decoders can produce**. The
public accessors widen the values back, so callers see the same values as the original fields:

| Field | Original | Column | Why it is enough |
|---|---|---|---|
| `pllTimerMs` | `Long` | `IntArray` | at most 65,534 s × 1000 = 65,534,000 ms |
| `lockTimeMs` | `Long` (uint32) | `IntArray` (raw bits) | uint32 arithmetic in C; read back as `toLong() and 0xFFFFFFFF` |
| `signalType`, `flags`, `lockCount`, `rawCn0DbHz`, `info` | `Int` | `ByteArray` (unsigned) | values 0..255 |
| `mpMm`, `smoothingCorrMm` | `Int` | `ShortArray` | i16 on the wire, or ±10,240 mm in Meas3MP |
| `carrierMp1_512c` | `Int` | `ByteArray` (signed) | i8 on the wire, or -128..127 in Meas3MP |

That makes the epoch about 120 KB, against roughly 250 KB of objects in the original. More
importantly, the 2,400 object headers, 2,700 references and pointer chasing are gone.

**Dirty-range clearing.** The C code (and the original port) clears the *whole* epoch before
every decode: 2,400 sets × 16 fields. The new epoch tracks `dirtyChannels`, the high-water
mark of channels written since the last clear, and clears only that range with `fill`.
Every write path calls `touch(ch)` first. This includes channels that are written but not
counted in `channelCount`, such as a last Type-1 sub-block with no valid measurement. A
`MeasExtra` lookup can still find such a channel, so the result is exactly that of a full
clear.

### `Meas3RefEpoch`: keep only what is read

The reference epoch held a full `MeasSet` for each of 7 constellations × 64 satellites × 16
signals. Delta decoding (`MasterDelta*`, `SlaveDelta`) reads only **five** fields of those sets:
`prM`, `lCycles`, `cn0DbHz`, `flags` and `pllTimerMs`. The other eleven were written by
`copyFrom` but never read. The new reference epoch stores only the five as columns, plus
`sigIdx` (a `ByteArray`, since values are below 16), `slaveSigMask`, `prRate64mmS` and the
header copy (one `ByteArray` of 7 × 64). Dropping fields that are never read cannot change any
result.

Clearing happens at every reference epoch, which with a 1 ms interval code means every
epoch. It touches only the constellations written since the last clear (`dirtySystems`
bitmask). A typical GPS + GLONASS + Galileo + BeiDou log clears 4 of 7 ranges, and only the
arrays, never objects.

### `MeasEpochCollector`: reusable block slots

The collector must keep the blocks of one epoch until the epoch is complete, and the reader
reuses its buffer, so these blocks are copied. Each block kind has a `BlockSlot`: a byte array
that grows (in powers of two, from 512 bytes up to 64 KiB) to the largest block of that kind,
plus a view. After the first few epochs the slots never grow again. The sink
(`MeasEpochSink`) and the look-ahead are fixed when the collector is constructed, so calling
`add(block)` needs no lambda.

### Decoders

`LegacyMeasDecoder` (MeasEpoch v2, GenMeasEpoch, SISMeas, MeasExtra, MeasFullRange) and
`Meas3Decoder` are line-by-line ports of the original Kotlin. Every arithmetic expression is
kept in the same order with the same types, because floating-point results depend on it.
Results go straight into the epoch columns. Scratch state (`Obs`, `Type1Ref`, `MasterInfo`,
extension cursors) is now stored in decoder fields.

`SignalType.wavelength` is a table lookup: 256 entries, plus 2 × 14 for GLONASS FDMA by
frequency number. The tables are filled at start-up by the original `when`, kept as the single
definition, so the values are the same doubles. A test compares them with the original for
every signal −10..300 and frequency number −12..12.

### `LockTimes`

`LockTimes` follows the same algorithm, keyed by satellite (fix #5 of the original port). It
uses two `IntArray`s, and the keys of a PRN are laid out in the same `[antenna][slot]` order as
the sets of a channel, so the inner loop walks both arrays sequentially.

## Memory model, summarised

| Object | Allocated | Size |
|---|---|---|
| `SbfBlockReader` | once | `max(bufferSize, 131,064)` bytes (default 1 MiB) |
| `MeasEpochCollector` | once | epoch about 120 KB, 3 reference epochs about 190 KB each, lock-time history about 54 KB, block slots up to the largest block of each kind |
| per block / per epoch | **nothing** | |

Total steady-state memory for a reader and collector is about 1.8 MB, and it does not change
with the length of the stream.
