package ruggedmobile.sbf

/**
 * A view of one SBF block: the `length` bytes at `offset` in [bytes], header included, so
 * field offsets match the SBF Reference Guide and `sbfdef.h`.
 *
 * A view does not own its bytes. [SbfBlockReader] hands out the same view object for every
 * block, pointing into its read buffer, so reading a block costs no allocation and no copy.
 * A view is valid until the next call to [SbfBlockReader.next]; use [copyTo] / [toByteArray]
 * to keep the bytes longer. To view your own bytes, use [wrap].
 *
 * All multi-byte fields are little-endian. Offsets are relative to the block start. Reads
 * outside the block return 0 instead of throwing: the C code reads fixed-size structs whose
 * unused tail is stale buffer memory, and a zero is the safest stand-in for that.
 */
class SbfBlock {
    /** Backing array; the block occupies `[offset, offset + length)`. */
    var bytes: ByteArray = EMPTY
        private set

    var offset: Int = 0
        private set

    var length: Int = 0
        private set

    /** Points this view at `bytes[offset until offset + length]` and returns it. */
    fun wrap(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): SbfBlock {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "range $offset+$length outside array of ${bytes.size}"
        }
        bind(bytes, offset, length)
        return this
    }

    internal fun bind(bytes: ByteArray, offset: Int, length: Int) {
        this.bytes = bytes
        this.offset = offset
        this.length = length
    }

    /** Used by the reader when it moves the block inside its buffer. */
    internal fun moveTo(offset: Int) {
        this.offset = offset
    }

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

    fun u8(off: Int): Int = rdU8(bytes, offset, length, off)
    fun i8(off: Int): Int = if (off >= 0 && off < length) bytes[offset + off].toInt() else 0
    fun u16(off: Int): Int = rdU16(bytes, offset, length, off)
    fun i16(off: Int): Int = u16(off).toShort().toInt()
    fun u32(off: Int): Long = rdU32(bytes, offset, length, off)

    /** A 32-bit field as raw bits, for bit-field extraction. */
    fun i32(off: Int): Int = rdI32(bytes, offset, length, off)
    fun f32(off: Int): Float = Float.fromBits(rdI32(bytes, offset, length, off))
    fun f64(off: Int): Double = Double.fromBits(rdF64Bits(bytes, offset, length, off))

    /** Copies the block's bytes into [dest] at [destOffset]. */
    fun copyTo(dest: ByteArray, destOffset: Int = 0) {
        bytes.copyInto(dest, destOffset, offset, offset + length)
    }

    /** A new array holding a copy of the block. */
    fun toByteArray(): ByteArray = bytes.copyOfRange(offset, offset + length)

    override fun toString(): String = "SbfBlock(number=$number, revision=$revision, length=$length, tow=$tow, wnc=$wnc)"

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
