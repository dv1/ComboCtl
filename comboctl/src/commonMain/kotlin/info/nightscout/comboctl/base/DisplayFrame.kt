package info.nightscout.comboctl.base

const val DISPLAY_FRAME_WIDTH = 96
const val DISPLAY_FRAME_HEIGHT = 32

// One frame consists of 96x32 pixels, one bit
// per pixel (hence the division by 8 to get the
// number of bytes per frame.)
const val NUM_DISPLAY_FRAME_BYTES = DISPLAY_FRAME_WIDTH * DISPLAY_FRAME_HEIGHT / 8

/**
 * Class containing a 96x32 pixel black&white Combo display frame.
 *
 * These frames are sent by the Combo when it is operating
 * in the remote terminal (RT) mode.
 *
 * The pixels are stored in row-major order. One bit equals one
 * pixel. This means that one row consists of 96 / 8 = 12 bytes.
 *
 * The most significant bit (MSB) of the first byte is the pixel
 * at coordinates (x 0, y 0). The least significant bit (LSB) of
 * the first byte is the pixel at coordinates (x 7, y 0). The MSB
 * of the second byte is the pixel at (x 8, y 0). The MSB of the
 * first byte of the second row is the pixel at (x 96, y 1) etc.
 *
 * Note that this is not the layout of the pixels as transmitted
 * by the Combo. Rather, the pixels are rearranged in a layout
 * that is more commonly used and easier to work with.
 *
 * @param displayFrameBytes Bytes of the display frame to use.
 *        The list has to have exactly NUM_DISPLAY_FRAME_BYTES bytes.
 */
data class DisplayFrame(private val displayFrameBytes: List<Byte>) : Iterable<Byte> {
    /**
     * Number of display frame bytes.
     *
     * This mainly exists to make this class compatible with
     * code that operates on collections.
     */
    val size = NUM_DISPLAY_FRAME_BYTES

    init {
        require(displayFrameBytes.size == size)
    }

    /**
     * Returns the pixel at the given coordinates.
     *
     * @param x X coordinate. Valid range is 0..95 (inclusive).
     * @param y Y coordinate. Valid range is 0..31 (inclusive).
     * @return true if the pixel at these coordinates is set,
     *         false if it is cleared.
     */
    fun getPixelAt(x: Int, y: Int) =
        ((displayFrameBytes[(x + y * DISPLAY_FRAME_WIDTH) / 8].toPosInt() and (1 shl (7 - (x % 8)))) != 0)

    operator fun get(index: Int) = displayFrameBytes[index]

    override operator fun iterator() = displayFrameBytes.iterator()

    override fun toString() = displayFrameBytes.toHexString()
}

/**
 * Display frame filled with empty pixels. Useful for initializations.
 */
val NullDisplayFrame = DisplayFrame(List<Byte>(NUM_DISPLAY_FRAME_BYTES) { 0x00 })
