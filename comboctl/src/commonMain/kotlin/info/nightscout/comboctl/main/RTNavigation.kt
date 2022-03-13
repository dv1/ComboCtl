package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.Graph
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.connectBidirectionally
import info.nightscout.comboctl.base.connectDirectionally
import info.nightscout.comboctl.base.findShortestPath
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.parsedScreenFlow
import kotlin.math.abs
import kotlin.reflect.KClassifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val logger = Logger.get("RTNavigation")

private const val WAIT_PERIOD_DURING_LONG_RT_BUTTON_PRESS_IN_MS = 110L

/**
 * RT navigation buttons.
 *
 * These are essentially the [ApplicationLayer.RTButton] values, but
 * also include combined button presses for navigating back (which
 * requires pressing both MENU and UP buttons at the same time).
 */
enum class RTNavigationButton(val rtButtonCodes: List<ApplicationLayer.RTButton>) {
    UP(listOf(ApplicationLayer.RTButton.UP)),
    DOWN(listOf(ApplicationLayer.RTButton.DOWN)),
    MENU(listOf(ApplicationLayer.RTButton.MENU)),
    CHECK(listOf(ApplicationLayer.RTButton.CHECK)),

    BACK(listOf(ApplicationLayer.RTButton.MENU, ApplicationLayer.RTButton.UP)),
    UP_DOWN(listOf(ApplicationLayer.RTButton.UP, ApplicationLayer.RTButton.DOWN))
}

internal data class RTEdgeValue(val button: RTNavigationButton, val edgeValidityCondition: EdgeValidityCondition = EdgeValidityCondition.ALWAYS) {
    enum class EdgeValidityCondition {
        ONLY_IF_COMBO_STOPPED,
        ONLY_IF_COMBO_RUNNING,
        ALWAYS
    }

    // Exclude edgeValidityCondition from comparisons. This is mainly
    // done to make it easier to test the RT navigation code.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RTEdgeValue

        if (button != other.button) return false

        return true
    }

    override fun hashCode(): Int {
        return button.hashCode()
    }
}

