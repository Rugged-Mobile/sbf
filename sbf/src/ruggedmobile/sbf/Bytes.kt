package ruggedmobile.sbf

/*
 * Little-endian reads from a window `[base, base + len)` of a byte array.
 *
 * Offsets are relative to the window. Bytes outside the window read as 0, never throw: the C
 * code reads fixed-size structs whose unused tail is stale buffer memory, and 0 is the safest
 * stand-in (same rule as the original `SbfBlock`).
 *
 * Each multi-byte read checks the whole range once and then reads directly (fast path). Only
 * a read that straddles the window edge falls back to byte-by-byte checks, which gives exactly
 * the original per-byte semantics. Comparisons are written as `off <= len - n` so they cannot
 * overflow.
 */

@Suppress("NOTHING_TO_INLINE")
internal inline fun rdU8(b: ByteArray, base: Int, len: Int, off: Int): Int =
    if (off >= 0 && off < len) b[base + off].toInt() and 0xFF else 0

internal fun rdU16(b: ByteArray, base: Int, len: Int, off: Int): Int {
    if (off >= 0 && off <= len - 2) {
        val p = base + off
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    }
    return rdU8(b, base, len, off) or (rdU8(b, base, len, off + 1) shl 8)
}

internal fun rdI32(b: ByteArray, base: Int, len: Int, off: Int): Int {
    if (off >= 0 && off <= len - 4) {
        val p = base + off
        return (b[p].toInt() and 0xFF) or
            ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or
            (b[p + 3].toInt() shl 24)
    }
    return rdU16(b, base, len, off) or (rdU16(b, base, len, off + 2) shl 16)
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun rdU32(b: ByteArray, base: Int, len: Int, off: Int): Long =
    rdI32(b, base, len, off).toLong() and 0xFFFFFFFFL

internal fun rdF64Bits(b: ByteArray, base: Int, len: Int, off: Int): Long =
    rdU32(b, base, len, off) or (rdU32(b, base, len, off + 4) shl 32)
