package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val logger = Logger.get("ParsedScreenFlow")

/**
 * Exception thrown when alert screens appear in the [parsedScreenFlow].
 *
 * @property alertScreenContents The content of the alert screen(s).
 */
class AlertScreenException(val alertScreenContents: List<AlertScreenContent>) :
    ComboException("RT alert screen appeared with contents: $alertScreenContents") {
    /** Returns true if there is at least one [AlertScreenContent.Warning] in [alertScreenContents]. */
    fun containsWarnings() = alertScreenContents.any { it is AlertScreenContent.Warning }

    /** Returns true if there is at least one [AlertScreenContent.Error] in [alertScreenContents]. */
    fun containsErrors() = alertScreenContents.any { it is AlertScreenContent.Error }

    /** Returns the warning code if [alertScreenContents] only contains a single warning, and null otherwise. */
    fun getSingleWarningCode() =
        if ((alertScreenContents.size == 1) && (alertScreenContents[0] is AlertScreenContent.Warning))
            (alertScreenContents[0] as AlertScreenContent.Warning).code
        else
            null
}

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

// Flow that retains state about the last observed alert screens.
// This is necessary, since sometimes, one alert screen may follow another.
// With this flow, we can collect all of those and return them as one list.
internal class AlertScreenFilterFlow(
    private val parsedScreenFlow: Flow<ParsedScreen>,
    private val ignoreAlertScreens: Boolean,
    private val dismissAlertScreenAction: suspend () -> Unit
) : AbstractFlow<ParsedScreen>() {
    private var alertScreenContents = mutableListOf<AlertScreenContent>()

    override suspend fun collectSafely(collector: FlowCollector<ParsedScreen>) {
        parsedScreenFlow.collect { parsedScreen ->
            if (parsedScreen is ParsedScreen.AlertScreen) {
                if (!ignoreAlertScreens) {
                    when (val alertScreenContent = parsedScreen.content) {
                        is AlertScreenContent.Warning,
                        is AlertScreenContent.Error -> {
                            logger(LogLevel.WARN) { "Got alert screen with content $alertScreenContent" }
                            if (alertScreenContent !in alertScreenContents)
                                alertScreenContents.add(alertScreenContent)
                            dismissAlertScreenAction()
                        }
                        else -> Unit
                    }
                }
            } else {
                if (!alertScreenContents.isEmpty()) {
                    val finishedContents = alertScreenContents
                    alertScreenContents = mutableListOf<AlertScreenContent>()
                    throw AlertScreenException(finishedContents)
                }
                collector.emit(parsedScreen)
            }
        }
    }
}

internal fun Flow<ParsedScreen>.alertScreenFilterFlow(ignoreAlertScreens: Boolean, dismissAlertScreenAction: suspend () -> Unit): Flow<ParsedScreen> =
    AlertScreenFilterFlow(this, ignoreAlertScreens, dismissAlertScreenAction)

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
 * Alert screens are automatically dismissed (by [dismissAlertScreenAction]), and their
 * contents (error/warning number) are communicated to the user via [AlertScreenException].
 * When this exception is thrown, and the screens got dismissed, the user should expect
 * the Combo to have returned to the main screen. Any operation that was being performed
 * when the alert screen(s) appeared (setting a TBR for example) must be considered aborted.
 *
 * IMPORTANT: If multiple parsed screen flows are created from the same display frame flow,
 * it is essential that only one of those flows that are active at the same time actually does
 * any alert screen dismissals. The others must set [ignoreAlertScreens] to true. Otherwise,
 * the flows may dismiss screens more often than necessary, possibly causing undefined behavior.
 * Also, that way, only the flow that actually dismisses alert screens throws [AlertScreenException].
 * Typically, flows with ignoreAlertScreens set to false are flows that are used by RT navigation
 * and quantity adjustment functions (like navigating to a specific screen or adjusting a TBR
 * on screen), while flows with ignoreAlertScreens set to true are those that are used for
 * purely observing the screens. (If multiple flows exist with ignoreAlertScreens set to false,
 * but only one of them is in use at the same time, then this will not cause problems, since
 * these are cold flows and don't run on their own.)
 *
 * @param displayFrameFlow Flow of [DisplayFrame] instances that will be parsed.
 * @param filterDuplicates Whether or not to filter out duplicates. Filtering is
 *        enabled by default.
 * @param ignoreAlertScreens If set to true, alert screens are ignored and dropped. If
 *        set to false, alert screens are processed, and an exception is thrown. set to
 *        true by default to help prevent potential duplicate dismissals (see above).
 * @param dismissAlertScreenAction Callback that gets invoked whenever an alert screen
 *        needs to be dismissed. Typically, the CHECK button is pressed in here.
 *        Only gets called if [ignoreAlertScreens] is set to false.
 *        Default callback does nothing.
 * @return The [ParsedScreen] flow.
 * @throws AlertScreenException if alert screens are seen. Only thrown if
 *         [ignoreAlertScreens] is set to false.
 */
fun parsedScreenFlow(
    displayFrameFlow: Flow<DisplayFrame>,
    filterDuplicates: Boolean = true,
    ignoreAlertScreens: Boolean = true,
    dismissAlertScreenAction: suspend () -> Unit = { }
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
            // Check for alert screens and throw an exception if some are seen
            .alertScreenFilterFlow(ignoreAlertScreens, dismissAlertScreenAction)
    } else {
        displayFrameFlow
            // First, parse the frames
            .map { displayFrame -> parseDisplayFrame(displayFrame) }
            // Check for alert screens and throw an exception if some are seen
            .alertScreenFilterFlow(ignoreAlertScreens, dismissAlertScreenAction)
    }