// Directed cyclic graph for navigating between RT screens. The edge
// values indicate what button to press to reach the edge's target node
// (= target screen). The button may have to be pressed more than once
// until the target screen appears if other screens are in between.
internal val rtNavigationGraph = Graph<KClassifier, RTEdgeValue>().apply {
    // Set up graph nodes for each ParsedScreen, to be able
    // to connect them below.
    val mainNode = node(ParsedScreen.MainScreen::class)
    val quickinfoNode = node(ParsedScreen.QuickinfoMainScreen::class)
    val tbrMenuNode = node(ParsedScreen.TemporaryBasalRateMenuScreen::class)
    val tbrPercentageNode = node(ParsedScreen.TemporaryBasalRatePercentageScreen::class)
    val tbrDurationNode = node(ParsedScreen.TemporaryBasalRateDurationScreen::class)
    val myDataMenuNode = node(ParsedScreen.MyDataMenuScreen::class)
    val myDataBolusDataMenuNode = node(ParsedScreen.MyDataBolusDataScreen::class)
    val myDataErrorDataMenuNode = node(ParsedScreen.MyDataErrorDataScreen::class)
    val myDataDailyTotalsMenuNode = node(ParsedScreen.MyDataDailyTotalsScreen::class)
    val myDataTbrDataMenuNode = node(ParsedScreen.MyDataTbrDataScreen::class)
    val basalRate1MenuNode = node(ParsedScreen.BasalRate1ProgrammingMenuScreen::class)
    val basalRateTotalNode = node(ParsedScreen.BasalRateTotalScreen::class)
    val basalRateFactorSettingNode = node(ParsedScreen.BasalRateFactorSettingScreen::class)
    val timeDateSettingsMenuNode = node(ParsedScreen.TimeAndDateSettingsMenuScreen::class)
    val timeDateSettingsHourNode = node(ParsedScreen.TimeAndDateSettingsHourScreen::class)
    val timeDateSettingsMinuteNode = node(ParsedScreen.TimeAndDateSettingsMinuteScreen::class)
    val timeDateSettingsYearNode = node(ParsedScreen.TimeAndDateSettingsYearScreen::class)
    val timeDateSettingsMonthNode = node(ParsedScreen.TimeAndDateSettingsMonthScreen::class)
    val timeDateSettingsDayNode = node(ParsedScreen.TimeAndDateSettingsDayScreen::class)

    // Below, nodes are connected. Connections are edges in the graph.

    // Main screen and quickinfo.
    connectBidirectionally(RTEdgeValue(RTNavigationButton.CHECK), RTEdgeValue(RTNavigationButton.BACK), mainNode, quickinfoNode)

    connectBidirectionally(
        RTEdgeValue(RTNavigationButton.MENU), RTEdgeValue(RTNavigationButton.BACK),
        myDataMenuNode, basalRate1MenuNode
    )

    // Connection between main menu and time and date settings menu. Note that there
    // is only this one connection to the time and date settings menu, even though it
    // is actually possible to reach that menu from for example the basal rate 1
    // programming one by pressing MENU several times. That's because depending on
    // the Combo's configuration, significantly more menus may actually lie between
    // basal rate 1 and time and date settings, causing the navigation to take
    // significantly longer. Also, in pretty much all cases, any access to the time
    // and date settings menu starts from the main menu, so it makes sense to establish
    // only one connection between the main menu and the time and date settings menu.
    connectBidirectionally(
        RTEdgeValue(RTNavigationButton.BACK), RTEdgeValue(RTNavigationButton.MENU),
        mainNode,
        timeDateSettingsMenuNode
    )

    // Connections to the TBR menu do not always exist - if the Combo
    // is stopped, the TBR menu is disabled, so create separate connections
    // for it and mark them as being invalid if the Combo is stopped to
    // prevent the RTNavigation code from traversing them if the Combo
    // is currently in the stopped state.

    // These are the TBR menu connections. In the running state, the
    // TBR menu is then directly reachable from the main menu and is
    // placed in between the main and the My Data menu.
    connectBidirectionally(
        RTEdgeValue(RTNavigationButton.MENU, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_RUNNING),
        RTEdgeValue(RTNavigationButton.BACK, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_RUNNING),
        mainNode, tbrMenuNode
    )
    connectBidirectionally(
        RTEdgeValue(RTNavigationButton.MENU, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_RUNNING),
        RTEdgeValue(RTNavigationButton.BACK, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_RUNNING),
        tbrMenuNode, myDataMenuNode
    )

    // In the stopped state, the My Data menu can directly be reached from the
    // main mode, since the TBR menu that is in between is turned off.
    connectBidirectionally(
        RTEdgeValue(RTNavigationButton.MENU, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_STOPPED),
        RTEdgeValue(RTNavigationButton.BACK, RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_STOPPED),
        mainNode, myDataMenuNode
    )

    // These are the connections between TBR screens. A specialty of these
    // screens is that transitioning between the percentage and duration
    // screens is done with the MENU screen in both directions
    // (percentage->duration and duration->percentage). The TBR menu screen
    // can be reached from both of these screens by pressing BACK. But the
    // duration screen cannot be reached directly from the TBR menu screen,
    // which is why there's a direct edge from the duration to the menu
    // screen but not one in the other direction.
    connectBidirectionally(RTEdgeValue(RTNavigationButton.CHECK), RTEdgeValue(RTNavigationButton.BACK), tbrMenuNode, tbrPercentageNode)
    connectBidirectionally(RTEdgeValue(RTNavigationButton.MENU), RTEdgeValue(RTNavigationButton.MENU), tbrPercentageNode, tbrDurationNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), tbrDurationNode, tbrMenuNode)

    // The basal rate programming screens. Going to the basal rate factors requires
    // two transitions (basal rate 1 -> basal rate total -> basal rate factor).
    // Going back requires one, but directly goes back to basal rate 1.
    connectBidirectionally(RTEdgeValue(RTNavigationButton.CHECK), RTEdgeValue(RTNavigationButton.BACK), basalRate1MenuNode, basalRateTotalNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.MENU), basalRateTotalNode, basalRateFactorSettingNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), basalRateFactorSettingNode, basalRate1MenuNode)

    // Connections between myData screens. Navigation through these screens
    // is rather straightforward. Pressing CHECK when at the my data menu
    // transitions to the bolus data screen. Pressing MENU then transitions
    // through the various myData screens. The order is: bolus data, error
    // data, daily totals, TBR data. Pressing MENU when at the TBR data
    // screen cycles back to the bolus data screen. Pressing BACK in any
    // of these screens transitions back to the my data menu screen.
    connectDirectionally(RTEdgeValue(RTNavigationButton.CHECK), myDataMenuNode, myDataBolusDataMenuNode)
    connectDirectionally(
        RTEdgeValue(RTNavigationButton.MENU),
        myDataBolusDataMenuNode, myDataErrorDataMenuNode, myDataDailyTotalsMenuNode, myDataTbrDataMenuNode
    )
    connectDirectionally(RTEdgeValue(RTNavigationButton.MENU), myDataTbrDataMenuNode, myDataBolusDataMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), myDataBolusDataMenuNode, myDataMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), myDataErrorDataMenuNode, myDataMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), myDataDailyTotalsMenuNode, myDataMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), myDataTbrDataMenuNode, myDataMenuNode)

    // Time and date settings screen. These work just like the my data screens.
    // That is: Navigating between the "inner" time and date screens works
    // by pressing MENU, and when pressing MENU at the last of these screens,
    // navigation transitions back to the first of these screens. Pressing
    // BACK transitions back to the time and date settings menu screen.
    connectDirectionally(RTEdgeValue(RTNavigationButton.CHECK), timeDateSettingsMenuNode, timeDateSettingsHourNode)
    connectDirectionally(
        RTEdgeValue(RTNavigationButton.MENU),
        timeDateSettingsHourNode, timeDateSettingsMinuteNode, timeDateSettingsYearNode,
        timeDateSettingsMonthNode, timeDateSettingsDayNode
    )
    connectDirectionally(RTEdgeValue(RTNavigationButton.MENU), timeDateSettingsDayNode, timeDateSettingsHourNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), timeDateSettingsHourNode, timeDateSettingsMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), timeDateSettingsMinuteNode, timeDateSettingsMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), timeDateSettingsYearNode, timeDateSettingsMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), timeDateSettingsMonthNode, timeDateSettingsMenuNode)
    connectDirectionally(RTEdgeValue(RTNavigationButton.BACK), timeDateSettingsDayNode, timeDateSettingsMenuNode)
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
class CouldNotFindRTScreenException(val targetScreenType: KClassifier) :
    RTNavigationException("Could not find RT screen $targetScreenType")

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
 * Remote terminal (RT) navigation context.
 *
 * This provides the necessary functionality for functions that navigate through RT screens
 * like [cycleToRTScreen]. These functions analyze incoming [ParsedScreen] instances with
 * the corresponding flow, and apply changes & transitions with the provided abstract
 * button actions.
 *
 * The button press functions are almost exactly like the ones from [PumpIO]. The only
 * difference is how buttons are specified - the underlying PumpIO functions get the
 * [RTNavigationButton.rtButtonCodes] value of their "button" arguments, and not the
 * "button" argument directly.
 */
