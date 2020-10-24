package info.nightscout.comboctl.base

/**
 * Class for assembling RT_DISPLAY application layer packet rows to a complete display frame.
 *
 * RT_DISPLAY packets contain 1/4th of a frame. These subsets are referred to as "rows".
 * Since a frame contains 96x32 pixels, each row contains 96x8 pixels.
 *
 * This class assembles these rows into complete frames. To that end, it has to convert
 * the layout the pixels are arranged in into a more intuitive column-major bitmap layout.
 * The result is a [DisplayFrame] instance with all of the frame's pixels in row-major
 * layout. See the [DisplayFrame] documentation for more details about its layout.
 *
 * The class is designed for streaming use. This means that it can be continuously fed
 * the contents of RT_DISPLAY packets, and it will keep producing frames once it has
 * enough data to complete a frame. When it completed one, it returns the frame, and
 * wipes its internal row collection, allowing it to start from scratch to be able to
 * begin completing a new frame.
 *
 * If frame data with a different index is fed into the assembler before the frame completion
 * is fully done, it also resets itself. The purpose of the index is to define what frame
 * each row belongs to. That way, it is assured that rows of different frames cannot be
 * mixed up together. Without the index, if for some reason one RT_DISPLAY packet isn't
 * received, the assembler would assemble a frame incorrectly.
 *
 * In practice, it is not necessary to keep this in mind. Just feed data into the assembler
 * by calling its main function, [DisplayFrameAssembler.processRTDisplayPayload]. When that
 * function returns null, just keep going. When it returns a [DisplayFrame] instance, then
 * this is a complete frame that can be further processed / analyzed.
 *
 * Example:
 *
 * ```
 * val assembler = DisplayFrameAssembler()
 *
 * while (receivingPackets()) {
 *     val rtDisplayPayload = applicationLayer.parseRTDisplayPacket(packet)
 *
 *     val displayFrame = assembler.processRTDisplayPayload(
 *         rtDisplayPayload.index,
 *         rtDisplayPayload.row,
 *         rtDisplayPayload.pixels
 *     )
 *     if (displayFrame != null) {
 *         // Output the completed frame
 *     }
 * }
 * ```
 */
class DisplayFrameAssembler {
    private val rtDisplayFrameRows = mutableListOf<List<Byte>?>(null, null, null, null)
    private var currentIndex: Int? = null
    private var numRowsLeftUnset = 4

    /**
     * Main assembly function.
     *
     * This feeds RT_DISPLAY data into the assembler. The index is the RT_DISPLAY
     * index value. The row is a value in the 0-3 range, specifying what row this
     * is about. pixels is the byte list containing the pixel bytes from the packet.
     * This list must contain exactly 96 bytes, since the whole frame is made of
     * 384 bytes, and there are 4 rows, so each row contains 384 / 4 = 96 bytes.
     *
     * @param index RT_DISPLAY index value.
     * @param row Row number, in the 0-3 range (inclusive).
     * @param pixels RT_DIPLAY pixel bytes.
     * @return null if no frame could be completed yet. A DisplayFrame instance
     *         if the assembler had enough data to complete a frame.
     */
    fun processRTDisplayPayload(index: Int, row: Int, pixels: List<Byte>): DisplayFrame? {
        require(pixels.size == NUM_DISPLAY_FRAME_BYTES / 4)

        // Check if we got data from a different frame. If so, we have to throw
        // away any previously collected data, since it belongs to a previous frame.
        if (index != currentIndex) {
            reset()
            currentIndex = index
        }

        // If we actually are _adding_ a new row, decrement the numRowsLeftUnset
        // counter. That counter specifies how many row entries are still set to
        // null. Once the counter reaches zero, it means the rtDisplayFrameRows
        // list is fully populated, and we can complete the frame.
        if (rtDisplayFrameRows[row] == null)
            numRowsLeftUnset -= 1

        rtDisplayFrameRows[row] = pixels

        if (numRowsLeftUnset == 0) {
            val displayFrame = assembleDisplayFrame()
            currentIndex = null
            return displayFrame
        } else
            return null
    }

    /**
     * Main assembly function.
     *
     * This is an overloaded variant of [processRTDisplayPayload] that accepts
     * an [ApplicationLayer.RTDisplayPayload] instance instead of the individual
     * index, row, pixels arguments.
     */
    fun processRTDisplayPayload(rtDisplayPayload: ApplicationLayer.RTDisplayPayload): DisplayFrame? =
        processRTDisplayPayload(rtDisplayPayload.index, rtDisplayPayload.row, rtDisplayPayload.pixels)

    private fun reset() {
        rtDisplayFrameRows.fill(null)
        numRowsLeftUnset = 4
    }

    private fun assembleDisplayFrame(): DisplayFrame {
        val displayFrameBytes = MutableList<Byte>(NUM_DISPLAY_FRAME_BYTES) { 0x00 }

        // (Note: Display frame rows are not to be confused with pixel rows. See the
        // class description for details about the display frame rows.)
        // Pixels are stored in the RT_DISPLAY display frame rows in a column-major
        // order. Also, the rightmost column is actually stored first, and the leftmost
        // one last. And since each display frame row contains 1/4th of the entire display
        // frame, this means it contains 8 pixel rows. This in turn means that this
        // layout stores one byte per column. So, the first byte in the display frame row
        // contains the pixels from (x 95 y 0) to (x 95 y 7). The second byte contains
        // pixels from (x 94 y 0) to (x 94 y 7) etc. 
        for (row in 0 until 4) {
            val rtDisplayFrameRow = rtDisplayFrameRows[row]!!
            for (column in 0 until 96) {
                // Get the 8 pixels from the current column.
                // We invert the index by subtracting it from
                // 95, since, as described above, the first
                // byte actually contains the rightmost column.
                val byteWithColumnPixels = rtDisplayFrameRow.get(95 - column).toPosInt()
                // Scan the 8 pixels in the selected column.
                for (y in 0 until 8) {
                    // Isolate the current pixel.
                    val pixel = ((byteWithColumnPixels and (1 shl y)) != 0)

                    if (pixel) {
                        // Get the index of the location on our output video frame bytes
                        // (that's the destination, hence the "dest" naming).
                        val destBitIndex = column + (y + row * 8) * 96
                        val destByteIndex = destBitIndex / 8
                        // Get the index of the bit within the destination byte.
                        val bitIndexWithinDestByte = 7 - (destBitIndex % 8)

                        // Now set the pixel in the display frame by using bitwise OR.
                        var destByteValue = displayFrameBytes[destByteIndex].toPosInt()
                        destByteValue = destByteValue or (1 shl bitIndexWithinDestByte)
                        displayFrameBytes[destByteIndex] = destByteValue.toByte()
                    }
                }
            }
        }

        return DisplayFrame(displayFrameBytes)
    }
}
