package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.ShortestPathHalf
import info.nightscout.comboctl.base.Tree
import info.nightscout.comboctl.base.findShortestPath
import info.nightscout.comboctl.parser.AlertScreenContent
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.ParsedScreenStream
import kotlin.reflect.KClassifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private val logger = Logger.get("RTNavigation")

/**
 * RT navigation buttons.
 *
 * These are essentially the [PumpIO.Button] values, but also include
 * combined button presses for navigating back (which requires pressing
 * both MENU and UP buttons at the same time).
 */
enum class RTNavigationButton(val rtButtonCodes: List<PumpIO.Button>) {
    UP(listOf(PumpIO.Button.UP)),
    DOWN(listOf(PumpIO.Button.DOWN)),
    MENU(listOf(PumpIO.Button.MENU)),
    CHECK(listOf(PumpIO.Button.CHECK)),

    BACK(listOf(PumpIO.Button.MENU, PumpIO.Button.UP))
}

internal data class RTNavigationScreenInfo(
    val screenType: KClassifier,
    val buttonToReachThis: RTNavigationButton?,
    val buttonToExit: RTNavigationButton? = RTNavigationButton.BACK
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RTNavigationScreenInfo

        // Equality only depends on the screen type, not on the buttons.
        // This is because we locate nodes in the navigation tree solely
        // and only by the screen type. Depending on the shortest path
        // between these nodes, we then use appropriate buttons.
        // See findRTNavigationPath for details.
        if (screenType != other.screenType) return false

        return true
    }

    override fun hashCode() = screenType.hashCode()
}

internal val rtNavigationTree = Tree<RTNavigationScreenInfo>(
    rootValue = RTNavigationScreenInfo(ParsedScreen.MainScreen::class, buttonToReachThis = null, buttonToExit = null)
) {
    child(RTNavigationScreenInfo(ParsedScreen.QuickinfoMainScreen::class, RTNavigationButton.CHECK))
    child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRateMenuScreen::class, RTNavigationButton.MENU)) {
        child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRatePercentageScreen::class, RTNavigationButton.CHECK))
            .child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRateDurationScreen::class, RTNavigationButton.MENU))
        child(RTNavigationScreenInfo(ParsedScreen.BasalRate1ProgrammingMenuScreen::class, RTNavigationButton.MENU))
            .child(RTNavigationScreenInfo(ParsedScreen.BasalRateTotalScreen::class, RTNavigationButton.CHECK))
                .child(RTNavigationScreenInfo(ParsedScreen.BasalRateFactorSettingScreen::class, RTNavigationButton.MENU))
    }
}

internal data class RTNavigationPathNode(val screenType: KClassifier?, val nextNavButton: RTNavigationButton?)

// This creates a sequence that contains all of the necessary instructions
// to navigate between screens to reach the desired screen type. Each
// RTNavigationPathNode in the sequence contains the type of the next screen
// and the button to press to reach that next screen. In the last item
// in that sequence, both of these values are set to null.
internal fun findRTNavigationPath(fromScreenType: KClassifier, toScreenType: KClassifier) =
    findShortestPath(
        rtNavigationTree,
        RTNavigationScreenInfo(fromScreenType, null),
        RTNavigationScreenInfo(toScreenType, null)
    ) { screenInfo: RTNavigationScreenInfo, shortestPathHalf: ShortestPathHalf, nextScreenInfo: RTNavigationScreenInfo?, _: ShortestPathHalf? ->
        if (nextScreenInfo != null) {
            RTNavigationPathNode(
                nextScreenInfo.screenType,
                // The path nodes from the first half are those where navigation
                // is "going back". When looking at the rtNavigationTree above,
                // this means going up the tree in direction of the root node. In
                // the second half, navigation "goes forward" to the target node.
                when (shortestPathHalf) {
                    ShortestPathHalf.FIRST_HALF -> screenInfo.buttonToExit
                    ShortestPathHalf.SECOND_HALF -> nextScreenInfo.buttonToReachThis
                }
            )
        } else
            RTNavigationPathNode(null, null)
    }

/**
 * Base class for exceptions thrown when navigating through remote terminal (RT) screens.
 *
 * @param message The detail message.
 */
open class RTNavigationException(message: String) : ComboException(message)

/**
 * Exception thrown when the RT navigation could not find a screen of the searched type.
 *
 * @property targetScreenType Type of the screen that was searched.
 */
class CouldNotFindRTScreenException(val targetScreenType: KClassifier) : RTNavigationException("Could not find RT screen $targetScreenType")

