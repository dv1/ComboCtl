package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.DateTime
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class ParsedScreenFlowTest {
    @Test
    fun checkSingleDisplayFrameFlow() = runBlocking {
        val displayFrameFlow = listOf(
            testFrameStandardBolusMenuScreen
        ).asFlow()

        val screenFlow = parsedScreenFlow(displayFrameFlow) { }
        val flowResult = mutableListOf<ParsedScreen>()
        screenFlow.toList(flowResult)
        val flowResultIter = flowResult.listIterator()

        assertEquals(1, flowResult.size)

        assertEquals(
            ParsedScreen.StandardBolusMenuScreen,
            flowResultIter.next()
        )
    }

    @Test
    fun checkDuplicateDisplayFrameFiltering() = runBlocking {
        // Test the parsedScreenFlow by feeding it known test frames
        // along with unrecognizable ones. We also feed duplicates,
        // both recognizable and unrecognizable ones, to check that
        // the flow filters these out.

        val unrecognizableDisplayFrame1A = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { false })
        val unrecognizableDisplayFrame1B = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { false })
        val unrecognizableDisplayFrame2 = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { true })

        val displayFrameFlow = listOf(
            // We use these two frames to test out the filtering
            // of duplicate frames. These two frame _are_ equal.
            // The frames just differ in the time separator, but
            // both result in ParsedScreen.NormalMainScreen instances
            // with the same semantics (same time etc). We expect the
            // parsedScreenFlow to recognize and filter out the duplicate.
            testFrameMainScreenWithTimeSeparator,
            testFrameMainScreenWithoutTimeSeparator,
            // 1A and 1B are two different unrecognizable DisplayFrame
            // instances with equal pixel content to test the filtering
            // of duplicate frames when said frames are _not_ recognizable
            // by the parser. The flow should then compare the frames
            // pixel by pixel.
            unrecognizableDisplayFrame1A,
            unrecognizableDisplayFrame1B,
            // Frame 2 is an unrecognizable DisplayFrame whose pixels
            // are different than the ones in frames 1A and 1B. We
            // expect the flow to do a pixel-by-pixel comparison between
            // the unrecognizable frames and detect that frame 2 is
            // really different (= not a duplicate).
            unrecognizableDisplayFrame2,
            // A recognizable frame to test the case that a recognizable
            // frame follows an unrecognizable one.
            testFrameStandardBolusMenuScreen
        ).asFlow()

        val screenFlow = parsedScreenFlow(displayFrameFlow) { }
        val flowResult = mutableListOf<ParsedScreen>()
        screenFlow.toList(flowResult)
        val flowResultIter = flowResult.listIterator()

        // We expect _one_ ParsedScreen.NormalMainScreen
        // (the other frame with the equal content must be filtered out).
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Normal(
                    currentTime = DateTime.fromTime(hour = 10, minute = 20),
                    activeBasalRateNumber = 1,
                    currentBasalRateFactor = 200
                )
            ),
            flowResultIter.next()
        )
        // Next we expect an UnrecognizedScreen result after the change from NormalMainScreen
        // to a frame (unrecognizableDisplayFrame1A) that could not be recognized.
        assertEquals(
            ParsedScreen.UnrecognizedScreen,
            flowResultIter.next()
        )
        // We expect an UnrecognizedScreen result after switching to unrecognizableDisplayFrame2.
        // This is an unrecognizable frame that differs in its pixels from
        // unrecognizableDisplayFrame1A and 1B. Importantly, 1B must have been
        // filtered out, since both 1A and 1B could not be recognized _and_ have
        // equal pixel content.
        assertEquals(
            ParsedScreen.UnrecognizedScreen,
            flowResultIter.next()
        )
        // Since unrecognizableDisplayFrame1B must have been filtered out,
        // the next result we expect is the StandardBolusMenuScreen.
        assertEquals(
            ParsedScreen.StandardBolusMenuScreen,
            flowResultIter.next()
        )
    }

    @Test
    fun checkDuplicateParsedScreenFiltering() = runBlocking {
        // Test the duplicate parsed screen detection with 3 time and date hour settings screens.
        // All three are parsed to ParsedScreen.TimeAndDateSettingsHourScreen instances.
        // All three contain different pixels. (This is the crucial difference to the
        // checkDuplicateDisplayFrameFiltering above.) However, the first 2 have their "hour"
        // properties set to 13, while the third has "hour" set to 14. The parsedScreenFlow
        // is expected to filter the duplicate TimeAndDateSettingsHourScreen with the "13" hour.

        val displayFrameFlow = listOf(
            testTimeAndDateSettingsHourRussianScreen, // This screen frame has "1 PM" (= 13 in 24h format) as hour
            testTimeAndDateSettingsHourTurkishScreen, // This screen frame has "1 PM" (= 13 in 24h format) as hour
            testTimeAndDateSettingsHourPolishScreen // This screen frame has "2 PM" (= 13 in 24h format) as hour
        ).asFlow()

        val screenFlow = parsedScreenFlow(displayFrameFlow) { }
        val flowResult = mutableListOf<ParsedScreen>()
        screenFlow.toList(flowResult)
        val flowResultIter = flowResult.listIterator()

        assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 13), flowResultIter.next())
        assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14), flowResultIter.next())
    }
}
