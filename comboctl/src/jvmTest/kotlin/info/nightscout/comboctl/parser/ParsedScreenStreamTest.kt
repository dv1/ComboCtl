package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ParsedScreenStreamTest {
    @Test
    fun checkDuplicateDisplayFrameFiltering() {
        // Test the ParsedScreenStream by feeding it known test frames
        // along with unrecognizable ones. We also feed duplicates in,
        // both recognizable and unrecognizable ones, to check that
        // the stream filters these out.

        val unrecognizableDisplayFrame1A = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { false })
        val unrecognizableDisplayFrame1B = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { false })
        val unrecognizableDisplayFrame2 = DisplayFrame(BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { true })

        runBlocking {
            val displayFrameFlow = MutableSharedFlow<DisplayFrame>()

            launch {
                // We use these two frames to test out the filtering
                // of recognizable frame filtering. These two _are_
                // equal. The frames just differ in the time separator,
                // but both result in ParsedScreen.NormalMainScreen instances
                // with the same semantics (same time etc).
                displayFrameFlow.emit(testFrameMainScreenWithTimeSeparator)
                displayFrameFlow.emit(testFrameMainScreenWithoutTimeSeparator)
                // 1A and 1B are two different DisplayFrame instances with
                // equal content to test duplicate filtering.
                displayFrameFlow.emit(unrecognizableDisplayFrame1A)
                displayFrameFlow.emit(unrecognizableDisplayFrame1B)
                displayFrameFlow.emit(unrecognizableDisplayFrame2)
                displayFrameFlow.emit(testFrameStandardBolusMenuScreen)
            }

            val parsedScreenStream = ParsedScreenStream(displayFrameFlow)

            // We expect _one_ ParsedScreen.NormalMainScreen
            // (the other frame with the equal content must be filtered out).
            assertEquals(
                ParsedScreen.MainScreen(
                    MainScreenContent.Normal(
                        currentTimeHour = 10,
                        currentTimeMinute = 20,
                        activeBasalRateNumber = 1,
                        currentBasalRateFactor = 200
                    )
                ),
                parsedScreenStream.getNextParsedScreen()
            )
            // Next we expect an UnrecognizedScreen result after the change from NormalMainScreen
            // to a frame (unrecognizableDisplayFrame1A) that could not be recognized.
            assertEquals(
                ParsedScreen.UnrecognizedScreen,
                parsedScreenStream.getNextParsedScreen()
            )
            // We expect an UnrecognizedScreen result after switching to unrecognizableDisplayFrame2.
            // This is an unrecognizable frame that differs in its pixels from
            // unrecognizableDisplayFrame1A and 1B. Importantly, 1B must have been
            // filtered out, since both 1A and 1B could not be recognized _and_ have
            // equal pixel content.
            assertEquals(
                ParsedScreen.UnrecognizedScreen,
                parsedScreenStream.getNextParsedScreen()
            )
            // Since unrecognizableDisplayFrame1B must have been filtered out,
            // the next result we expect is the StandardBolusMenuScreen.
            assertEquals(
                ParsedScreen.StandardBolusMenuScreen,
                parsedScreenStream.getNextParsedScreen()
            )
        }
    }

    @Test
    fun checkDuplicateParsedScreenFiltering() {
        // Test the duplicate parsed screen detection with 3 time and date hour settings screens.
        // All three are parsed to ParsedScreen.TimeAndDateSettingsHourScreen instances.
        // All three contain different pixels. (This is the crucial difference to the
        // checkDuplicateDisplayFrameFiltering above.) However, the first 2 have their "hour"
        // properties set to 13, while the third has "hour" set to 14. The ParsedScreenStream
        // is expected to filter the duplicate TimeAndDateSettingsHourScreen with the "13" hour.

        runBlocking {
            val displayFrameFlow = MutableSharedFlow<DisplayFrame>()

            launch {
                displayFrameFlow.emit(testTimeAndDateSettingsHourRussianScreen) // This screen frame has "1 PM" (= 13 in 24h format) as hour
                displayFrameFlow.emit(testTimeAndDateSettingsHourTurkishScreen) // This screen frame has "1 PM" (= 13 in 24h format) as hour
                displayFrameFlow.emit(testTimeAndDateSettingsHourPolishScreen) // This screen frame has "2 PM" (= 13 in 24h format) as hour
            }

            val parsedScreenStream = ParsedScreenStream(displayFrameFlow)

            assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 13), parsedScreenStream.getNextParsedScreen())
            assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14), parsedScreenStream.getNextParsedScreen())
        }
    }
}