/**
 * Exception thrown when a function needed a specific screen type but could not get it.
 *
 * Typically, this happens because a display frame could not be parsed,
 * so the screen is [ParsedScreen.UnrecognizedScreen].
 */
class NoUsableRTScreenException() : RTNavigationException("No usable RT screen available")

/**
 * Exception thrown when an alert screen appears while navigating through RT screens.
 *
 * @property alertScreenContent The content of the alert screen.
 */
class AlertScreenException(val alertScreenContent: AlertScreenContent) :
    RTNavigationException("RT alert screen appeared with contents: $alertScreenContent")

/**
 * Context object for navigating through remote terminal (RT) screens.
 *
 * This stores all common states that are used during navigation. In particular,
 * the last parsed screen is stored. This is important, since it takes some time
 * until the Combo sends a display frame with a _new_ screen inside (it sometimes
 * sends the same display frame multiple times, or different display frames with
 * the same screen type multiple times). If for example function A got a parsed
 * screen but could not handle it, function B might still be interested in trying
 * to interpret the current screen contents. By being able to access the last
 * parsed screen, function B can then interpret it immediately without having
 * to wait for a new screen again. This improves performance overall.
 *
 * This also contains code to operate the pump, particularly for pushing buttons.
 *
 * @property pump Pump to operate. Navigation functions may need to access
 *           some of the [Pump] functionality, which is why this exists.
 * @property parsedScreenStream [ParsedScreenStream] instance to receive
 *           parsed screens from.
 * @property maxNumCycleAttempts How many times RT navigation shall cycle through
 *           screens before giving up. This is necessary to avoid potential
 *           infinite loops.
 */
