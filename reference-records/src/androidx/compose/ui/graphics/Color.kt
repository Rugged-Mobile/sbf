// Test-only stand-in, NOT part of the copied sources: SbfRecords.kt imports Compose's Color for
// PvtMode.color(), which the parity tests never call. This lets the copy compile unmodified
// without pulling Compose into a JVM test module.
package androidx.compose.ui.graphics

class Color
