# reference-records

A verbatim copy of the SBF block decoders from gnss-plot, taken from
`libs/gnss-plot/plot-sbf/src/main/kotlin/com/ruggedmobile/plot/sbf/` at commit `85cf8cf`
(the files last changed in `7c5bbb6`):

| File | Copied from |
|---|---|
| `src/com/ruggedmobile/plot/sbf/SbfRecords.kt` | `SbfRecords.kt`, unchanged |
| `src/com/ruggedmobile/plot/sbf/Wgs84.kt` | `Wgs84.kt`, unchanged (used by `BaseStationInfo.geodetic`) |

They are compiled against the original `sbf` library in the `reference` module, exactly as in
gnss-plot. Three small stand-ins let them compile without Compose or the rest of gnss-plot. Each
says so in a header comment:

| Stand-in | Why |
|---|---|
| `androidx/compose/ui/graphics/Color.kt` | `SbfRecords.kt` imports Compose's `Color` for `PvtMode.color()` |
| `com/ruggedmobile/plot/PlotColors.kt` | the five colour properties `PvtMode.color()` reads |
| `com/ruggedmobile/plot/sbf/GeodeticPoint.kt` | declared in gnss-plot's `GnssLog.kt`; the declaration is identical |

`verify` compares the new `ruggedmobile.sbf` decoders (`PvtBlocks.kt`, `SatDirections.kt`) with
these on every real log and on damaged blocks.

**Do not edit the copied files.** If gnss-plot's decoders change, copy them again and re-run
`verify`.
