package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.ShortestPathHalf
import info.nightscout.comboctl.base.Tree
import info.nightscout.comboctl.base.findShortestPath
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.parsedScreenFlow
import kotlin.reflect.KClassifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

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

    BACK(listOf(PumpIO.Button.MENU, PumpIO.Button.UP)),
    UPDOWN(listOf(PumpIO.Button.UP, PumpIO.Button.DOWN))
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

// Tree for computing a route to navigate between screens.
// To make sense of this, it is important to keep two things in mind:
//
// 1. Transitions between screens in this tree do not have to specify
//    all screens in-between. Instead, navigating works by pressing
//    the button associated with the target screen until said screen
//    shows up. For example, when starting at the main screen (which
//    is the implicit root of the tree), navigating to the tempo basal
//    menu screen would involve pressing the MENU button repeatedly
//    until that screen appears.
// 2. Even though navigating between the menus in the Combo appears
//    as a list traversal, it can actually be modeled as a tree, where
//    navigating to the next menu screen equals descending into child
//    nodes. For example, getting from the basal rate menu screen to
//    the time and date settings menu screen requires descending into
//    child nodes by repeatedly pressing the MENU button according to
//    the tree structure. Doing this makes it easy to find a shortest
//    path between screens, since doing that in a tree is trivial.
internal val rtNavigationTree = Tree<RTNavigationScreenInfo>(
    rootValue = RTNavigationScreenInfo(ParsedScreen.MainScreen::class, buttonToReachThis = null, buttonToExit = null)
) {
    child(RTNavigationScreenInfo(ParsedScreen.QuickinfoMainScreen::class, RTNavigationButton.CHECK))
    child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRateMenuScreen::class, RTNavigationButton.MENU)) {
        child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRatePercentageScreen::class, RTNavigationButton.CHECK))
            .child(RTNavigationScreenInfo(ParsedScreen.TemporaryBasalRateDurationScreen::class, RTNavigationButton.MENU))
        child(RTNavigationScreenInfo(ParsedScreen.MyDataMenuScreen::class, RTNavigationButton.MENU)) {
            child(RTNavigationScreenInfo(ParsedScreen.MyDataBolusDataScreen::class, RTNavigationButton.CHECK))
                .child(RTNavigationScreenInfo(ParsedScreen.MyDataErrorDataScreen::class, RTNavigationButton.MENU))
                    .child(RTNavigationScreenInfo(ParsedScreen.MyDataDailyTotalsScreen::class, RTNavigationButton.MENU))
                        .child(RTNavigationScreenInfo(ParsedScreen.MyDataTbrDataScreen::class, RTNavigationButton.MENU))
            child(RTNavigationScreenInfo(ParsedScreen.BasalRate1ProgrammingMenuScreen::class, RTNavigationButton.MENU)) {
                child(RTNavigationScreenInfo(ParsedScreen.BasalRateTotalScreen::class, RTNavigationButton.CHECK))
                    .child(RTNavigationScreenInfo(ParsedScreen.BasalRateFactorSettingScreen::class, RTNavigationButton.MENU))
                child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsMenuScreen::class, RTNavigationButton.MENU))
                    .child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsHourScreen::class, RTNavigationButton.CHECK))
                        .child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsMinuteScreen::class, RTNavigationButton.MENU))
                            .child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsYearScreen::class, RTNavigationButton.MENU))
                                .child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsMonthScreen::class, RTNavigationButton.MENU))
                                    .child(RTNavigationScreenInfo(ParsedScreen.TimeAndDateSettingsDayScreen::class, RTNavigationButton.MENU))
            }
        }
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
 * Exception thrown when the RT navigation encountered an unexpected screen type.
 *
 * @property expectedScreenType Type of the screen that was expected.
 * @property encounteredScreenType Type of the screen that was encountered.
 */
class UnexpectedRTScreenException(
    val expectedScreenType: KClassifier,
    val encounteredScreenType: KClassifier
) : RTNavigationException("Unexpected RT screen; expected $expectedScreenType, encountered $encounteredScreenType")

/**
 * Exception thrown when the RT navigation could not find a screen of the searched type.
 *
 * @property targetScreenType Type of the screen that was searched.
 */
class CouldNotFindRTScreenException(val targetScreenType: KClassifier) : RTNavigationException("Could not find RT screen $targetScreenType")

/**
 * Exception thrown when in spite of repeatedly trying to exit to the main screen, no recognizable RT screen is found.
 *
 * This is different from [NoUsableRTScreenException] in that the code tried to get out
 * of whatever unrecognized part of the RT menu and failed because it kept seeing unfamiliar
 * screens, while that other exception is about not getting a specific RT screen.
 */
