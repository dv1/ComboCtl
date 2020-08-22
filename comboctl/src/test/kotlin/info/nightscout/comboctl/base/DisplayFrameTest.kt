package info.nightscout.comboctl.base

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The frame contents contained in the original frame rows from the Combo below.
// This is used as a reference to see if frame conversion and assembly is correct.
val referenceDisplayFramePixels = listOf(
    "..OOO.....OOO...OOO........OOOOO..OOO...........................................................",
    ".O.O.O...O...O.O...O.......O.....O...O..........................................................",
    "O..O..O......O.....O.......OOOO..O...O..........................................................",
    "O..OO.O.....O.....O............O..OOOO..........................................................",
    "O.....O....O.....O.............O.....O..........................................................",
    ".O...O....O.....O..........O...O....O...........................................................",
    "..OOO....OOOOO.OOOOO........OOO...OO............................................................",
    "................................................................................................",
    "..................................OOOO..............OOOO........OOOO............................",
    ".....OOOOOOO.....................OO..OO............OO..OO......OO..OO...........................",
    ".....OOOOOOO....................OO....OO..........OO....OO....OO....OO..........................",
    ".....OO...OO....................OO....OO..........OO....OO....OO....OO.....OO..OO....OO.OO......",
    ".....OO...OOOOOOO...............OO....OO..........OO....OO....OO....OO.....OO..OO....OO.OO......",
    ".....OO...OOOOOOO...............OO....OO...........OO..OO......OO..OO......OO..OO....OO.OO......",
    "OOOOOOO...OO...OO...............OO....OO............OOOO........OOOO.......OO..OO...OO..OO......",
    "OOOOOOO...OO...OO...............OO....OO...........OO..OO......OO..OO......OO..OO...OO..OOOOO...",
    "OO...OO...OO...OO...O...........OO....OO..........OO....OO....OO....OO.....OO..OO...OO..OOO.OO..",
    "OO...OO...OO...OO..OO...........OO....OO..........OO....OO....OO....OO.....OO..OO..OO...OO..OO..",
    "OO...OO...OO...OO...O...........OO....OO..........OO....OO....OO....OO.....OO..OO..OO...OO..OO..",
    "OO...OO...OO...OO...O...........OO....OO..........OO....OO....OO....OO.....OO..OO..OO...OO..OO..",
    "OO...OO...OO...OO...O...........OO....OO...OOO....OO....OO....OO....OO.....OO..OO.OO....OO..OO..",
    "OO...OO...OO...OO...O............OO..OO....OOO.....OO..OO......OO..OO......OO..OO.OO....OO..OO..",
    "OO...OO...OO...OO..OOO............OOOO.....OOO......OOOO........OOOO........OOOO..OO....OO..OO..",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................",
    "................................................................................................"
)

