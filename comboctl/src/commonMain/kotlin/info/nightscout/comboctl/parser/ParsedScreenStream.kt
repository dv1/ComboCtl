package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.DisplayFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first

/**
 * Parses [DisplayFrame] instances coming from a [SharedFlow] and filters out duplicate screens.
 *
 * In order to interpret display frames, they must be parsed first. This is accomplished
 * using the [parseDisplayFrame] function. However, the Combo sometimes sends the same
 * frame more than once. Also, not all frames can be recognized.
 *
 * This class provides functionality to receive incoming display frames, parse them,
 * check for duplicates and unrecognized frames, and output [ParsedScreen] instances
 * that contain the semantics of recognized frames. This is essential for being able
 * to programmatically control the Combo in the remote terminal mode (for example,
 * to set a basal profile).
 *
 * @param displayFrameFlow [SharedFlow] delivering display frames.
 */
class ParsedScreenStream(private val displayFrameFlow: SharedFlow<DisplayFrame>) {
    private var previousDisplayFrame: DisplayFrame? = null
    private var previousParsedScreen: ParsedScreen = ParsedScreen.UnrecognizedScreen

    /**
     * Reset internal states back to their initial values.
     *
     * This is useful when a new connection was established, or when the Combo
     * just switched to the remote terminal mode.
     */
    fun reset() {
        previousDisplayFrame = null
        previousParsedScreen = ParsedScreen.UnrecognizedScreen
    }

    /**
     * Gets a new [ParsedScreen] instance out of the newest unique [DisplayFrame].
     *
     * This suspends until a new [DisplayFrame] is received via the display frame flow
     * and said frame is found to not be a duplicate.
     *
     * There are two special cases in which this will return [ParsedScreen.UnrecognizedScreen].
     *
     * 1. The flow just started, and the very first received frame was not recognized.
     * 2. Both the newly received frame _and_ the previously received frame were not
     *    recognized, but the pixels of these frames are different.
     *
     * In both cases, The return value is [ParsedScreen.UnrecognizedScreen] to let the
     * caller know that a new screen came in and that it is not recognized. (If in case
     * #1 both frames have the same pixels, the new frame is considered a duplicate, and filtered out.)
     *
     * This is important if the code that calls this function programmatically presses
     * RT buttons based on whether or not a new screen was received.
     *
     * @return [ParsedScreen] instance if a new non-duplicate frame was received,
     *         recognized, and parsed. [ParsedScreen.UnrecognizedScreen] if a new
     *         non-duplicate was received that could not be recognized/parsed.
     */
    suspend fun getNextParsedScreen(): ParsedScreen {
        displayFrameFlow.first { displayFrame ->
            if ((previousDisplayFrame == null) || (previousDisplayFrame !== displayFrame)) {
                val parsedScreen = parseDisplayFrame(displayFrame)

                // If both previous and current parsed screen are UnrecognizedScreen,
                // then either there is no previous screen (= we just got the first frame)
                // and the current one was not recognized, or neither the previous nor the
                // current  display frame were recognized. In both cases, value-compare the
                // frames. If the previous frame is UnrecognizedScreen, the inequality
                // comparison will be true, specifying that this is a new frame. If the
                // previous frame is not UnrecognizedScreen, the previous and next frame
                // are value-compared, pixel by pixel, to check if they have equal contents.
                // If not, isNewFrame is true.
                if ((previousParsedScreen is ParsedScreen.UnrecognizedScreen) && (parsedScreen is ParsedScreen.UnrecognizedScreen)) {
                    val isNewFrame = (previousDisplayFrame != displayFrame)
                    previousDisplayFrame = displayFrame
                    return@first isNewFrame
                }

                // If we reach this point, then either:
                //
                // 1. previousParsedScreen is UnrecognizedScreen and parsedScreen is not
                // 2. Neither are UnrecognizedScreen, and the value comparison shows that
                //    their values aren't equal
                //
                // In both cases this means that the current frame is
                // a new, different one.
                if ((previousParsedScreen is ParsedScreen.UnrecognizedScreen) || (previousParsedScreen != parsedScreen)) {
                    previousDisplayFrame = displayFrame
                    previousParsedScreen = parsedScreen
                    return@first true
                }
            }
            return@first false
        }
        return previousParsedScreen
    }
}