class CouldNotRecognizeAnyRTScreenException : RTNavigationException("Could not recognize any RT screen")

/**
 * Exception thrown when a function needed a specific screen type but could not get it.
 *
 * Typically, this happens because a display frame could not be parsed
 * (= the screen is [ParsedScreen.UnrecognizedScreen]).
 */
class NoUsableRTScreenException : RTNavigationException("No usable RT screen available")

/**
 * Context class for housing states related to navigating through RT screens.
 *
 * This wraps a [Pump] instance in a higher level interface that takes care of button
 * presses (including button combinations, for example MENU+UP for navigating back)
 * and handles the instantiation of [ParsedScreen] flows. RT navigation functions
 * like [cycleToRTScreen] use this context.
 *
 * It is possible to create multiple context instances that are associated with the same
 * pump, but it is important to make sure that at most one instance operates that pump
 * at the same time. It is therefore not recommended to create more than one context
 * for the same pump (and it rarely makes sense to do so).
 *
 * As a safeguard, [maxNumCycleAttempts] limits the amount of screen changes that
 * navigation functions like [cycleToRTScreen] or [waitUntilScreenAppears] can see
 * before they throw [CouldNotFindRTScreenException]. The default value is 20,
 * meaning that these functions can at most see 20 transitions betwen screens.
 * This prevents infinite loops in case these functions fail to find the screen
 * they are looking for.
 *
 * @property pump Pump to associate the context with.
 * @property maxNumCycleAttempts Maximum RT screen cycle count. Must be at least 1.
 */
class RTNavigationContext(
    private val pump: Pump,
    val maxNumCycleAttempts: Int = 20
) {
    init {
        require(maxNumCycleAttempts > 0)
    }

    /**
     * Returns a new [Flow] of [ParsedScreen] instances.
     *
     * See the [parsedScreenFlow] documentation for details about the flow itself.
     * This function creates one such flow whose dismissAlertAction is to press
     * the CHECK button.
     *
     * @param filterDuplicates Whether or not to filter out duplicates. Filtering is
     *        enabled by default.
     * @return The [ParsedScreen] flow.
     */
    fun getParsedScreenFlow(filterDuplicates: Boolean = true, processAlertScreens: Boolean = true) =
        parsedScreenFlow(
            pump.displayFrameFlow,
            filterDuplicates = filterDuplicates,
            processAlertScreens = processAlertScreens
        )

    suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)? = null) =
        pump.startLongRTButtonPress(button.rtButtonCodes, keepGoing)

    suspend fun stopLongButtonPress() = pump.stopLongRTButtonPress()

    suspend fun waitForLongButtonPressToFinish() = pump.waitForLongRTButtonPressToFinish()

    suspend fun shortPressButton(button: RTNavigationButton) = pump.sendShortRTButtonPress(button.rtButtonCodes)
}