interface RTNavigationContext {
    /**
     * Maximum number of times functions like [cycleToRTScreen] can cycle through screens.
     *
     * This is a safeguard to prevent infinite loops in case these functions like [cycleToRTScreen]
     * fail to find the screen they are looking for. This is a quantity that defines how
     * often these functions can transition to other screens without getting to the screen
     * they are looking for. Past that amount, they throw [CouldNotFindRTScreenException].
     *
     * This is always >= 1, and typically a value like 20.
     */
    val maxNumCycleAttempts: Int

    /**
     * Returns a new flow of [ParsedScreen] instances.
     *
     * See the [parsedScreenFlow] documentation for details about the flow itself.
     *
     * @param filterDuplicates Whether to filter out duplicates. Filtering is
     *   enabled by default.
     * @return The [ParsedScreen] flow.
     */
    fun getParsedScreenFlow(filterDuplicates: Boolean = true, processAlertScreens: Boolean = true): Flow<ParsedScreen>

    suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)? = null)
    suspend fun stopLongButtonPress()
    suspend fun waitForLongButtonPressToFinish()
    suspend fun shortPressButton(button: RTNavigationButton)
}

/**
 * [PumpIO] based implementation of [RTNavigationContext].
 *
 * This uses a [PumpIO] instance to pass button actions to, and provides a [ParsedScreen]
 * flow by parsing the [PumpIO.displayFrameFlow]. It is the implementation suited for
 * production use. [maxNumCycleAttempts] is set to 20 by default.
 */
