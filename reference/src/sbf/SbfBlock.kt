package sbf

/**
 * One validated SBF block. [bytes] holds exactly `Length` bytes, header included, so field
 * offsets match the SBF Reference Guide and `sbfdef.h`.
 *
 * All multi-byte fields are little-endian. Reads past the end of the block return 0 instead
 * of throwing: the C code reads fixed-size structs whose unused tail is stale buffer
 * memory, and a zero is the safest stand-in for that.
 */
class SbfBlock(val bytes: ByteArray) {
    val length: Int get() = bytes.size

    val id: Int get() = u16(4)

    /** Bits 0-12 of the ID. */
    val number: Int get() = id and 0x1FFF

    /** Bits 13-15 of the ID. */
    val revision: Int get() = id ushr 13

    /** Time of week in ms, [Dnu.U32] if unknown. */
    val tow: Long get() = u32(8)

    /** Continuous week number, [Dnu.U16] if unknown. */
    val wnc: Int get() = u16(12)

    /** `IsTimeValid()`: neither TOW nor WNc is set to its do-not-use value. */
    val isTimeValid: Boolean get() = tow != Dnu.U32 && wnc != Dnu.U16

    fun u8(off: Int): Int = bytes.u8(off)
    fun i8(off: Int): Int = bytes.i8(off)
    fun u16(off: Int): Int = bytes.u16(off)
    fun i16(off: Int): Int = bytes.i16(off)
    fun u32(off: Int): Long = bytes.u32(off)

    /** A 32-bit field as raw bits, for bit-field extraction. */
    fun i32(off: Int): Int = bytes.i32(off)
    fun f32(off: Int): Float = bytes.f32(off)
    fun f64(off: Int): Double = bytes.f64(off)
}

internal fun ByteArray.u8(off: Int): Int = if (off in indices) this[off].toInt() and 0xFF else 0
internal fun ByteArray.i8(off: Int): Int = if (off in indices) this[off].toInt() else 0
internal fun ByteArray.u16(off: Int): Int = u8(off) or (u8(off + 1) shl 8)
internal fun ByteArray.i16(off: Int): Int = u16(off).toShort().toInt()
internal fun ByteArray.i32(off: Int): Int = u16(off) or (u16(off + 2) shl 16)
internal fun ByteArray.u32(off: Int): Long = i32(off).toLong() and 0xFFFFFFFFL
internal fun ByteArray.f32(off: Int): Float = Float.fromBits(i32(off))
internal fun ByteArray.f64(off: Int): Double = Double.fromBits(u32(off) or (u32(off + 4) shl 32))