/**
 * Repeatedly presses the [button] until a screen of the required [targetScreenType] appears.
 *
 * @param rtNavigationContext Context for navigating to the target screen.
 * @param button Button to press for cycling to the target screen.
 * @param targetScreenType Type of the target screen.
 * @return The last observed [ParsedScreen].
 * @throws CouldNotFindRTScreenException if the screen was not found even
 *         after this function moved [RTNavigationContext.maxNumCycleAttempts]
 *         times from screen to screen.
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun cycleToRTScreen(
    rtNavigationContext: RTNavigationContext,
    button: RTNavigationButton,
    targetScreenType: KClassifier
): ParsedScreen {
    var cycleCount = 0
    return rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
                throw CouldNotFindRTScreenException(targetScreenType)

            if (parsedScreen::class == targetScreenType) {
                true
            } else {
                rtNavigationContext.shortPressButton(button)
                cycleCount++
                false
            }
        }
}

/**
 * Keeps watching out for incoming screens until one of the desired type is observed.
 *
 * @param rtNavigationContext Context for observing incoming screens.
 * @param targetScreenType Type of the target screen.
 * @throws CouldNotFindRTScreenException if the screen was not seen even after
 *         this function observed [RTNavigationContext.maxNumCycleAttempts]
 *         screens coming in.
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun waitUntilScreenAppears(rtNavigationContext: RTNavigationContext, targetScreenType: KClassifier) {
    var cycleCount = 0
    rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
                throw CouldNotFindRTScreenException(targetScreenType)

            if (parsedScreen::class == targetScreenType) {
                true
            } else {
                cycleCount++
                false
            }
        }
}

/**
 * Navigates from the current screen to the screen of the given type.
 *
 * This performs a navigation by pressing the appropriate RT buttons to
 * transition between screens until the target screen is reached. This uses
 * an internal navigation tree to compute the shortest path from the current
 * to the target screen. If no path to the target screen can be found,
 * [CouldNotFindRTScreenException] is thrown.
 *
 * @param rtNavigationContext Context to use for navigating.
 * @param targetScreenType Type of the target screen.
 * @throws CouldNotFindRTScreenException if the screen was not seen even after
 *         this function observed [RTNavigationContext.maxNumCycleAttempts]
 *         screens coming in, or if no path from the current screen to
 *         [targetScreenType] could be found.
 * @throws CouldNotRecognizeAnyRTScreenException if the RT menu is at an
 *         unknown, unrecognized screen at the moment, and in spite of repeatedly
 *         pressing the BACK button to exit back to the main menu, the code
 *         kept seeing unrecognized screens.
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun navigateToRTScreen(
    rtNavigationContext: RTNavigationContext,
    targetScreenType: KClassifier
) {
    logger(LogLevel.DEBUG) { "About to navigate to RT screen of type $targetScreenType" }

    // Get the current screen so we know the starting point. If it is an
    // unrecognized screen, press BACK until we are at the main screen.
    var numAttemptsToRecognizeScreen = 0
    var currentParsedScreen = rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (parsedScreen is ParsedScreen.UnrecognizedScreen) {
                numAttemptsToRecognizeScreen++
                if (numAttemptsToRecognizeScreen >= rtNavigationContext.maxNumCycleAttempts)
                    throw CouldNotRecognizeAnyRTScreenException()
                rtNavigationContext.shortPressButton(RTNavigationButton.BACK)
                false
            } else {
                true
            }
        }

    logger(LogLevel.DEBUG) { "Navigation starts at screen $currentParsedScreen" }

    // Figure out the shortest path.
    var path = try {
        findRTNavigationPath(currentParsedScreen::class, targetScreenType)
    } catch (e: IllegalArgumentException) {
        // Happens when currentParsedScreen::class is not found in the navigation tree.
        setOf()
    }

    if (path.isEmpty()) {
        // If we are getting an unknown screen, try exiting by repeatedly pressing BACK.
        // cycleToRTScreen() takes care of that. If it fails to find the main screen,
        // it throws a CouldNotFindRTScreenException.

        logger(LogLevel.WARN) {
            "We are at screen of type ${currentParsedScreen::class}, which is unknown " +
            "to findRTNavigationPath(); exiting back to the main screen"
        }
        currentParsedScreen = cycleToRTScreen(rtNavigationContext, RTNavigationButton.BACK, ParsedScreen.MainScreen::class)

        // Now try again to find a path. We should get a valid path now. We would
        // not be here otherwise, since cycleToRTScreen() throws an exception then.
        path = try {
            findRTNavigationPath(currentParsedScreen::class, targetScreenType)
        } catch (e: IllegalArgumentException) {
            setOf()
        }

        if (path.isEmpty()) {
            // Should not happen due to the cycleToRTScreen() call above.
            logger(LogLevel.ERROR) { "Could not find RT navigation path even after navigating back to the main menu" }
            throw CouldNotFindRTScreenException(targetScreenType)
        }
    }

    // Navigate from the current to the target screen.
    var cycleCount = 0
    val pathIt = path.iterator()
    var nextTargetPathItem = pathIt.next()
    rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
                throw CouldNotFindRTScreenException(targetScreenType)

            logger(LogLevel.DEBUG) { "We are currently at screen $parsedScreen" }

            if (parsedScreen::class == nextTargetPathItem.screenType) {
                cycleCount = 0
                if (pathIt.hasNext()) {
                    logger(LogLevel.DEBUG) { "Reached intermediate target type ${nextTargetPathItem.screenType}" }
                    nextTargetPathItem = pathIt.next()
                } else {
                    logger(LogLevel.DEBUG) { "Target screen type reached" }
                    return@first true
                }
            }

            if (nextTargetPathItem.nextNavButton != null) {
                logger(LogLevel.DEBUG) { "Pressing button ${nextTargetPathItem.nextNavButton!!} to navigate further" }
                rtNavigationContext.shortPressButton(nextTargetPathItem.nextNavButton!!)
                cycleCount++
                false
            } else {
                logger(LogLevel.DEBUG) { "Reached end of navigation path" }
                true
            }
        }
}

/**
 * Holds down a specific button until the specified screen check callback returns true.
 *
 * This is useful for performing an ongoing activity based on the screen contents.
 * [adjustQuantityOnScreen] uses this internally for adjusting a quantiy on screen.
 * [button] is kept pressed until [checkScreen] returns true, at which point that
 * RT button is released.
 *
 * @param rtNavigationContext Context to use for the long RT button press.
 * @param button Button to long-press.
 * @param checkScreen Callback that returns false if the button shall continue
 *        to be long-pressed or true if it shall be released. The latter also
 *        causes this function to finish.
 * @return The last observed [ParsedScreen].
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun longPressRTButtonUntil(
    rtNavigationContext: RTNavigationContext,
    button: RTNavigationButton,
    checkScreen: (parsedScreen: ParsedScreen) -> Boolean
): ParsedScreen {
    var startedLongPress = false

    try {
        logger(LogLevel.DEBUG) { "Starting long RT button press:  button: $button" }
        val screenFlow = rtNavigationContext.getParsedScreenFlow()

        return screenFlow
            .first { parsedScreen ->
                if (checkScreen(parsedScreen)) {
                    return@first true
                }

                if (!startedLongPress) {
                    rtNavigationContext.startLongButtonPress(button) {
                        // Wait for a short while. This is necessary, because
                        // at each startLongButtonPress callback iteration,
                        // a command is sent to the Combo that informs it
                        // that the button is still being held down. This
                        // triggers an update in the Combo. For example, if
                        // the current screen is a TBR percentage screen,
                        // and the UP button is held down, then the percentage
                        // will be increased after that command is sent to
                        // the Combo. These commands cannot be sent too
                        // rapidly, since it takes the Combo some time to
                        // send a new screen (a screen with the incremented
                        // percentage in this TBR example) to the client.
                        // If the commands are sent too quickly, then the
                        // Combo would keep sending new screens even long
                        // after the button was released.
                        delay(110)
                        true
                    }
                    startedLongPress = true
                }

                false
            }
    } finally {
        logger(LogLevel.DEBUG) { "Stopping long RT button press:  button: $button" }
        if (startedLongPress)
            rtNavigationContext.stopLongButtonPress()
    }
}

sealed class ShortPressRTButtonsCommand {
    object DoNothing : ShortPressRTButtonsCommand()
    object Stop : ShortPressRTButtonsCommand()
    data class PressButton(val button: RTNavigationButton) : ShortPressRTButtonsCommand()
}

/**
 * Short-presses a button until the specified screen check callback returns true.
 *
 * This is the short-press counterpart to [longPressRTButtonUntil]. For each observed
 * [ParsedScreen], it invokes the specified  [checkScreen] callback. That callback then
 * returns a command, telling this function what to do next. If that comand is
 * [ShortPressRTButtonsCommand.PressButton], this function short-presses the button
 * specified in that sealed subclass, and then waits for the next [ParsedScreen].
 * If the command is [ShortPressRTButtonsCommand.Stop], this function finishes.
 * If the command is [ShortPressRTButtonsCommand.DoNothing], this function skips
 * the current [ParsedScreen]. The last command is useful for example when the
 * screen contents are blinking. By returning DoNothing, the callback effectively
 * causes this function to wait until another screen (hopefully without the blinking)
 * arrives and can be processed by that callback.
 *
 * @param rtNavigationContext Context to use for the short RT button press.
 * @param checkScreen Callback that returns the command this function shall execute next.
 * @return The last observed [ParsedScreen].
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun shortPressRTButtonsUntil(
    rtNavigationContext: RTNavigationContext,
    checkScreen: (parsedScreen: ParsedScreen) -> ShortPressRTButtonsCommand
): ParsedScreen {
    return rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            when (val command = checkScreen(parsedScreen)) {
                ShortPressRTButtonsCommand.DoNothing -> Unit
                ShortPressRTButtonsCommand.Stop -> return@first true
                is ShortPressRTButtonsCommand.PressButton -> rtNavigationContext.shortPressButton(command.button)
            }

            false
        }
}

/**
 * Adjusts a quantity that is shown currently on screen, using the specified in/decrement buttons.
 *
 * Internally, this first uses a long RT button press to quickly change the quantity
 * to be as close as possible to the [targetQuantity]. Then, with short RT button
 * presses, any leftover differences between the currently shown quantity and
 * [targetQuantity] is corrected.
 *
 * The current quantity is extracted from the current [ParsedScreen] with the
 * [getQuantity] callback. That callback returns null if the quantity currently
 * is not available (typically happens because the screen is blinking). This
 * will not cause an error; instead, this function will just wait until the
 * callback returns a non-null value.
 *
 * Some quantities may be cyclic in nature. For example, a minute value has a valid range
 * of 0-59, but if the curret value is 55, and the target value is 3, it is faster to press
 * the [incrementButton] until the value wraps around from 59 to 0 and then keeps increasing
 * to 3. The alternative would be to press the [decrementButton] 52 times, which is slower.
 * This requires a non-null [cyclicQuantityRange] value. If that argument is null, this
 * function will not do such a cyclic logic.
 *
 * @param rtNavigationContext Context to use for adjusting the quantity.
 * @param targetQuantity Quantity to set the on-screen quantity to.
 * @param incrementButton What RT button to press for incrementing the on-screen quantity.
 * @param decrementButton What RT button to press for decrementing the on-screen quantity.
 * @param cyclicQuantityRange The cyclic quantity range, or null if no such range exists.
 * @param getQuantity Callback for extracting the on-screen quantity.
 * @throws AlertScreenException if alert screens are seen.
 */