class RTNavigationContext(
    val pump: Pump,
    val parsedScreenStream: ParsedScreenStream,
    val maxNumCycleAttempts: Int = 20
) {
    private var parsedScreen: ParsedScreen = ParsedScreen.UnrecognizedScreen
    private var parsedScreenSet = false

    private val mutableRTWarningCodeFlow = MutableSharedFlow<Int>()

    /**
     * Read-only [SharedFlow] property that delivers warning codes observed while getting [ParsedScreen] instances.
     *
     * This flow publishes warning codes during the [waitForAndDismissWarningScreen]
     * and [getParsedScreen] calls. These RT warning screens are automatically
     * dismissed. The user gets notified about those through this flow.
     * One exception is when [waitForAndDismissWarningScreen] is called and
     * a warning screen is observed whose code matches the expected code that
     * was passed to that function call as argument. That code is not published
     * through this flow.
     */
    val rtWarningCodeFlow = mutableRTWarningCodeFlow.asSharedFlow()

    /**
     * Gets the parsed screen from this context object.
     *
     * This returns the parsed screen that is internally stored. If there is
     * no such parsed screen, it fetches one from the [ParsedScreenStream]
     * that was passed to the constructor, stores a reference to that newly
     * received parsed screen internally (to make sure the next time this
     * function is called, that parsed screen is returned immediately),
     * and then returns the parsed screen. If callers decide that that
     * parsed screen has been fully processed and needs to be discarded
     * (to be able to look at new parsed screens), [parsedScreenDone]
     * needs to be called.
     *
     * If a a warning screen appears, it is automatically dismissed
     * by "pressing" the CHECK button. Afterwards, the warning code
     * is published via the [rtWarningCodeFlow], and this function
     * proceeds as usual with returning the next parsed screen.
     * (The warning screens are not returned; they are filtered out.)
     *
     * @return The parsed screen, of [ParsedScreen.UnrecognizedScreen]
     *         if the screen could not be recognized and consequently
     *         was not parsed.
     * @throws AlertScreenException if an error screen appears. If this
     *         happens, that screen is implicitly marked as processed
     *         as if [parsedScreenDone] had been called.
     */
    suspend fun getParsedScreen(): ParsedScreen {
        if (!parsedScreenSet) {
            parsedScreen = parsedScreenStream.getNextParsedScreen()

            if (parsedScreen is ParsedScreen.AlertScreen) {
                when (val alertScreenContent = (parsedScreen as ParsedScreen.AlertScreen).content) {
                    is AlertScreenContent.Warning -> {
                        logger(LogLevel.WARN) { "Got warning screen with code ${alertScreenContent.code}" }
                        dismissWarningScreen()
                    }
                    is AlertScreenContent.Error -> {
                        // Mark this screen as done to make sure that
                        // the next getParsedScreen() does not again
                        // retrieve the error screen.
                        parsedScreenDone()
                        logger(LogLevel.ERROR) { "Got error screen with code ${alertScreenContent.code}" }
                        throw AlertScreenException(alertScreenContent)
                    }
                    else -> Unit
                }
            }

            parsedScreenSet = true
        }
        return parsedScreen
    }

    /**
     * Suspends until a warning screen with the given code is observed and dismissed.
     *
     * This is useful if after a certain action it is expected that a warning screen
     * appears. In such a case, it is necessary to dismiss that warning. The most
     * prominent example of this is the W6 warning when a current TBR is cancelled
     * by setting its percentage to 100.
     *
     * This function works in two stages. First, it keeps getting screens until
     * a warning screen is seen. This commences the second stage, during which the
     * function keeps pressing CHECK until the warning screen is not visible anymore.
     * The screen that is shown after the warning screen is stored and can be
     * accessed as usual with [getParsedScreen]. (It is not marked as processed.)
     *
     * If any other warnings occur while this function is suspending, their codes
     * are published via [rtWarningCodeFlow]. The expected warning code is not
     * published, however.
     *
     * @throws AlertScreenException if an error screen appears. If this
     *         happens, that screen is implicitly marked as processed
     *         as if [parsedScreenDone] had been called.
     */
    suspend fun waitForAndDismissWarningScreen(expectedWarningCode: Int) {
        var warningScreenDismissed = false
        while (!warningScreenDismissed) {
            if (parsedScreen is ParsedScreen.AlertScreen) {
                when (val alertScreenContent = (parsedScreen as ParsedScreen.AlertScreen).content) {
                    is AlertScreenContent.Warning -> {
                        logger(LogLevel.WARN) { "Got warning screen with code ${alertScreenContent.code}" }
                        dismissWarningScreen(expectedWarningCode)
                        warningScreenDismissed = true
                    }
                    is AlertScreenContent.Error -> {
                        // Mark this screen as done to make sure that
                        // the next getParsedScreen() does not again
                        // retrieve the error screen.
                        parsedScreenDone()
                        logger(LogLevel.ERROR) { "Got error screen with code ${alertScreenContent.code}" }
                        throw AlertScreenException(alertScreenContent)
                    }
                    else -> Unit
                }
            } else
                parsedScreen = parsedScreenStream.getNextParsedScreen()
        }

        parsedScreenSet = true
    }

    /**
     * Invalidates any previously received parsed screen.
     *
     * This needs to be called once a parsed screen is considered to
     * have been fully processed and to not be of any use anymore.
     * Calling this then guarantees that the next [getParsedScreen]
     * call will fetch a new parsed screen instead of returning the
     * previously received one.
     */
    fun parsedScreenDone() {
        parsedScreenSet = false
    }

    /**
     * Pushes an RT navigation button.
     *
     * See [RTNavigationButton] for details about why this exists.
     *
     * @param button RT navigation button to press.
     */
    suspend fun pushButton(button: RTNavigationButton) =
        pump.sendShortRTButtonPress(button.rtButtonCodes)

    private suspend fun dismissWarningScreen(expectedWarningCode: Int? = null) {
        val observedWarnings = mutableListOf<Int>()

        while (true) {
            if (parsedScreen !is ParsedScreen.AlertScreen) {
                logger(LogLevel.DEBUG) { "Warning screen no longer visible; successfully dismissed warning" }
                break
            }

            when (val alertScreenContent = (parsedScreen as ParsedScreen.AlertScreen).content) {
                is AlertScreenContent.Warning -> {
                    // We store the warning code in a list, not just an Int. This covers
                    // a rare corner case where a new warning appears before the current
                    // warning is dismissed.
                    if (alertScreenContent.code !in observedWarnings) {
                        logger(LogLevel.DEBUG) { "Seen warning screen with code ${alertScreenContent.code}; dismissing" }
                        observedWarnings.add(0, alertScreenContent.code)
                    }
                }
                is AlertScreenContent.Error -> {
                    // Corner case: An error appears before the current warning is dismissed.
                    // Handle this as if the error appeared in the beginning.
                    logger(LogLevel.ERROR) { "Got error screen with code ${alertScreenContent.code}" }
                    throw AlertScreenException(alertScreenContent)
                }
                else -> Unit
            }

            // Keep pushing the CHECK button until the warning screen is gone.
            pushButton(RTNavigationButton.CHECK)

            parsedScreen = parsedScreenStream.getNextParsedScreen()
        }

        for (warningCode in observedWarnings) {
            if ((expectedWarningCode == null) || (warningCode != expectedWarningCode)) {
                logger(LogLevel.DEBUG) { "Reporting previously observed and dismissed warning with code $warningCode" }
                mutableRTWarningCodeFlow.emit(warningCode)
            }
        }
    }
}