class RTNavigationContextProduction(
    private val pumpIO: PumpIO,
    override val maxNumCycleAttempts: Int = 20
) : RTNavigationContext {
    init {
        require(maxNumCycleAttempts > 0)
    }

    override fun getParsedScreenFlow(filterDuplicates: Boolean, processAlertScreens: Boolean) =
        parsedScreenFlow(
            pumpIO.displayFrameFlow,
            filterDuplicates = filterDuplicates,
            processAlertScreens = processAlertScreens
        )

    override suspend fun startLongButtonPress(button: RTNavigationButton, keepGoing: (suspend () -> Boolean)?) =
        pumpIO.startLongRTButtonPress(button.rtButtonCodes, keepGoing)

    override suspend fun stopLongButtonPress() = pumpIO.stopLongRTButtonPress()

    override suspend fun waitForLongButtonPressToFinish() = pumpIO.waitForLongRTButtonPressToFinish()

    override suspend fun shortPressButton(button: RTNavigationButton) = pumpIO.sendShortRTButtonPress(button.rtButtonCodes)
}

sealed class ShortPressRTButtonsCommand {
    object DoNothing : ShortPressRTButtonsCommand()
    object Stop : ShortPressRTButtonsCommand()
    data class PressButton(val button: RTNavigationButton) : ShortPressRTButtonsCommand()
}

sealed class LongPressRTButtonsCommand {
    object ContinuePressingButton : LongPressRTButtonsCommand()
    object ReleaseButton : LongPressRTButtonsCommand()
}

/**
 * Holds down a specific button until the specified screen check callback returns true.
 *
 * This is useful for performing an ongoing activity based on the screen contents.
 * [adjustQuantityOnScreen] uses this internally for adjusting a quantity on screen.
 * [button] is kept pressed until [checkScreen] returns [LongPressRTButtonsCommand.ReleaseButton],
 * at which point that RT button is released.
 *
 * @param rtNavigationContext Context to use for the long RT button press.
 * @param button Button to long-press.
 * @param checkScreen Callback that returns whether to continue
 *   long-pressing the button or releasing it.
 * @return The last observed [ParsedScreen].
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
 */
