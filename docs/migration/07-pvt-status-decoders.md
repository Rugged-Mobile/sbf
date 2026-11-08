# 7. PVT and status decoders (from gnss-plot)

gnss-plot's `plot-sbf` module decoded the position, quality and correction blocks its plots use
(`SbfRecords.kt`). Those decoders are SBF knowledge, not plotting, so they now live in this
library, next to the measurement decoders, and the data-oriented rules apply to them too.
gnss-plot is not modified; its `SbfRecords.kt` is copied unchanged into the `reference-records`
module as the test baseline.

## Blocks

| Block | Number | New API (`ruggedmobile.sbf`) | Was (`SbfRecords`) |
|---|---|---|---|
| PVTGeodetic v2 | 4007 | `PvtGeodetic.*(block)` | `pvtGeodetic(b): PvtGeodetic?` |
| PVTCartesian v2 | 4006 | `PvtCartesian.*(block)` | `pvtCartesian(b): PvtCartesian?` |
| PosCovGeodetic | 5906 | `PosCovGeodetic.*(block)` | `posCovGeodetic(b): PositionSigma?` |
| PosCovCartesian | 5905 | `PosCovCartesian.*(block)` | `posCovCartesian(b): CartesianSigma?` |
| DOP v2 | 4001 | `Dop.*(block)` | `dop(b): Dop?` |
| AttEuler | 5938 | `AttEuler.*(block)` | `attEuler(b): Attitude?` |
| BaseStation | 5949 | `BaseStation.*(block)` | `baseStation(b): BaseStationInfo?` |
| DiffCorrIn | 5919 | `DiffCorrIn.*(block)` | `diffCorrIn(b): CorrectionMessage?` |
| ChannelStatus | 4013 | `SatDirections.readChannelStatus(block)` | `channelStatus(b): List<SatDirection>` |
| SatVisibility | 4012 | `SatDirections.readSatVisibility(block)` | `satVisibility(b): List<SatDirection>` |

Also moved: the block numbers gnss-plot added (`PlotBlockNumber` → `BlockNumber.SAT_VISIBILITY`,
`CHANNEL_STATUS`, `POS_COV_GEODETIC`, `DIFF_CORR_IN`), `SbfBlock.gpsTimeS`, `SECONDS_PER_WEEK`
(→ `GpsTime.SECONDS_PER_WEEK`), and the code tables `PvtMode` (labels and quality ranks),
`Datums` (→ `Datum`) and `CorrectionSources` (→ `CorrectionSource`).

Not moved, because they belong to the plots: `PvtMode.color()` (Compose colours), and
`BaseStationInfo.geodetic` (WGS84 conversion; it stays with the recording model in gnss-plots).

## Design

**Field accessors instead of decoded objects.** The original built a data class per block
(PVTGeodetic: 19 fields, boxed in an object) even when the caller kept two of them. Each new
decoder is an `object` of functions that read one field each, straight from the `SbfBlock` view
into the reader's buffer:

```kotlin
// Before
val p = SbfRecords.pvtGeodetic(block) ?: return
log.add(p.timeS, p.latitudeRad, p.longitudeRad, p.heightM, p.mode)

// After: nothing is allocated, and only these five fields are read
if (block.number == BlockNumber.PVT_GEODETIC_2) {
    log.add(block.gpsTimeS, PvtGeodetic.latitudeRad(block), PvtGeodetic.longitudeRad(block),
        PvtGeodetic.heightM(block), PvtGeodetic.mode(block))
}
```

The accessors do not check the block number; the caller dispatches on `block.number` once,
which it does anyway to choose a decoder. Do-not-use values map exactly as before: NaN for
floating-point fields, -1 for counts and IDs, `Datum.UNKNOWN` for datum 255.

**`PvtMode` is an `Int` code, not an enum.** Codes are stored in `IntArray` columns by the
recording model, so an enum would be converted back and forth. `PvtMode.of(modeField)` takes
bits 0-3 and maps undefined values to `PvtMode.UNKNOWN` (-1), as `PvtMode.fromCode` did;
`label(code)` and `qualityRank(code)` replace the enum properties.

**Satellite directions as columns.** ChannelStatus and SatVisibility hold a variable list of
satellites. The original returned a new `List<SatDirection>` per block, and for ChannelStatus
also a `LinkedHashMap` to merge the channels of one satellite. `SatDirections` is one reusable
object with parallel arrays (`prn`, `azimuthDeg`, `elevationDeg`, `usedInPvt`, `rising`),
sized for the largest possible block (N is 8 bits), so it never grows. The merge uses a
PRN-indexed table that is reset after each block by visiting only the entries written.
Tri-state values (`Boolean?` before) are `YES` / `NO` / `UNKNOWN` bytes.

The ordering and merge rules are unchanged: a satellite seen on several channels appears once,
at its first channel's position, with its last channel's direction and rise/set state, and
"used" if any channel used it in the PVT.

## Verification

`RecordsParityTest` (module `verify`) compares the new decoders with the copied originals:

| Test | What it proves |
|---|---|
| `realLogs` | Every field of every block of the 10 types, bit for bit (floats as raw bits), on every log in the data folder. All 10 types must occur, so no decoder goes untested. |
| `damagedBlocks` | 40 seeds × up to 300 real blocks per type, each with random payload damage and truncated to a random length: same results, and the new decoders never throw. |
| `codeTables` | `PvtMode` codes, labels and ranks; `Datum` and `CorrectionSource` names for every value −1..300. |
| `decodersAllocateNothing` | Reading every field of every decoded block over a 16 MB log, replayed 6 times: no allocation per pass. |

Unit tests in `sbf/test` (`PvtBlocksTest`, `SatDirectionsTest`) cover each decoder on blocks built
with `testkit`, including do-not-use values, short rev 0 blocks and the ChannelStatus merge. They run on
JVM and Android.

Results (2026-11-08): 289,135 real blocks compared (PVTGeodetic 49,786, PosCovGeodetic 49,785,
DOP 37,813, ChannelStatus 37,812, AttEuler 37,812, DiffCorrIn 25,663, PVTCartesian 16,943,
PosCovCartesian 16,943, SatVisibility 15,665, BaseStation 923); 216,080 damaged blocks; all
identical, and 0 B allocated per pass.