suspend fun adjustQuantityOnScreen(
    rtNavigationContext: RTNavigationContext,
    targetQuantity: Int,
    incrementButton: RTNavigationButton = RTNavigationButton.UP,
    decrementButton: RTNavigationButton = RTNavigationButton.DOWN,
    cyclicQuantityRange: Int? = null,
    getQuantity: (parsedScreen: ParsedScreen) -> Int?
) {
    fun checkIfNeedsToIncrement(currentQuantity: Int): Boolean {
        return if (cyclicQuantityRange != null) {
            val distance = (targetQuantity - currentQuantity)
            if (abs(distance) <= (cyclicQuantityRange / 2))
                (currentQuantity < targetQuantity)
            else
                (currentQuantity > targetQuantity)
        } else
            (currentQuantity < targetQuantity)
    }

    var initialQuantity = 0

    // Get the quantity that is initially shown on screen.
    // This is necessary to (a) check if anything needs to
    // be done at all and (b) decide what button to long-press
    // in the code block below.
    rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            val quantity = getQuantity(parsedScreen)
            if (quantity != null) {
                initialQuantity = quantity
                true
            } else
                false
        }

    if (initialQuantity == targetQuantity)
        return

    var needToIncrement = checkIfNeedsToIncrement(initialQuantity)

    // First phase: Adjust quantity with a long RT button press.
    // This is (much) faster than using short RT button presses,
    // but can overshoot, especially since the Combo increases the
    // increment/decrement steps over time. (That is why the
    // comparisons below are <= and >= instead of ==).
    longPressRTButtonUntil(
        rtNavigationContext,
        if (needToIncrement) incrementButton else decrementButton
    ) { parsedScreen ->
        val currentQuantity = getQuantity(parsedScreen)
        if (currentQuantity == null) {
            false
        } else {
            if (needToIncrement)
                (currentQuantity >= targetQuantity)
            else
                (currentQuantity <= targetQuantity)
        }
    }

    var lastQuantity: Int? = null

    // Observe the screens until we see a screen whose quantity
    // is the same as the previous screen's. This "debouncing" is
    // necessary since the Combo may be somewhat behind with the
    // display frames it sends to the client. This means that even
    // after the longPressRTButtonUntil() call above finished, the
    // Combo may still send several send updates, and the on-screen
    // quantity may still be in/decremented. We need to wait until
    // that in/decrementing is over before we can do any corrections
    // with short RT button presses.
    rtNavigationContext.getParsedScreenFlow(filterDuplicates = false)
        .first { parsedScreen ->
            val currentQuantity = getQuantity(parsedScreen)

            if (currentQuantity != null) {
                if (currentQuantity == lastQuantity)
                    return@first true
                else
                    lastQuantity = currentQuantity
            }

            false
        }

    // If the on-screen quantity is not the target quantity, we may
    // have overshot, or the in/decrement factor may have been increased
    // over time by the Combo. Perform short RT button  presses to nudge
    // the quantity until it reaches the target value.
    shortPressRTButtonsUntil(rtNavigationContext) { parsedScreen ->
        val currentQuantity = getQuantity(parsedScreen)

        if (currentQuantity == null) {
            ShortPressRTButtonsCommand.DoNothing
        } else if (currentQuantity == targetQuantity) {
            ShortPressRTButtonsCommand.Stop
        } else {
            needToIncrement = checkIfNeedsToIncrement(currentQuantity)
            if (needToIncrement)
                ShortPressRTButtonsCommand.PressButton(incrementButton)
            else
                ShortPressRTButtonsCommand.PressButton(decrementButton)
        }
    }
}