suspend fun longPressRTButtonUntil(
    rtNavigationContext: RTNavigationContext,
    button: RTNavigationButton,
    checkScreen: (parsedScreen: ParsedScreen) -> LongPressRTButtonsCommand
): ParsedScreen {
    val channel = Channel<Boolean>(capacity = Channel.CONFLATED)
    val screenFlow = rtNavigationContext.getParsedScreenFlow()

    lateinit var lastParsedScreen: ParsedScreen

    logger(LogLevel.DEBUG) { "Long-pressing RT button $button until predicate indicates otherwise" }

    coroutineScope {
        launch {
            lastParsedScreen = screenFlow
                .first { parsedScreen ->
                    val predicateResult = checkScreen(parsedScreen)
                    val releaseButton = (predicateResult == LongPressRTButtonsCommand.ReleaseButton)
                    logger(LogLevel.VERBOSE) {
                        "Observed parsed screen $parsedScreen while long-pressing RT button; predicate result = $predicateResult"
                    }
                    channel.send(releaseButton)
                    return@first releaseButton
                }
        }

        launch {
            var inactivityCompensationStage = 0

            logger(LogLevel.VERBOSE) { "Started long press RT button coroutine" }
            rtNavigationContext.startLongButtonPress(button) {
                // Check if we need to stop. We have to handle a special
                // case here though because of the way the Combo's UX
                // works. When holding down a button, there is one update,
                // followed by a period of inactivity, followed by more
                // updates. The Combo does this because otherwise it would not
                // be possible for the user to reliably specify whether a button
                // press is a short or a long one. During the inactivity
                // period, there is no information from the Combo, no
                // RT button confirmation. But we have to keep sending
                // RT_BUTTON_STATUS packets during this period, otherwise
                // the inactivity period never ends. We solve this by adding
                // a special case: During that period, don't suspend until
                // the channel receives something. Instead, if the channel
                // did not receive anything, behave as if the channel received
                // "false". That way, the code below will run, and a new
                // RT_BUTTON_STATUS will be sent, and this will go on until
                // the channel actually receives something.
                val stop = if (inactivityCompensationStage == 2) {
                    val receiveAttemptResult = channel.tryReceive()
                    if (!receiveAttemptResult.isSuccess) {
                        false
                    } else
                        receiveAttemptResult.getOrThrow()
                } else
                    channel.receive()
                if (inactivityCompensationStage < 3)
                    inactivityCompensationStage++

                if (!stop) {
                    // In here, we wait for a short while. This is necessary,
                    // because at each startLongButtonPress callback iteration,
                    // a command is sent to the Combo that informs it that the
                    // button is still being held down. This triggers an update
                    // in the Combo. For example, if the current screen is a
                    // TBR percentage screen, and the UP button is held down,
                    // then the percentage will be increased after that command
                    // is sent to the Combo. These commands cannot be sent too
                    // rapidly, since it takes the Combo some time to  send a
                    // new screen (a screen with the incremented percentage in
                    // this TBR example) to the client. If the commands are sent
                    // too quickly, then the Combo would keep sending new
                    // screens even long after the button was released.
                    delay(WAIT_PERIOD_DURING_LONG_RT_BUTTON_PRESS_IN_MS)
                }

                return@startLongButtonPress !stop
            }
            rtNavigationContext.waitForLongButtonPressToFinish()
            logger(LogLevel.VERBOSE) { "Stopped long press RT button coroutine" }
        }
    }

    logger(LogLevel.DEBUG) { "Long-pressing RT button $button stopped after predicate returned true" }

    return lastParsedScreen
}

/**
 * Short-presses a button until the specified screen check callback returns true.
 *
 * This is the short-press counterpart to [longPressRTButtonUntil]. For each observed
 * [ParsedScreen], it invokes the specified  [processScreen] callback. That callback
 * then returns a command, telling this function what to do next. If that command is
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
 * @param processScreen Callback that returns the command this function shall execute next.
 * @return The last observed [ParsedScreen].
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
 */
suspend fun shortPressRTButtonsUntil(
    rtNavigationContext: RTNavigationContext,
    processScreen: (parsedScreen: ParsedScreen) -> ShortPressRTButtonsCommand
): ParsedScreen {
    logger(LogLevel.DEBUG) { "Repeatedly short-pressing RT button according to callback commands" }
    return rtNavigationContext.getParsedScreenFlow(filterDuplicates = false)
        .first { parsedScreen ->
            logger(LogLevel.VERBOSE) { "Got new screen $parsedScreen" }

            val command = processScreen(parsedScreen)
            logger(LogLevel.VERBOSE) { "Short-press RT button callback returned $command" }

            when (command) {
                ShortPressRTButtonsCommand.DoNothing -> Unit
                ShortPressRTButtonsCommand.Stop -> return@first true
                is ShortPressRTButtonsCommand.PressButton -> rtNavigationContext.shortPressButton(command.button)
            }

            false
        }
}

