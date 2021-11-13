package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.DisplayFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Exception thrown when an alert screens appear in the [parsedScreenFlow].
 *
 * @property alertScreenContent The content of the alert screen(s).
 */
class AlertScreenException(val alertScreenContent: AlertScreenContent) :
    ComboException("RT alert screen appeared with content: $alertScreenContent")

internal fun areParsedScreensEqual(
    first: Pair<ParsedScreen, DisplayFrame>,
    second: Pair<ParsedScreen, DisplayFrame>
): Boolean {
    val firstParsedScreen = first.first
    val firstDisplayFrame = first.second

    val secondParsedScreen = second.first
    val secondDisplayFrame = second.second

    // Typically, equality is determined by value-comparing the ParsedScreen
    // instances, that is, their tokens are compared. In the edge case that
    // both screens are unrecognized, this is not doable (some tokens may
    // have been detected incorrectly etc). As a fallback, in that case,
    // we compare the pixels, which is a more expensive check, but detects
    // when the frames look different. This case does not happen often, so
    // it is acceptable to do the more expensive check then.

    return if ((firstParsedScreen is ParsedScreen.UnrecognizedScreen) && (secondParsedScreen is ParsedScreen.UnrecognizedScreen))
        (firstDisplayFrame == secondDisplayFrame)
    else
        (firstParsedScreen == secondParsedScreen)
}

/**
 * Creates a [Flow] of [ParsedScreen] instances that are the result of parsing [DisplayFrame] instances.
 *
 * This cold flow first parses the display frames that come in through the [displayFrameFlow].
 * Each [DisplayFrame] is parsed, the result being a [ParsedScreen].
 *
 * The flow then checks for duplicate frames and discards duplicates. This is done at the
 * level of the parsed tokens, since sometimes, frames with differences in their pixels
 * still have the same content (example: a blinking time indicator produces frames that
 * sometimes have the pixels of the ':' separator, and sometimes don't).
 *
 * The remaining [ParsedScreen] instances are the output of this flow.
 *
 * @param displayFrameFlow Flow of [DisplayFrame] instances that will be parsed.
 * @param filterDuplicates Whether or not to filter out duplicates. Filtering is
 *        enabled by default.
 * @return The [ParsedScreen] flow.
 */
fun parsedScreenFlow(
    displayFrameFlow: Flow<DisplayFrame>,
    filterDuplicates: Boolean = true
): Flow<ParsedScreen> =
    if (filterDuplicates) {
        displayFrameFlow
            // Initial cheap check to detect and filter out DisplayFrame instances that are passed multiple times
            // to the flow (note the by-reference === comparison; the by-value comparison is done below) 
            .distinctUntilChanged { firstDisplayFrame, secondDisplayFrame -> firstDisplayFrame === secondDisplayFrame }
            // Parse frames and expand the flow to be able to also compare frame pixels in areParsedScreensEqual() if necessary
            .map { displayFrame -> Pair(parseDisplayFrame(displayFrame), displayFrame) }
            // Perform the computationally more expensive by-value equality check to filter out duplicates
            .distinctUntilChanged { firstParsedScreen, secondParsedScreen -> areParsedScreensEqual(firstParsedScreen, secondParsedScreen) }
            // Discard the display frames since we do not need them anymore (we are only interested in the parsed screens at this point)
            .map { pair -> pair.first }
            // Check for alert screen and throw an exception if one is seen
            .filter { parsedScreen ->
                if (parsedScreen is ParsedScreen.AlertScreen)
                    throw AlertScreenException(parsedScreen.content)
                else
                    true
            }
    } else {
        displayFrameFlow
            // First, parse the frames
            .map { displayFrame -> parseDisplayFrame(displayFrame) }
            // Check for alert screen and throw an exception if one is seen
            .filter { parsedScreen ->
                if (parsedScreen is ParsedScreen.AlertScreen)
                    throw AlertScreenException(parsedScreen.content)
                else
                    true
            }
    }