val originalRtDisplayFrameRows = listOf(
    byteArrayListOfInts(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1E, 0x29,
        0x49, 0x49, 0x06, 0x00, 0x39, 0x45, 0x45, 0x45, 0x27, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x46, 0x49, 0x51, 0x61, 0x42, 0x00, 0x46, 0x49,
        0x51, 0x61, 0x42, 0x00, 0x00, 0x1C, 0x22, 0x49, 0x4F, 0x41, 0x22, 0x1C
    ),
    byteArrayListOfInts(
        0x00, 0x00, 0x00, 0x80, 0x80, 0x80, 0xF8, 0xF8, 0x00, 0x38, 0xF8, 0xC0,
        0x00, 0x00, 0x00, 0xF8, 0xF8, 0x00, 0x00, 0xF8, 0xF8, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x1C, 0xBE, 0xE3, 0x41, 0x41, 0xE3, 0xBE, 0x1C, 0x00, 0x00,
        0x00, 0x00, 0x1C, 0xBE, 0xE3, 0x41, 0x41, 0xE3, 0xBE, 0x1C, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFC, 0xFE, 0x03, 0x01,
        0x01, 0x03, 0xFE, 0xFC, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0xF0, 0x30, 0x30, 0x30,
        0xFE, 0xFE, 0x06, 0x06, 0x06, 0xFE, 0xFE, 0xC0, 0xC0, 0xC0, 0xC0, 0xC0
    ),
    byteArrayListOfInts(
        0x00, 0x00, 0x7F, 0x7F, 0x00, 0x01, 0x7F, 0x7F, 0x00, 0x00, 0x01, 0x0F,
        0x7E, 0x70, 0x00, 0x3F, 0x7F, 0x40, 0x40, 0x7F, 0x3F, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x1F, 0x3F, 0x60, 0x40, 0x40, 0x60, 0x3F, 0x1F, 0x00, 0x00,
        0x00, 0x00, 0x1F, 0x3F, 0x60, 0x40, 0x40, 0x60, 0x3F, 0x1F, 0x00, 0x00,
        0x00, 0x00, 0x70, 0x70, 0x70, 0x00, 0x00, 0x00, 0x1F, 0x3F, 0x60, 0x40,
        0x40, 0x60, 0x3F, 0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x40, 0x7F, 0x42, 0x00, 0x00, 0x7F, 0x7F, 0x00, 0x00, 0x00,
        0x7F, 0x7F, 0x00, 0x00, 0x00, 0x7F, 0x7F, 0x00, 0x00, 0x00, 0x7F, 0x7F
    ),
    byteArrayListOfInts(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
)

class DisplayFrameTest {
    @Test
    fun checkPixelAddressing() {
        // Construct a simple display frame with 2 pixels set and the rest
        // being empty. Pixel 1 is at coordinates (x 1 y 0), pixel 2 is at
        // coordinates (x 0 y 1).
        val frameBytes = MutableList<Byte>(NUM_DISPLAY_FRAME_BYTES) { 0x00 }
        // See the DisplayFrame documentation for details about how pixels
        // are stored to make sense of this addressing.
        frameBytes[0] = 0x20.toByte()
        frameBytes[12 + 1] = 0x80.toByte()

        val displayFrame = DisplayFrame(frameBytes)

        // Verify that all pixels except the two specific ones are empty.
        for (y in 0 until 32) {
            for (x in 0 until 96) {
                when (Pair(x, y)) {
                    Pair(2, 0) -> assertEquals(true, displayFrame.getPixelAt(x, y))
                    Pair(8, 1) -> assertEquals(true, displayFrame.getPixelAt(x, y))
                    else -> assertEquals(false, displayFrame.getPixelAt(x, y))
                }
            }
        }
    }

    @Test
    fun checkDisplayFrameAssembly() {
        val assembler = DisplayFrameAssembler()
        var displayFrame: DisplayFrame?

        // The Combo splits a frame into 4 rows and transmits in one application layer
        // RT_DISPLAY packet each. We simulate this by keeping the actual pixel data
        // of these packets in the originalRtDisplayFrameRows array. If the assembler
        // works correctly, then feeding these four byte lists into it will produce
        // a complete display frame.
        //
        // The index here is 0x17. The index is how we can see if a row belongs to
        // the same frame, or if a new frame started. If we get a row with an index
        // that is different than the previous one, then we have to discard any
        // previously collected rows and start anew. Here, we supply 4 rows of the
        // same index, so we expect a complete frame.

        displayFrame = assembler.processRTDisplayPayload(0x17, 0, originalRtDisplayFrameRows[0])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 1, originalRtDisplayFrameRows[1])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 2, originalRtDisplayFrameRows[2])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 3, originalRtDisplayFrameRows[3])
        assertFalse(displayFrame == null)

        // Check that the assembled frame is correct.
        compareWithReference(displayFrame!!)
    }

    @Test
    fun checkChangingRTDisplayPayloadIndex() {
        val assembler = DisplayFrameAssembler()
        var displayFrame: DisplayFrame?

        // This is similar to the test above, except that we only provide 3 rows
        // of the frame with index 0x17, and then suddenly deliver a row with index
        // 0x18. We expect the assembler to discard any previously collected rows
        // and restart from scratch. We do provide four rows with index 0x18, so
        // the assembler is supposed to deliver a completed frame then.

        // The first rows with index 0x17.

        displayFrame = assembler.processRTDisplayPayload(0x17, 0, originalRtDisplayFrameRows[0])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 1, originalRtDisplayFrameRows[1])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 2, originalRtDisplayFrameRows[2])
        assertTrue(displayFrame == null)

        // First row with index 0x18. This should reset the assembler's contents,
        // restarting its assembly from scratch.

        displayFrame = assembler.processRTDisplayPayload(0x18, 0, originalRtDisplayFrameRows[0])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x18, 1, originalRtDisplayFrameRows[1])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x18, 2, originalRtDisplayFrameRows[2])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x18, 3, originalRtDisplayFrameRows[3])
        assertFalse(displayFrame == null)

        // Check that the completed frame is OK.
        compareWithReference(displayFrame!!)
    }

    @Test
    fun checkDisplayFrameOutOfOrderAssembly() {
        val assembler = DisplayFrameAssembler()
        var displayFrame: DisplayFrame?

        // Similar to the checkDisplayFrameAssembly, except that rows
        // are supplied to the assembler out-of-order.

        displayFrame = assembler.processRTDisplayPayload(0x17, 2, originalRtDisplayFrameRows[2])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 1, originalRtDisplayFrameRows[1])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 3, originalRtDisplayFrameRows[3])
        assertTrue(displayFrame == null)

        displayFrame = assembler.processRTDisplayPayload(0x17, 0, originalRtDisplayFrameRows[0])
        assertFalse(displayFrame == null)

        // Check that the assembled frame is correct.
        compareWithReference(displayFrame!!)
    }

    private fun dumpDisplayFrameContents(displayFrame: DisplayFrame) {
        for (y in 0 until 32) {
            for (x in 0 until 96) {
                val displayFramePixel = displayFrame.getPixelAt(x, y)
                print(if (displayFramePixel) 'O' else '.')
            }
            println("")
        }
    }

    private fun compareWithReference(displayFrame: DisplayFrame) {
        for (y in 0 until 32) {
            for (x in 0 until 96) {
                val referencePixel = (referenceDisplayFramePixels[y][x] != '.')
                val displayFramePixel = displayFrame.getPixelAt(x, y)

                val equal = (referencePixel == displayFramePixel)
                if (!equal) {
                    println("Mismatch at x $x y $y")
                    dumpDisplayFrameContents(displayFrame)
                }

                assertTrue(equal)
            }
        }
    }
}