/**
 * Repeatedly presses the [button] until a screen of the required [targetScreenType] appears.
 *
 * @param rtNavigationContext Context for navigating to the target screen.
 * @param button Button to press for cycling to the target screen.
 * @param targetScreenType Type of the target screen.
 * @return The last observed [ParsedScreen].
 * @throws CouldNotFindRTScreenException if the screen was not found even
 *   after this function moved [RTNavigationContext.maxNumCycleAttempts]
 *   times from screen to screen.
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
 */
suspend fun cycleToRTScreen(
    rtNavigationContext: RTNavigationContext,
    button: RTNavigationButton,
    targetScreenType: KClassifier
): ParsedScreen {
    logger(LogLevel.DEBUG) { "Running shortPressRTButtonsUntil() until screen of type $targetScreenType is observed" }
    var cycleCount = 0
    return shortPressRTButtonsUntil(rtNavigationContext) { parsedScreen ->
        if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
            throw CouldNotFindRTScreenException(targetScreenType)

        when (parsedScreen::class) {
            targetScreenType -> {
                logger(LogLevel.DEBUG) { "Target screen of type $targetScreenType reached; cycleCount = $cycleCount" }
                ShortPressRTButtonsCommand.Stop
            }
            else -> {
                cycleCount++
                logger(LogLevel.VERBOSE) { "Did not yet reach target screen type; cycleCount increased to $cycleCount" }
                ShortPressRTButtonsCommand.PressButton(button)
            }
        }
    }
}

/**
 * Keeps watching out for incoming screens until one of the desired type is observed.
 *
 * @param rtNavigationContext Context for observing incoming screens.
 * @param targetScreenType Type of the target screen.
 * @return The last observed [ParsedScreen], which is the screen this
 *   function was waiting for.
 * @throws CouldNotFindRTScreenException if the screen was not seen even after
 *   this function observed [RTNavigationContext.maxNumCycleAttempts]
 *   screens coming in.
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
 */