/**
 * Cycles through RT screens until the a screen with the desired type is found.
 *
 * "Cycling" means to press a particular RT navigation button until a screen
 * with the desired type is found. If the button is pressed a certain number
 * of times (defined by [RTNavigationContext]'s maxNumCycleAttempts property),
 * and no matching screen is found, this throws [CouldNotFindRTScreenException].
 *
 * This suspends until either the screen is found or an exception is thrown.
 *
 * @param rtNavigationContext RT navigation context to use for cycling through screens.
 * @param button RT navigation button to press for cycling between screens.
 * @param targetScreenType Type of the screen to search for.
 * @throws CouldNotFindRTScreenException if no screen with the given type was found.
 */
suspend fun cycleToRTScreen(
    rtNavigationContext: RTNavigationContext,
    button: RTNavigationButton,
    targetScreenType: KClassifier
) {
    for (numSeenScreens in 0 until rtNavigationContext.maxNumCycleAttempts) {
        val parsedScreen = rtNavigationContext.getParsedScreen()

        if (parsedScreen::class == targetScreenType)
            return

        rtNavigationContext.pushButton(button)

        rtNavigationContext.parsedScreenDone()
    }

    throw CouldNotFindRTScreenException(targetScreenType)
}

/**
 * Suspends until the desired screen type shows up.
 *
 * This is useful for when an RT button was pressed programmatically, and
 * execution has to wait until the effect of that is seen (when pressing said
 * button is supposed to change to another screen).
 *
 * This is almost identical to [cycleToRTScreen], except that that function
 * repeatedly presses an RT button after newly received parsed screen, while
 * this function just waits for the required screen to eventually show up.
 *
 * @param rtNavigationContext RT navigation context to use for waiting for the screen.
 * @param targetScreenType Type of the screen to wait for.
 * @throws CouldNotFindRTScreenException if no screen with the given type appeared.
 */
suspend fun waitUntilScreenAppears(rtNavigationContext: RTNavigationContext, targetScreenType: KClassifier) {
    for (numSeenScreens in 0 until rtNavigationContext.maxNumCycleAttempts) {
        val parsedScreen = rtNavigationContext.getParsedScreen()

        if (parsedScreen::class == targetScreenType)
            return

        rtNavigationContext.parsedScreenDone()
    }

    throw CouldNotFindRTScreenException(targetScreenType)
}

/**
 * Navigates through screens, entering and exiting subsections, until the target is reached.
 *
 * This differs from [cycleToRTScreen] in that only a target screen type is given.
 * A shortest path from the current screen to a screen with the given target type
 * is computed and then traversed. That path contains information about what
 * intermediate screens to cycle to and what buttons to press for cycling.
 *
 * @param rtNavigationContext RT navigation context to use for cycling through screens.
 * @param targetScreenType Type of the screen to navigate to.
 * @throws CouldNotFindRTScreenException if there is no screen of type [targetScreenType]
 *         in the internal RT navigation tree.
 */
suspend fun navigateToRTScreen(
    rtNavigationContext: RTNavigationContext,
    targetScreenType: KClassifier
) {
    // Handle special case where we currently are in a screen that could
    // not be recognized by any parser. In such a case, we just exit until
    // we end up at a recognizable screen, then continue from there.
    while (true) {
        val initialParsedScreen = rtNavigationContext.getParsedScreen()
        if (initialParsedScreen is ParsedScreen.UnrecognizedScreen) {
            rtNavigationContext.parsedScreenDone()
            rtNavigationContext.pushButton(RTNavigationButton.BACK)
        } else
            break
    }

    // Get the current screen. This will be our starting point for the path.
    val currentParsedScreen = rtNavigationContext.getParsedScreen()

    val path = findRTNavigationPath(currentParsedScreen::class, targetScreenType)
    if (path.isEmpty())
        throw CouldNotFindRTScreenException(targetScreenType)

    for (pathItem in path) {
        if ((pathItem.screenType != null) && pathItem.nextNavButton != null) {
            cycleToRTScreen(
                rtNavigationContext,
                pathItem.nextNavButton,
                pathItem.screenType
            )
        }
    }
}
