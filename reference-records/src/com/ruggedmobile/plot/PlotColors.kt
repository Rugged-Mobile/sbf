// Test-only stand-in, NOT part of the copied sources: the properties SbfRecords.kt reads in
// PvtMode.color(). The real class lives in gnss-plot's `plot` module.
package com.ruggedmobile.plot

import androidx.compose.ui.graphics.Color

class PlotColors(
    val good: Color,
    val warning: Color,
    val serious: Color,
    val critical: Color,
    val textSecondary: Color,
)