suspend fun waitUntilScreenAppears(
    rtNavigationContext: RTNavigationContext,
    targetScreenType: KClassifier
): ParsedScreen {
    logger(LogLevel.DEBUG) { "Observing incoming parsed screens and waiting for screen of type $targetScreenType to appear" }
    var cycleCount = 0
    return rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
                throw CouldNotFindRTScreenException(targetScreenType)

            if (parsedScreen::class == targetScreenType) {
                logger(LogLevel.DEBUG) { "Target screen of type $targetScreenType appeared; cycleCount = $cycleCount" }
                true
            } else {
                logger(LogLevel.VERBOSE) { "Target screen type did not appear yet; cycleCount increased to $cycleCount" }
                cycleCount++
                false
            }
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
 * of 0-59, but if the current value is 55, and the target value is 3, it is faster to press
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
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
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

    logger(LogLevel.DEBUG) {
        "Adjusting quantity on RT screen; targetQuantity = $targetQuantity; " +
        "increment / decrement buttons = $incrementButton / decrementButton; " +
        "cyclicQuantityRange = $cyclicQuantityRange"
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

    logger(LogLevel.DEBUG) { "Initial observed quantity: $initialQuantity" }

    if (initialQuantity == targetQuantity) {
        logger(LogLevel.DEBUG) { "Initial quantity is already the target quantity; nothing to do" }
        return
    }

    var needToIncrement = checkIfNeedsToIncrement(initialQuantity)
    logger(LogLevel.DEBUG) {
        "First phase; long-pressing RT button to " +
        "${if (needToIncrement) "increment" else "decrement"} quantity"
    }

    // First phase: Adjust quantity with a long RT button press.
    // This is (much) faster than using short RT button presses,
    // but can overshoot, especially since the Combo increases the
    // increment/decrement steps over time.
    longPressRTButtonUntil(
        rtNavigationContext,
        if (needToIncrement) incrementButton else decrementButton
    ) { parsedScreen ->
        val currentQuantity = getQuantity(parsedScreen)
        logger(LogLevel.VERBOSE) { "Current quantity in first phase: $currentQuantity; need to increment: $needToIncrement" }
        if (currentQuantity == null) {
            LongPressRTButtonsCommand.ContinuePressingButton
        } else {
            // If we are incrementing, and did not yet reach the
            // quantity, then we expect checkIfNeedsToIncrement()
            // to indicate that further incrementing is required.
            // The opposite is also true: If we are decrementing,
            // and didn't reach the quantity yet, we expect
            // checkIfNeedsToIncrement() to return false. We use
            // this to determine if we need to continue long-pressing
            // the RT button. If the current quantity is at the
            // target, we don't have to anymore. And if we overshot,
            // checkIfNeedsToIncrement() will return the opposite
            // of what we expect. In both of these cases, keepPressing
            // will be set to false, indicating that the long RT
            // button press needs to stop.
            val keepPressing =
                if (currentQuantity == targetQuantity)
                    false
                else if (needToIncrement)
                    checkIfNeedsToIncrement(currentQuantity)
                else
                    !checkIfNeedsToIncrement(currentQuantity)

            if (keepPressing)
                LongPressRTButtonsCommand.ContinuePressingButton
            else
                LongPressRTButtonsCommand.ReleaseButton
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

            logger(LogLevel.DEBUG) {
                "Observed quantity after long-pressing RT button: " +
                "last / current quantity: $lastQuantity / $currentQuantity"
            }

            if (currentQuantity != null) {
                if (currentQuantity == lastQuantity)
                    return@first true
                else
                    lastQuantity = currentQuantity
            }

            false
        }

    if (lastQuantity == targetQuantity) {
        logger(LogLevel.DEBUG) { "Last seen quantity $lastQuantity is the target quantity; adjustment finished" }
        return
    }

    logger(LogLevel.DEBUG) {
        "Second phase: last seen quantity $lastQuantity is not the target quantity; " +
        "short-pressing RT button(s) to finetune it"
    }

    // If the on-screen quantity is not the target quantity, we may
    // have overshot, or the in/decrement factor may have been increased
    // over time by the Combo. Perform short RT button  presses to nudge
    // the quantity until it reaches the target value.
    shortPressRTButtonsUntil(rtNavigationContext) { parsedScreen ->
        val currentQuantity = getQuantity(parsedScreen)

        val command = if (currentQuantity == null) {
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

        logger(LogLevel.VERBOSE) { "Observed quantity $currentQuantity during finetuning; issuing command $command" }

        command
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
 * @return The target screen.
 * @throws CouldNotFindRTScreenException if the screen was not seen even after
 *   this function observed [RTNavigationContext.maxNumCycleAttempts]
 *   screens coming in, or if no path from the current screen to
 *   [targetScreenType] could be found.
 * @throws CouldNotRecognizeAnyRTScreenException if the RT menu is at an
 *   unknown, unrecognized screen at the moment, and in spite of repeatedly
 *   pressing the BACK button to exit back to the main menu, the code
 *   kept seeing unrecognized screens.
 * @throws info.nightscout.comboctl.parser.AlertScreenException if alert screens are seen.
 */
suspend fun navigateToRTScreen(
    rtNavigationContext: RTNavigationContext,
    targetScreenType: KClassifier,
    isComboStopped: Boolean = false
): ParsedScreen {
    logger(LogLevel.DEBUG) { "About to navigate to RT screen of type $targetScreenType" }

    // Get the current screen to know the starting point. If it is an
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

    if (currentParsedScreen::class == targetScreenType) {
        logger(LogLevel.DEBUG) { "Already at target; exiting" }
        return currentParsedScreen
    }

    logger(LogLevel.DEBUG) { "Navigation starts at screen of type ${currentParsedScreen::class} and ends at screen of type $targetScreenType" }

    // Figure out the shortest path.
    var path = try {
        findShortestRtPath(currentParsedScreen::class, targetScreenType, isComboStopped)
    } catch (e: IllegalArgumentException) {
        // Happens when currentParsedScreen::class or targetScreenType are not found in the navigation tree.
        null
    }

    if (path?.isEmpty() ?: false)
        return currentParsedScreen

    if (path == null) {
        // If we reach this place, then the currentParsedScreen was recognized by the parser,
        // but there is no known path in the rtNavigationGraph to get from there to the target.
        // Try exiting by repeatedly pressing BACK. cycleToRTScreen() takes care of that.
        // If it fails to find the main screen, it throws a CouldNotFindRTScreenException.

        logger(LogLevel.WARN) {
            "We are at screen of type ${currentParsedScreen::class}, which is unknown " +
                    "to findRTNavigationPath(); exiting back to the main screen"
        }
        currentParsedScreen = cycleToRTScreen(
            rtNavigationContext,
            RTNavigationButton.BACK,
            ParsedScreen.MainScreen::class
        )

        // Now try again to find a path. We should get a valid path now. We would
        // not be here otherwise, since cycleToRTScreen() throws an exception then.
        path = try {
            findShortestRtPath(currentParsedScreen::class, targetScreenType, isComboStopped)
        } catch (e: IllegalArgumentException) {
            listOf()
        }

        if (path == null) {
            // Should not happen due to the cycleToRTScreen() call above.
            logger(LogLevel.ERROR) { "Could not find RT navigation path even after navigating back to the main menu" }
            throw CouldNotFindRTScreenException(targetScreenType)
        }
    }

    // Navigate from the current to the target screen.
    var cycleCount = 0
    val pathIt = path.iterator()
    var nextPathItem = pathIt.next()
    return rtNavigationContext.getParsedScreenFlow()
        .first { parsedScreen ->
            if (cycleCount >= rtNavigationContext.maxNumCycleAttempts)
                throw CouldNotFindRTScreenException(targetScreenType)

            logger(LogLevel.DEBUG) { "We are currently at screen $parsedScreen" }

            // A path item's targetNodeValue is the screen type we are trying
            // to reach, and the edgeValue is the RT button to press to reach it.
            // We stay at the same path item until we reach the screen type that
            // is specified by targetNodeValue. When that happens, we move on
            // to the next path item. Importantly, we _first_ move on to the next
            // item, and _then_ send the short RT button press based on that next
            // item, to avoid sending the RT button from the incorrect path item.
            // Example: Path item 1 contains target screen type A and RT button
            // MENU. Path item 2 contains target screen type B and RT button CHECK.
            // On every iteration, we first check if the current screen is of type
            // A. If it isn't, we need to press MENU again and check in the next
            // iteration again. If it is of type A however, then pressing MENU
            // would be incorrect, since we already are at A. Instead, we _first_
            // must move on to the next path item, and _that_ one says to press
            // CHECK until type B is reached.

            val nextTargetScreenTypeInPath = nextPathItem.targetNodeValue

            if (parsedScreen::class == nextTargetScreenTypeInPath) {
                cycleCount = 0
                if (pathIt.hasNext()) {
                    nextPathItem = pathIt.next()
                    logger(LogLevel.DEBUG) {
                        "Reached screen type $nextTargetScreenTypeInPath in path; " +
                        "continuing to ${nextPathItem.targetNodeValue}"
                    }
                } else {
                    // If this is the last path item, it implies
                    // that we reached our destination.
                    logger(LogLevel.DEBUG) { "Target screen type $targetScreenType reached" }
                    return@first true
                }
            }

            val navButtonToPress = nextPathItem.edgeValue.button
            logger(LogLevel.DEBUG) { "Pressing button $navButtonToPress to navigate further" }
            rtNavigationContext.shortPressButton(navButtonToPress)

            cycleCount++
            return@first false
        }
}

internal fun findShortestRtPath(from: KClassifier, to: KClassifier, isComboStopped: Boolean) =
    rtNavigationGraph.findShortestPath(from, to) {
        when (it.edgeValidityCondition) {
            RTEdgeValue.EdgeValidityCondition.ALWAYS -> true
            RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_RUNNING -> !isComboStopped
            RTEdgeValue.EdgeValidityCondition.ONLY_IF_COMBO_STOPPED -> isComboStopped
        }
    }