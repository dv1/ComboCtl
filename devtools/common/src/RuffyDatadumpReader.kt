package devtools.common

import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import java.io.BufferedInputStream
import java.io.IOException

private val logger = Logger.get("RuffyDatadumpReader")

internal fun Byte.toPosInt() = toInt() and 0xFF

/**
 * Reads binary data dumps produced by the datadump Ruffy fork.
 *
 * This fork can be found at https://github.com/dv1/ruffy/tree/datadumps.
 *
 * The produced data dumps use a very simple format: First comes a 32-bit
 * little endian unsigned integer, containing the number of bytes in the
 * following frame data. The most significant bit of that integer is not
 * part of the length (limiting frame data sizes to 2^31-1 bytes). Instead,
 * if it is set, it indicates that this is _outgoing_ data (that is: it
 * is sent by the client to the Combo). If it is not set, it indicates
 * _incoming_ data (that is: data sent by the Combo to the client). The
 * data dump interleaves these two types of frame data; they are added by
 * the aforementioned Ruffy fork as soon as they are encountered. For this
 * reason, it is important to be able to distinguish them.
 *
 * After that 32-bit integer, the actual frame data follows.
 *
 * Note that a frame data chunk does not necessarily have to contain
 * a full frame. Indeed, sometimes, the Combo sends partial frames,
 * particularly when the frames are bigger (for example, when transmitting
 * display data).
 *
 * @property inputStream The buffered input stream to read binary data from.
 */
class RuffyDatadumpReader(private val inputStream: BufferedInputStream) {
    data class FrameData(var frameData: List<Byte>, var isOutgoingData: Boolean)

    /**
     * Reads a chunk of frame data.
     *
     * @return The chunk of data read, along with info whether or not this
     *         is outgoing data, or null if either something went wrong of
     *         the end of file was reached.
     */
    fun readFrameData(): FrameData? {
        val frameDataLength: Int
        val frameData: List<Byte>
        val isOutgoingData: Boolean

        // First, try to read the 32-bit little endian unsigned integer.
        try {
            val lengthBytes = ByteArray(4)
            val numRead = inputStream.read(lengthBytes, 0, 4)

            if (numRead < 0) {
                // numRead < 0 is not an error; according to the BufferedInputStream
                // docs, it indicates a normal end-of-file.
                logger(LogLevel.DEBUG) { "End of file reached" }
                return null
            } else if (numRead != 4) {
                // We need 4 bytes to read the full integer. Fewer bytes indicate
                // incomplete data, which is invalid.
                logger(LogLevel.ERROR) { "Did only read $numRead/4 length byte(s)" }
                return null
            }

            // Assemble the frame data length, omitting the topmost bit,
            // since it isn't part of the length.
            frameDataLength = (lengthBytes[0].toPosInt() shl 0) or
                (lengthBytes[1].toPosInt() shl 8) or
                (lengthBytes[2].toPosInt() shl 16) or
                ((lengthBytes[3].toPosInt() and 0x7F) shl 24)
            // The topmost bit indicates whether or not this is outgoing data.
            isOutgoingData = (lengthBytes[3].toPosInt() and 0x80) != 0
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Could not read length byte(s): $e" }
            return null
        }

        logger(LogLevel.DEBUG) { "Attempting to read frame data with $frameDataLength byte(s)" }

        // Now try to read the actual frame data.
        try {
            val bytes = ByteArray(frameDataLength)
            val numRead = inputStream.read(bytes, 0, frameDataLength)

            if (numRead < 0) {
                logger(LogLevel.ERROR) { "End of file reached even though frame data bytes were expected" }
            } else if (numRead != frameDataLength) {
                logger(LogLevel.ERROR) { "Did only read $numRead/$frameDataLength frame data byte(s)" }
                return null
            }

            frameData = bytes.toList()
        } catch (e: IOException) {
            logger(LogLevel.ERROR) { "Could not read frame data byte(s): $e" }
            return null
        }

        return FrameData(frameData, isOutgoingData)
    }
}
