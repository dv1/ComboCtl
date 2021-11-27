package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.ComboIOException
import info.nightscout.comboctl.base.DateTime
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.TransportLayerIO
import info.nightscout.comboctl.base.toStringWithDecimal
import info.nightscout.comboctl.parser.AlertScreenContent
import info.nightscout.comboctl.parser.AlertScreenException
import info.nightscout.comboctl.parser.ParsedScreen
import kotlin.reflect.KClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val logger = Logger.get("PumpCommandDispatcher")

/** The number of integers in a basal profile. */
const val NUM_BASAL_PROFILE_FACTORS = 24

private const val NUM_IDEMPOTENT_COMMAND_DISPATCH_ATTEMPTS = 10

private const val DELAY_IN_MS_BETWEEN_COMMAND_DISPATCH_ATTEMPTS = 2000L

/**
 * Exception thrown when something goes wrong with a bolus delivery.
 *
 * @param totalAmount Total bolus amount that was supposed to be delivered.
 * @param message The detail message.
 */
open class BolusDeliveryException(val totalAmount: Int, message: String) : ComboException(message)

/**
 * Exception thrown when the Combo did not deliver the bolus at all.
 *
 * @param totalAmount Total bolus amount that was supposed to be delivered.
 */
class BolusNotDeliveredException(totalAmount: Int) :
    BolusDeliveryException(totalAmount, "Could not deliver bolus amount of ${totalAmount.toStringWithDecimal(1)} IU")

/**
 * Exception thrown when the bolus delivery was cancelled.
 *
 * @param deliveredAmount Bolus amount that was delivered before the bolus was cancelled.
 * @param totalAmount Total bolus amount that was supposed to be delivered.
 */
class BolusCancelledByUserException(deliveredAmount: Int, totalAmount: Int) :
    BolusDeliveryException(
        totalAmount,
        "Bolus cancelled (delivered amount: ${deliveredAmount.toStringWithDecimal(1)} IU  " +
                "total programmed amount: ${totalAmount.toStringWithDecimal(1)} IU"
    )

/**
 * Exception thrown when the bolus delivery was aborted due to an error.
 *
 * @param deliveredAmount Bolus amount that was delivered before the bolus was aborted.
 * @param totalAmount Total bolus amount that was supposed to be delivered.
 */
class BolusAbortedDueToErrorException(deliveredAmount: Int, totalAmount: Int) :
    BolusDeliveryException(
        totalAmount,
        "Bolus aborted due to an error (delivered amount: ${deliveredAmount.toStringWithDecimal(1)} IU  " +
                "total programmed amount: ${totalAmount.toStringWithDecimal(1)} IU"
    )

object RTCommandProgressStage {
    /**
     * Basal profile setting/getting stage.
     *
     * @property numSetFactors How many basal rate factors have been accessed by now.
     *           When the basal profile has been fully accessed, this value equals
     *           the value of totalNumFactors.
     * @property totalNumFactors Total number of basal rate factors.
     */
    data class BasalProfileAccess(val numSetFactors: Int, val totalNumFactors: Int = NUM_BASAL_PROFILE_FACTORS) :
        ProgressStage("basalProfileAccess")

    /**
     * TBR percentage setting stage.
     *
     * @property settingProgress How far along the TBR percentage setting is, in the 0-100 range.
     *           0 = procedure started. 100 = TBR percentage setting finished.
     */
    data class SettingTBRPercentage(val settingProgress: Int) : ProgressStage("settingTBRPercentage")

    /**
     * TBR duration setting stage.
     *
     * @property settingProgress How far along the TBR duration setting is, in the 0-100 range.
     *           0 = procedure started. 100 = TBR duration setting finished.
     */
    data class SettingTBRDuration(val settingProgress: Int) : ProgressStage("settingTBRDuration")

    /**
     * Bolus delivery stage.
     *
     * The amounts are given in 0.1 IU units. For example, "57" means 5.7 IU.
     *
     * @property deliveredAmount How many units have been delivered so far.
     *           This is always <= totalAmount.
     * @property totalAmount Total amount of bolus units.
     */
    data class DeliveringBolus(val deliveredAmount: Int, val totalAmount: Int) : ProgressStage("deliveringBolus")

    /**
     * TDD fetching history stage.
     *
     * @property historyEntryIndex Index of the history entry that was just fetched.
     *           Valid range is 1 to [totalNumEntries].
     * @property totalNumEntries Total number of entries in the history.
     */
    data class FetchingTDDHistory(val historyEntryIndex: Int, val totalNumEntries: Int) : ProgressStage("fetchingTDDHistory")

    /**
     * TBR fetching history stage.
     *
     * @property historyEntryIndex Index of the history entry that was just fetched.
     *           Valid range is 1 to [totalNumEntries].
     * @property totalNumEntries Total number of entries in the history.
     */
    data class FetchingTBRHistory(val historyEntryIndex: Int, val totalNumEntries: Int) : ProgressStage("fetchingTBRHistory")

    /**
     * SetDateTime stage when the current hour is set.
     */
    object SettingDateTimeHour : ProgressStage("settingDateTimeHour")

    /**
     * SetDateTime stage when the current minute is set.
     */
    object SettingDateTimeMinute : ProgressStage("settingDateTimeMinute")

    /**
     * SetDateTime stage when the current year is set.
     */
    object SettingDateTimeYear : ProgressStage("settingDateTimeYear")

    /**
     * SetDateTime stage when the current month is set.
     */
    object SettingDateTimeMonth : ProgressStage("settingDateTimeMonth")

    /**
     * SetDateTime stage when the current day is set.
     */
    object SettingDateTimeDay : ProgressStage("settingDateTimeDay")
}

/**
 * Class for dispatching high level commands using a [Pump] instance.
 *
 * This takes a connected [Pump] instance and translates high level commands
 * (setting a basal profile for example) into commands the pump understands.
 * These may be direct command-mode commands if these are available, or they
 * may be simulated button presses as if the user were operating the pump in
 * the remote terminal mode. The methods that dispatch these commands
 * automatically switch to the remote terminal or command mode, depending on
 * what mode is required for the command.
 *
 * Only one command can be dispatched at the same time. Also, while this is
 * happening, [pump]'s methods must not be used except for calls that don't
 * initiate communication with the pump and only return state from [pump]
 * itself. This is because concurrent operation is not possible with the
 * pump and would otherwise lead to undefined behavior.
 *
 * The methods assume that the pump is already connected.
 *
 * @property pump [Pump] instance to use for dispatching commands.
 */
class PumpCommandDispatcher(private val pump: Pump, private val onEvent: (event: Event) -> Unit = { }) {
    private val rtNavigationContext = RTNavigationContext(pump)
    private var parsedScreenFlow = rtNavigationContext.getParsedScreenFlow()
    private val commandMutex = Mutex()

    // Used for counting how many times a RT alert screen was dismissed by a button press.
    private var dismissalCount = 0
    // Used in handleAlertScreenContent() to check if the current alert
    // screen contains the same alert as the previous one.
    private var lastObservedAlertScreenContent: AlertScreenContent? = null

    private val mutableCommandDispatchState = MutableStateFlow(DispatchState.IDLE)

    private val basalProfileAccessReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.BasalProfileAccess::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        // Basal profile access progress is determined by the single
        // stage in the reporter, which is BasalProfileAccess.
        // That stage contains how many basal profile factors have
        // been accessed so far, which is suitable for a progress
        // indicator, so we use that for the overall progress.
        when (stage) {
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.BasalProfileAccess ->
                stage.numSetFactors.toDouble() / stage.totalNumFactors.toDouble()
            else -> 0.0
        }
    }

    private val tbrProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.SettingTBRPercentage::class,
            RTCommandProgressStage.SettingTBRDuration::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        // TBR progress is divided in two stages, each of which have
        // their own individual progress. Combine them by letting the
        // SettingTBRPercentage stage cover the 0.0 - 0.5 progress
        // range, and SettingTBRDuration cover the remaining 0.5 -1.0
        // progress range.
        when (stage) {
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.SettingTBRPercentage ->
                0.0 + stage.settingProgress.toDouble() / 100.0 * 0.5
            is RTCommandProgressStage.SettingTBRDuration ->
                0.5 + stage.settingProgress.toDouble() / 100.0 * 0.5
            else -> 0.0
        }
    }

    private val bolusDeliveryProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.DeliveringBolus::class
        ),
        Unit
    ) { _: Int, _: Int, stage: ProgressStage, _: Unit ->
        // Bolus delivery progress is determined by the single
        // stage in the reporter, which is DeliveringBolus.
        // That stage contains how many IU have been delivered
        // so far, which is suitable for a progress indicator,
        // so we use that for the overall progress.
        when (stage) {
            BasicProgressStage.Finished,
            BasicProgressStage.Aborted -> 1.0
            is RTCommandProgressStage.DeliveringBolus ->
                stage.deliveredAmount.toDouble() / stage.totalAmount.toDouble()
            else -> 0.0
        }
    }

    private class HistoryProgressContext(
        val enabledParts: Set<HistoryPart>,
        var curHistoryPart: Int = 0,
        var prevObservedStageType: KClassifier = Nothing::class
    )

    private val historyProgressReporter = ProgressReporter<HistoryProgressContext>(
        listOf(
            RTCommandProgressStage.FetchingTDDHistory::class,
            RTCommandProgressStage.FetchingTBRHistory::class
        ),
        HistoryProgressContext(setOf(), 0, RTCommandProgressStage.FetchingTDDHistory::class)
    ) {
        _: Int, _: Int, stage: ProgressStage, context: HistoryProgressContext ->
        // History fetch progress is more complex than that of other
        // reporters because the fetch process can be configured
        // such that only parts of the history are fetched. This
        // has to be taken into account here. The progress is divided
        // into the enabled parts. Up to 3 parts can be enabled (those
        // are the 3 parts from the HistoryPart enum). Divide the
        // 0.0 - 1.0 progress range equally among the parts that are
        // enabled (= contained in the context.enabledParts set).
        // We also have to keep track of when progress moves on to
        // another part. For that purpose, the context stores the
        // type of the last observed stage. If the stage changes
        // (for example, from FetchingTDDHistory to FetchingTBRHistory),
        // we consider this a transition to the next part. (The
        // start case is covered by using Nothing::class as the special
        // initial type stored in prevObservedStageType.)
        if (context.enabledParts.isEmpty()) {
            0.0
        } else {
            if (context.prevObservedStageType == Nothing::class)
                context.curHistoryPart = 0
            else if (context.prevObservedStageType != stage::class)
                context.curHistoryPart++

            context.prevObservedStageType = stage::class

            val numParts = context.enabledParts.size.toDouble()
            val progressOffset = context.curHistoryPart.toDouble() / numParts

            when (stage) {
                is RTCommandProgressStage.FetchingTDDHistory ->
                    progressOffset + stage.historyEntryIndex.toDouble() / stage.totalNumEntries.toDouble() / numParts
                is RTCommandProgressStage.FetchingTBRHistory ->
                    progressOffset + stage.historyEntryIndex.toDouble() / stage.totalNumEntries.toDouble() / numParts
                else -> 0.0
            }
        }
    }

    private val setDateTimeProgressReporter = ProgressReporter<Unit>(
        listOf(
            RTCommandProgressStage.SettingDateTimeHour::class,
            RTCommandProgressStage.SettingDateTimeMinute::class,
            RTCommandProgressStage.SettingDateTimeYear::class,
            RTCommandProgressStage.SettingDateTimeMonth::class,
            RTCommandProgressStage.SettingDateTimeDay::class
        ),
        Unit
    )

    /**
     * Exception thrown when an idempotent command failed every time.
     *
     * Idempotent commands are retried multiple times if they fail. If all attempts
     * fail, the dispatcher gives up, and throws this exception instead.
     */
    class CommandExecutionAttemptsFailedException :
        ComboException("All attempts to execute the command failed")

    /**
     * Events that can occur during operation and are shown through RT warning screens.
     *
     * These are announced as remote terminal warning screens and are automatically
     * dismissed. Then, they are forwarded through the [onEvent] property, and the
     * command that was being executed at the time of the event is retried if
     * it is an idempotent command.
     */
    enum class Event {
        BATTERY_LOW,
        RESERVOIR_LOW
    }

    enum class DispatchState {
        IDLE,
        DISPATCHING
    }

    /**
     * [StateFlow] for notifying subscribers when a command is being dispatched.
     */
    val commandDispatchState: StateFlow<DispatchState> = mutableCommandDispatchState.asStateFlow()

    /**
     * [StateFlow] for reporting progress during the [setBasalProfile] and [getBasalProfile] calls.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow only consists of one stage (aside from Finished/Aborted/Idle),
     * and that is [RTCommandProgressStage.BasalProfileAccess].
     */
    val basalProfileAccessFlow = basalProfileAccessReporter.progressFlow

    /**
     * Sets the Combo's basal profile via the remote terminal (RT) mode.
     *
     * This function suspends until the basal profile is fully set. The
     * [basalProfileAccessFlow] can be used to get informed about the basal
     * profile setting progress. Since setting a profile can take quite a
     * while, it is recommended to make use of this to show some sort of progress
     * indicator on a GUI.
     *
     * The supplied [basalProfile] must contain exactly 24 integers (one for
     * each basal profile factor). Each factor is an integer-encoded-decimal.
     * The last 3 digits of the integer make up the 3 most significant fractional
     * digits of a decimal. For example, 10 IU are encoded as 10000, 2.5 IU are
     * encoded as 2500, 0.06 IU are encoded as 60 etc.
     *
     * The total supported range is 0.0 IU to 10 IU (inclusive). The following
     * IU ranges are supported for each factor, along with the granularity:
     *
     *   0.00 IU to 0.05 IU  : increment in 0.05 IU steps
     *   0.05 IU to 1.00 IU  : increment in 0.01 IU steps
     *   1.00 IU to 10.00 IU : increment in 0.05 IU steps
     *
     * If [carryOverLastFactor] is set to true (the default value), this function
     * moves between basal profile factors by pressing the UP and DOWN buttons
     * simultaneously instead of the MENU button. This copies over the last
     * factor that was being programmed in to the next factor. If this is false,
     * the MENU key is pressed. The pump then does not carry over anything to the
     * next screen; instead, the currently programmed in factor shows up.
     * Typically, carrying over the last factor is faster, which is why this is
     * set to true by default. There might be corner cases where setting this to
     * false results in faster execution, but at the moment, none are known.
     *
     * @param basalProfile Basal profile to set.
     * @param carryOverLastFactor If set to true, previously programmed in factors
     *        are carried to the next factor while navigating through the profile.
     * @throws IllegalArgumentException if the basal profile does not contain
     *         exactly 24 values (the factors), or if at least one of the factors
     *         is <0, or if the factors aren't correctly rounded (for example,
     *         when trying to use 0.03 as one of the 24 factors).
     * @throws AlertScreenException if an alert occurs during this call.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun setBasalProfile(basalProfile: List<Int>, carryOverLastFactor: Boolean = true) =
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, isIdempotent = true) {
        require(basalProfile.size == NUM_BASAL_PROFILE_FACTORS)

        basalProfile.forEachIndexed { index, factor ->
            require(factor >= 0) { "Basal profile factor #$index is <0 (value: $factor)" }
            require(
                ((factor >= 50) && (factor <= 1000) && ((factor % 10) == 0)) || // 0.05 to 1 IU range rounding check with the 0.01 IU steps
                ((factor >= 1000) && (factor <= 10000) && ((factor % 50) == 0)) // 1 to 10 IU range rounding check with the 0.05 IU steps
            ) { "Basal profile factor #$index is not correctly rounded (value: $factor)" }
        }

        basalProfileAccessReporter.reset(Unit)

        basalProfileAccessReporter.setCurrentProgressStage(RTCommandProgressStage.BasalProfileAccess(0))

        try {
            navigateToRTScreen(rtNavigationContext, ParsedScreen.BasalRateFactorSettingScreen::class)

            // Store the hours at which the current basal rate factor
            // begins so we can be sure that during screen cycling we
            // actually get to the next factor (which begins at
            // different hours).
            var previousBeginHour = (parsedScreenFlow.first() as ParsedScreen.BasalRateFactorSettingScreen).beginTime.hour

            for (index in basalProfile.indices) {
                val basalFactor = basalProfile[index]
                adjustQuantityOnScreen(rtNavigationContext, basalFactor) {
                    (it as ParsedScreen.BasalRateFactorSettingScreen).numUnits
                }

                basalProfileAccessReporter.setCurrentProgressStage(RTCommandProgressStage.BasalProfileAccess(index + 1))

                // By pushing MENU or UPDOWN we move to the next basal rate factor.
                // If we are at the last factor, and are about to transition back to
                // the first one again, we always press MENU to make sure the first
                // factor isn't overwritten by the last factor that got carried over.
                rtNavigationContext.shortPressButton(
                    if (carryOverLastFactor && (index != (basalProfile.size - 1)))
                        RTNavigationButton.UPDOWN
                    else
                        RTNavigationButton.MENU
                )

                // Wait until we actually get a different BasalRateFactorSettingScreen.
                // The pump might send us the same screen multiple times, because it
                // might be blinking, so it is important to wait until the button press
                // above actually resulted in a change to the screen with the next factor.
                parsedScreenFlow
                    .first { parsedScreen ->
                        parsedScreen as ParsedScreen.BasalRateFactorSettingScreen
                        if (parsedScreen.beginTime.hour == previousBeginHour) {
                            false
                        } else {
                            previousBeginHour = parsedScreen.beginTime.hour
                            true
                        }
                    }
            }

            // All factors are set. Press CHECK once to get back to the total
            // basal rate screen, and then CHECK again to store the new profile
            // and return to the main menu.

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.BasalRateTotalScreen::class)

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

            basalProfileAccessReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            basalProfileAccessReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * Sets the Combo's basal profile via the remote terminal (RT) mode.
     *
     * This function suspends until the basal profile is fully retrieved. The
     * [basalProfileAccessFlow] can be used to get informed about the basal
     * profile retrieval progress. Since getting a profile can take quite a
     * while, it is recommended to make use of this to show some sort of progress
     * indicator on a GUI.
     *
     * The retrieved basal profile always contains exactly 24 integers (one for
     * each basal profile factor). Each factor is an integer-encoded-decimal.
     * The last 3 digits of the integer make up the 3 most significant fractional
     * digits of a decimal. For example, 10 IU are encoded as 10000, 2.5 IU are
     * encoded as 2500, 0.06 IU are encoded as 60 etc. For the list of valid
     * value ranges to expect, see the [setBasalProfile] documentation.
     *
     * @return Basal profile, as 24 IU decimals, encoded as integers.
     * @throws AlertScreenException if an alert occurs during this call.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     * @throws UnexpectedRTScreenException if during the basal profile
     *         retrieval, an unexpected RT screen is encountered.
     */
    suspend fun getBasalProfile() = dispatchCommand<List<Int>>(PumpIO.Mode.REMOTE_TERMINAL, isIdempotent = true) {
        basalProfileAccessReporter.reset(Unit)

        basalProfileAccessReporter.setCurrentProgressStage(RTCommandProgressStage.BasalProfileAccess(0))

        try {
            val basalProfile = MutableList(NUM_BASAL_PROFILE_FACTORS) { -1 }

            navigateToRTScreen(rtNavigationContext, ParsedScreen.BasalRateFactorSettingScreen::class)

            var numObservedScreens = 0
            var numRetrievedFactors = 0

            // Do a long RT MENU button press to quickly navigate
            // through all basal profile factors, keeping count on
            // all observed screens and all retrieved factors to
            // be able to later check if all factors were observed.
            longPressRTButtonUntil(rtNavigationContext, RTNavigationButton.MENU) { parsedScreen ->
                if (parsedScreen !is ParsedScreen.BasalRateFactorSettingScreen) {
                    logger(LogLevel.ERROR) { "Got a non-profile screen ($parsedScreen)" }
                    throw UnexpectedRTScreenException(
                        ParsedScreen.BasalRateFactorSettingScreen::class,
                        parsedScreen::class
                    )
                }

                numObservedScreens++

                val factorIndexOnScreen = parsedScreen.beginTime.hour

                // numUnits null means the basal profile factor
                // is currently not shown due to blinking.
                if (parsedScreen.numUnits == null)
                    return@longPressRTButtonUntil false

                // If the factor in the profile is >= 0, it
                // means it was already set earlier.
                if (basalProfile[factorIndexOnScreen] >= 0)
                    return@longPressRTButtonUntil false

                val factor = parsedScreen.numUnits
                basalProfile[factorIndexOnScreen] = factor
                logger(LogLevel.DEBUG) { "Got basal profile factor #$factorIndexOnScreen : $factor" }

                basalProfileAccessReporter.setCurrentProgressStage(
                    RTCommandProgressStage.BasalProfileAccess(numRetrievedFactors)
                )

                numRetrievedFactors++

                (numObservedScreens >= NUM_BASAL_PROFILE_FACTORS)
            }

            // Failsafe in the unlikely case that the longPressRTButtonUntil()
            // call above skipped over some of the basal profile factors. In such
            // a case, numRetrievedFactors will be less than 24 (the value of
            // NUM_BASAL_PROFILE_FACTORS).
            // The corresponding items in the basalProfile int list will be set to
            // -1, since those items will have been skipped as well. Therefore,
            // for each negative item, revisit the corresponding screen.
            if (numRetrievedFactors < NUM_BASAL_PROFILE_FACTORS) {
                for (index in basalProfile.indices) {
                    // We are only interested in those entries that have been
                    // skipped. Those entries are set to their initial value (-1).
                    if (basalProfile[index] >= 0)
                        continue

                    logger(LogLevel.DEBUG) { "Re-reading missing basal profile factor $index" }

                    shortPressRTButtonsUntil(rtNavigationContext) { parsedScreen ->
                        if (parsedScreen !is ParsedScreen.BasalRateFactorSettingScreen) {
                            logger(LogLevel.ERROR) { "Got a non-profile screen ($parsedScreen)" }
                            throw UnexpectedRTScreenException(
                                ParsedScreen.BasalRateFactorSettingScreen::class,
                                parsedScreen::class
                            )
                        }

                        val factorIndexOnScreen = parsedScreen.beginTime.hour

                        if (factorIndexOnScreen == index) {
                            val factor = parsedScreen.numUnits
                            // Do nothing if the factor is currently not
                            // shown due to blinking so we can retry.
                            // Eventually, the factor becomes visible again.
                            if (factor == null)
                                return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.DoNothing

                            basalProfile[index] = factor
                            logger(LogLevel.DEBUG) { "Got basal profile factor #$index : $factor" }

                            // We got the factor, so we can stop short-pressing the RT button.
                            return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.Stop
                        } else {
                            // This is not the correct basal profile factor, so keep
                            // navigating through them to find the correct factor.
                            return@shortPressRTButtonsUntil ShortPressRTButtonsCommand.PressButton(
                                RTNavigationButton.MENU)
                        }
                    }

                    basalProfileAccessReporter.setCurrentProgressStage(
                        RTCommandProgressStage.BasalProfileAccess(numRetrievedFactors)
                    )
                    numRetrievedFactors++
                }
            }

            // All factors retrieved. Press CHECK once to get back to the total
            // basal rate screen, and then CHECK again to return to the main menu.

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.BasalRateTotalScreen::class)

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

            basalProfileAccessReporter.setCurrentProgressStage(BasicProgressStage.Finished)

            // Return the resulting profile.
            basalProfile
        } catch (e: Exception) {
            basalProfileAccessReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * [StateFlow] for reporting progress during the [setTemporaryBasalRate] call.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow consists of two stages (aside from Finished/Aborted/Idle).
     * These are:
     *
     * - [RTCommandProgressStage.SettingTBRPercentage]
     * - [RTCommandProgressStage.SettingTBRDuration]
     */
    val tbrProgressFlow = tbrProgressReporter.progressFlow

    /**
     * Sets the Combo's current temporary basal rate (TBR) via the remote terminal (RT) mode.
     *
     * This function suspends until the TBR is fully set. The [tbrProgressFlow]
     * can be used to get informed about the TBR setting progress. Since setting
     * a TBR can take a while, it is recommended to make use of this to show
     * some sort of progress indicator on a GUI.
     *
     * If the percentage is 100, any ongoing TBR will be cancelled. The Combo
     * will produce a W6 warning screen when this happens. This screen is
     * automatically dismissed by this function before it exits.
     *
     * [percentage] must be in the range 0-500 (specifying the % of the TBR),
     * and an integer multiple of 10.
     * [durationInMinutes] must be at least 15 (since the Combo cannot do TBRs
     * that are shorter than 15 minutes), and must an integer multiple of 15.
     * Maximum allowed duration is 24 hours, so the maximum valid value is 1440.
     * However, if [percentage] is 100, the value of [durationInMinutes]
     * is ignored.
     *
     * @param percentage TBR percentage to set.
     * @param durationInMinutes TBR duration in minutes to set.
     * @throws IllegalArgumentException if the percentage is not in the 0-500 range,
     *         or if the percentage value is not an integer multiple of 10, or if
     *         the duration is <15 or not an integer multiple of 15 (see the note
     *         about duration being ignored with percentage 100 above though).
     * @throws AlertScreenException if alerts occurs during this call, and they
     *         aren't a W6 warning (those are handled by this function).
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun setTemporaryBasalRate(percentage: Int, durationInMinutes: Int) =
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, isIdempotent = true) {
        // The Combo can only set TBRs of up to 500%, and the
        // duration can only be an integer multiple of 15 minutes.
        require((percentage >= 0) && (percentage <= 500))
        require((percentage % 10) == 0)
        require(
            (percentage == 100) ||
            ((durationInMinutes >= 15) && (durationInMinutes <= (24 * 60)) && ((durationInMinutes % 15) == 0))
        )

        tbrProgressReporter.reset(Unit)

        tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(0))

        try {
            var initialQuantityDistance: Int? = null

            // First, set the TBR percentage.
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TemporaryBasalRatePercentageScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, percentage) {
                val currentPercentage = (it as ParsedScreen.TemporaryBasalRatePercentageScreen).percentage

                // Calculate setting process out of the "distance" from the
                // current percentage to the target percentage. As we adjust
                // the quantity, that "distance" shrinks. When it is 0, we
                // consider the adjustment to be complete, or in other words,
                // the settingProgress to be at 100.
                // In the corner case where the current percentage displayed
                // on screen is already the target percentage, we just set
                // settingProgress straight to 100.
                if (currentPercentage != null) {
                    if (initialQuantityDistance == null) {
                        initialQuantityDistance = currentPercentage - percentage
                    } else {
                        val settingProgress = if (initialQuantityDistance == 0) {
                            100
                        } else {
                            val currentQuantityDistance = currentPercentage - percentage
                            (100 - currentQuantityDistance * 100 / initialQuantityDistance!!).coerceIn(0, 100)
                        }
                        tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(settingProgress))
                    }
                }

                currentPercentage
            }

            // If the percentage is 100%, we are done (and navigating to
            // the duration screen is not possible). Otherwise, continue.
            if (percentage != 100) {
                initialQuantityDistance = null

                tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(0))

                navigateToRTScreen(rtNavigationContext, ParsedScreen.TemporaryBasalRateDurationScreen::class)

                adjustQuantityOnScreen(rtNavigationContext, durationInMinutes) {
                    val currentDuration = (it as ParsedScreen.TemporaryBasalRateDurationScreen).durationInMinutes

                    // Do the settingProgress calculation just like before when setting the percentage.
                    if (currentDuration != null) {
                        if (initialQuantityDistance == null) {
                            initialQuantityDistance = currentDuration - durationInMinutes
                        } else {
                            val settingProgress = if (initialQuantityDistance == 0) {
                                100
                            } else {
                                val currentQuantityDistance = currentDuration - durationInMinutes
                                (100 - currentQuantityDistance * 100 / initialQuantityDistance!!).coerceIn(0, 100)
                            }
                            tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(settingProgress))
                        }
                    }

                    currentDuration
                }
            }

            tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(100))

            // TBR set. Press CHECK to confirm it and exit back to the main menu.
            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)

            tbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            tbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * Reads information from the pump's quickinfo screen.
     *
     * @throws AlertScreenException if alerts occur during this call.
     * @throws NoUsableRTScreenException if the quickinfo screen could not be found.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     * @return The [Quickinfo].
     */
    suspend fun readQuickinfo() = dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, isIdempotent = true) {
        navigateToRTScreen(rtNavigationContext, ParsedScreen.QuickinfoMainScreen::class)
        val parsedScreen = parsedScreenFlow.first()

        when (parsedScreen) {
            is ParsedScreen.QuickinfoMainScreen -> {
                // After parsing the quickinfo screen, exit back to the main screen by pressing BACK.
                rtNavigationContext.shortPressButton(RTNavigationButton.BACK)
                // Return the quickinfo from the quickinfo screen
                parsedScreen.quickinfo
            }
            else -> throw NoUsableRTScreenException()
        }
    }

    /**
     * [StateFlow] for reporting progress during the [deliverBolus] call.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow only consists of one stage (aside from Finished/Aborted/Idle),
     * and that is [RTCommandProgressStage.DeliveringBolus].
     */
    val bolusDeliveryProgressFlow = bolusDeliveryProgressReporter.progressFlow

    /**
     * Instructs the pump to deliver the specified bolus amount.
     *
     * This function only delivers a standard bolus, no multi-wave / extended ones.
     * It is currently not known how to command the Combo to deliver those types.
     *
     * The function suspends until the bolus was fully delivered or an error occurred.
     * In the latter case, an exception is thrown. During the delivery, the current
     * status is periodically retrieved from the pump. [bolusStatusUpdateIntervalInMs]
     * controls the status update interval. At each update, the bolus state is checked
     * (that is, whether it is delivering, or it is done, or an error occurred etc).
     * The bolus amount that was delivered by that point is communicated via the
     * [bolusDeliveryProgressFlow].
     *
     * To cancel the bolus, simply cancel the coroutine that is suspended by this function.
     *
     * @param bolusAmount Bolus amount to deliver. Note that this is given
     *        in 0.1 IU units, so for example, "57" means 5.7 IU. Valid range
     *        is 0.0 IU to 25.0 IU (that is, integer values 0-250).
     * @param bolusStatusUpdateIntervalInMs Interval between status updates,
     *        in milliseconds. Must be at least 1
     * @throws BolusNotDeliveredException if the pump did not deliver the bolus.
     *         This typically happens because the pump is currently stopped.
     * @throws BolusCancelledByUserException when the bolus was cancelled by the user.
     * @throws BolusAbortedDueToErrorException when the bolus delivery failed due
     *         to an error.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun deliverBolus(bolusAmount: Int, bolusStatusUpdateIntervalInMs: Long = 250) =
        dispatchCommand(PumpIO.Mode.COMMAND, isIdempotent = false) {

        require((bolusAmount >= 0) && (bolusAmount <= 250))
        require(bolusStatusUpdateIntervalInMs >= 1)

        logger(LogLevel.DEBUG) { "Beginning bolus delivery of ${bolusAmount.toStringWithDecimal(1)} IU" }
        val didDeliver = pump.deliverCMDStandardBolus(bolusAmount)
        if (!didDeliver) {
            logger(LogLevel.ERROR) { "Bolus delivery did not commence" }
            throw BolusNotDeliveredException(bolusAmount)
        }

        bolusDeliveryProgressReporter.reset(Unit)

        logger(LogLevel.DEBUG) { "Waiting until bolus delivery is complete" }

        try {
            while (true) {
                delay(bolusStatusUpdateIntervalInMs)

                val status = pump.getCMDCurrentBolusDeliveryStatus()

                logger(LogLevel.VERBOSE) { "Got current bolus delivery status: $status" }

                val deliveredAmount = when (status.deliveryState) {
                    ApplicationLayerIO.CMDBolusDeliveryState.DELIVERING -> bolusAmount - status.remainingAmount
                    ApplicationLayerIO.CMDBolusDeliveryState.DELIVERED -> bolusAmount
                    ApplicationLayerIO.CMDBolusDeliveryState.CANCELLED_BY_USER -> {
                        logger(LogLevel.DEBUG) { "Bolus cancelled by user" }
                        throw BolusCancelledByUserException(
                            deliveredAmount = bolusAmount - status.remainingAmount,
                            totalAmount = bolusAmount
                        )
                    }
                    ApplicationLayerIO.CMDBolusDeliveryState.ABORTED_DUE_TO_ERROR -> {
                        logger(LogLevel.ERROR) { "Bolus aborted due to a delivery error" }
                        throw BolusAbortedDueToErrorException(
                            deliveredAmount = bolusAmount - status.remainingAmount,
                            totalAmount = bolusAmount
                        )
                    }
                    else -> continue
                }

                bolusDeliveryProgressReporter.setCurrentProgressStage(
                    RTCommandProgressStage.DeliveringBolus(
                        deliveredAmount = deliveredAmount,
                        totalAmount = bolusAmount
                    )
                )

                if (deliveredAmount >= bolusAmount) {
                    bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
                    break
                }
            }
        } catch (e: BolusDeliveryException) {
            // Handle BolusDeliveryException subclasses separately,
            // since these exceptions are thrown when the delivery
            // was cancelled by the user or aborted due to an error.
            // The code further below tries to cancel in case of any
            // exception, which would make no sense with these ones.
            bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        } catch (e: Exception) {
            bolusDeliveryProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            try {
                pump.cancelCMDStandardBolus()
            } catch (cancelBolusExc: Exception) {
                logger(LogLevel.ERROR) { "Silently discarding caught exception while cancelling bolus: $cancelBolusExc" }
            }
            throw e
        }
    }

    enum class HistoryPart {
        HISTORY_DELTA,
        TDD_HISTORY,
        TBR_HISTORY
    }

    data class TddHistoryEvent(val date: DateTime, val totalDailyAmount: Int)
    data class TbrHistoryEvent(val timestamp: DateTime, val percentage: Int, val durationInMinutes: Int)

    data class History(
        val historyDeltaEvents: List<ApplicationLayerIO.CMDHistoryEvent>,
        val tddEvents: List<TddHistoryEvent>,
        val tbrEvents: List<TbrHistoryEvent>
    )

    /**
     * [StateFlow] for reporting progress during the [fetchHistory] call.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow consists of these stages (aside from Finished/Aborted/Idle):
     *
     * - [RTCommandProgressStage.FetchingTDDHistory]
     * - [RTCommandProgressStage.FetchingTBRHistory]
     */
    val historyProgressFlow = historyProgressReporter.progressFlow

    /**
     * Fetches bolus, TDD, and TBR history from the Combo.
     *
     * The Combo actually contains two history datasets. One is a "delta",
     * that is, events that happened since the last time the Combo was
     * queried for such that history delta. Once retrieved, that delta
     * is cleared, which is why only the events since the last retrieval
     * are available in this dataset. The other is a full list of the last
     * 30 boluses, the last 30 TDD figures, and the last 30 TBRs. That
     * second dataset is *not* cleared after retrieval.
     *
     * The history delta is retrieved very quickly, since it works over
     * the Combo's command mode. The other dataset has to be accessed
     * over the remote terminal mode, which is a much slower process
     * as a result.
     *
     * To allow for multiple use cases which may require different forms
     * of data, this function accepts the [enabledParts] argument, which
     * specifies what to fetch. Other parts are omitted. For example, if
     * only [HistoryPart.HISTORY_DELTA] is in this set, then this function
     * will finish very quickly, but only return the history delta.
     *
     * @param enabledParts Which parts of the history to fetch.
     * @return Retrieved history. Not all fields may be filled, depending
     *         on what is specified in [enabledParts].
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun fetchHistory(enabledParts: Set<HistoryPart>) =
        dispatchCommand<History>(pumpMode = null, isIdempotent = true) {
        // Calling dispatchCommand with pump mode set to null, since we
        // may have to switch between modes multiple times.

        historyProgressReporter.reset(HistoryProgressContext(enabledParts))

        try {
            val deltaEvents = if (HistoryPart.HISTORY_DELTA in enabledParts) {
                pump.switchMode(PumpIO.Mode.COMMAND)
                pump.getCMDHistoryDelta()
            } else
                listOf()

            val tddEvents = if (HistoryPart.TDD_HISTORY in enabledParts) {
                pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
                navigateToRTScreen(rtNavigationContext, ParsedScreen.MyDataDailyTotalsScreen::class)

                val eventsList = mutableListOf<TddHistoryEvent>()

                longPressRTButtonUntil(rtNavigationContext, RTNavigationButton.DOWN) { parsedScreen ->
                    if (parsedScreen !is ParsedScreen.MyDataDailyTotalsScreen) {
                        logger(LogLevel.DEBUG) { "Got a non-TDD screen ($parsedScreen) ; stopping TDD history scan" }
                        return@longPressRTButtonUntil true
                    }

                    eventsList.add(
                        TddHistoryEvent(
                            date = parsedScreen.date,
                            totalDailyAmount = parsedScreen.totalDailyAmount
                        )
                    )

                    logger(LogLevel.DEBUG) {
                        "Got TDD history event ${parsedScreen.index} / ${parsedScreen.totalNumEntries} ; " +
                        "date = ${parsedScreen.date} ; " +
                        "TDD = ${parsedScreen.totalDailyAmount.toStringWithDecimal(3)}"
                    }

                    historyProgressReporter.setCurrentProgressStage(
                        RTCommandProgressStage.FetchingTDDHistory(parsedScreen.index, parsedScreen.totalNumEntries)
                    )

                    (parsedScreen.index >= parsedScreen.totalNumEntries)
                }

                eventsList
            } else
                listOf()

            val tbrEvents = if (HistoryPart.TBR_HISTORY in enabledParts) {
                pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
                navigateToRTScreen(rtNavigationContext, ParsedScreen.MyDataTbrDataScreen::class)

                val eventsList = mutableListOf<TbrHistoryEvent>()

                longPressRTButtonUntil(rtNavigationContext, RTNavigationButton.DOWN) { parsedScreen ->
                    if (parsedScreen !is ParsedScreen.MyDataTbrDataScreen) {
                        logger(LogLevel.DEBUG) { "Got a non-TBR screen ($parsedScreen) ; stopping TBR history scan" }
                        return@longPressRTButtonUntil true
                    }

                    eventsList.add(
                        TbrHistoryEvent(
                            timestamp = parsedScreen.timestamp,
                            percentage = parsedScreen.percentage,
                            durationInMinutes = parsedScreen.durationInMinutes
                        )
                    )

                    logger(LogLevel.DEBUG) {
                        "Got TDD history event ${parsedScreen.index} / ${parsedScreen.totalNumEntries} ; " +
                        "timestamp = ${parsedScreen.timestamp} ; percentage = ${parsedScreen.percentage} ; " +
                        "duration in minutes = ${parsedScreen.durationInMinutes}"
                    }

                    historyProgressReporter.setCurrentProgressStage(
                        RTCommandProgressStage.FetchingTBRHistory(parsedScreen.index, parsedScreen.totalNumEntries)
                    )

                    (parsedScreen.index >= parsedScreen.totalNumEntries)
                }

                eventsList
            } else
                listOf()

            historyProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)

            History(deltaEvents, tddEvents, tbrEvents)
        } catch (e: Exception) {
            historyProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * [StateFlow] for reporting progress during the [setDateTime] call.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow consists of these stages (aside from Finished/Aborted/Idle):
     *
     * - [RTCommandProgressStage.SettingDateTimeHour]
     * - [RTCommandProgressStage.SettingDateTimeMinute]
     * - [RTCommandProgressStage.SettingDateTimeYear]
     * - [RTCommandProgressStage.SettingDateTimeMonth]
     * - [RTCommandProgressStage.SettingDateTimeDay]
     */
    val setDateTimeProgressFlow = setDateTimeProgressReporter.progressFlow

    /**
     * Sets the Combo's current date and time.
     *
     * The time is given as localtime, since the Combo is not timezone-aware.
     *
     * This is done by using the remote terminal mode, and as
     * a consequence, takes some time to finish, unlike its
     * [getDateTime] counterpart.
     *
     * @param newDateTime Date and time to set.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun setDateTime(newDateTime: DateTime) =
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, isIdempotent = true) {
        setDateTimeProgressReporter.reset(Unit)

        try {
            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeHour)
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsHourScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newDateTime.hour) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsHourScreen).hour
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeMinute)
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsMinuteScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newDateTime.minute) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsMinuteScreen).minute
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeYear)
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsYearScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newDateTime.year) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsYearScreen).year
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeMonth)
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsMonthScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newDateTime.month) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsMonthScreen).month
            }

            setDateTimeProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingDateTimeDay)
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TimeAndDateSettingsDayScreen::class)
            adjustQuantityOnScreen(rtNavigationContext, newDateTime.day) { parsedScreen ->
                (parsedScreen as ParsedScreen.TimeAndDateSettingsDayScreen).day
            }

            rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)

            setDateTimeProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            setDateTimeProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * Retrieves the Combo's current date and time.
     *
     * The time is given as localtime, since the Combo is not timezone-aware.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun getDateTime() = dispatchCommand<DateTime>(PumpIO.Mode.COMMAND, isIdempotent = true) {
        pump.readCMDDateTime()
    }

    // An idempotent command is one that can be retried safely. It is very
    // important that the isIdempotent is set to true only if it is 100%
    // certain that the command truly _is_ idempotent. In particular, this
    // command _must not_ be set to true if it is about delivering a bolus,
    // since retrying to deliver a bolus can result in duplicate bolusing.
    private suspend fun <T> dispatchCommand(
        pumpMode: PumpIO.Mode?,
        isIdempotent: Boolean,
        block: suspend () -> T
    ): T = commandMutex.withLock {
        mutableCommandDispatchState.value = DispatchState.DISPATCHING

        try {
            check(pump.connectionState.value == Pump.ConnectionState.CONNECTED) { "Pump is not connected" }

            // Verify that there have been no errors/warnings since the last time
            // a command was dispatched. ComboCtl has no way of getting notified
            // about warnings/errors until the pump sets RT screens and/or the
            // pump's warning/error status is queried through the command mode.
            // We do the latter with this function call.
            checkForAlerts()

            var retval: T? = null

            // A command dispatch is attempted a number of times. That number
            // depends on whether or not it is an idempotent command. If it is,
            // then it is possible to retry multiple times if command dispatch
            // failed due to certain specific exceptions. (Any other exceptions
            // are just rethrown; no more attempts are made then.)
            var attemptNr = 0
            val maxNumAttempts = if (isIdempotent) NUM_IDEMPOTENT_COMMAND_DISPATCH_ATTEMPTS else 1

            // The command is run in a child coroutine that lives inside
            // a SupervisorScope. If the background worker fails, that
            // coroutine is cancelled by calling cmdDeferred.cancel(). The
            // exception is recorded in the backgroundIOException variable
            // to be able to process it further after the command was cancelled.
            // (We use a SupervisorScope to contain the coroutine cancellation.)
            var cmdDeferred: Deferred<Unit>? = null
            var backgroundIOException: Exception? = null

            // Set to true if the code inside the while loop below determined
            // that it is OK to retry the command and that in order to do so
            // the pump must be reconnected.
            var needsToReconnect = false

            pump.onBackgroundIOException = { exception ->
                // If an exception occurs, record it and cancel the
                // coroutine that is currently executing the command.
                backgroundIOException = exception
                cmdDeferred?.cancel()
            }

            // Reset these to guarantee that the handleAlertScreenContent()
            // calls don't use stale states.
            dismissalCount = 0
            lastObservedAlertScreenContent = null

            while (attemptNr < maxNumAttempts) {
                var incrementAttemptNr = true

                try {
                    if (needsToReconnect) {
                        // Wait a while before attempting to reconnect. IO failure
                        // typically happens due to Bluetooth problems (including
                        // non-technical ones like when the pump is out of reach)
                        // and pump specific cases like when the user presses a
                        // button on the pump and enables its local UI (this
                        // terminates the Bluetooth connection). In these cases,
                        // it is useful to wait a bit to give the pump and/or the
                        // Android Bluedevil stack some time to recover.
                        delay(DELAY_IN_MS_BETWEEN_COMMAND_DISPATCH_ATTEMPTS)
                        pump.reconnect()
                        // Check for alerts right after reconnect since the earlier
                        // disconnect may have produced an alert. For example, if
                        // a TBR was being set, and the pump got disconnected, a
                        // W6 alert will have been triggered.
                        checkForAlerts()
                        needsToReconnect = false
                        logger(LogLevel.DEBUG) { "Pump successfully reconnected" }
                    }

                    supervisorScope {
                        cmdDeferred = async {
                            var doAlertCheck = true

                            try {
                                if (pumpMode != null)
                                    pump.switchMode(pumpMode)

                                retval = block.invoke()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Don't do alert checks if an exception other
                                // than CancellationException occurs.
                                doAlertCheck = false
                                throw e
                            } finally {
                                if (doAlertCheck) {
                                    // Post-command check in case something went wrong
                                    // and an alert screen appeared after the command ran.
                                    // Most commonly, these are benign warning screens,
                                    // especially W6, W7, W8.
                                    // Using a NonCancellable context in case the command
                                    // was aborted by cancellation (like a cancelled bolus).
                                    // Without this context, the checkForAlerts() call would
                                    // not actually do anything.
                                    withContext(NonCancellable) {
                                        checkForAlerts()
                                    }
                                }
                            }
                        }
                    }

                    // At this point, the Deferred is guaranteed to have completed,
                    // since the supervisor scope above does not end until all of
                    // its child coroutines are completed. This implies that await()
                    // will never suspend. The reason we call await() is that if
                    // the async block inside the supervisor scope was completed
                    // by an exception instead of by an orderly finish, async will
                    // have captured that exception, and await() will release and
                    // rethrow it here. If the block was just aborted by a regular
                    // coroutine cancellation, then await() will throw the internal
                    // JobCancellationException, which is a CancellationException
                    // subclass. This is why we ignore CancellationException here;
                    // we aren't interested in regular cancellations. We are
                    // interested in other exceptions, however, and those aren't
                    // ignored. This is particularly important for AlertScreenException,
                    // which is handled in the AlertScreenException catch block below.
                    try {
                        cmdDeferred?.await()
                    } catch (ignore: CancellationException) {
                    }

                    // If the coroutine above was cancelled due to a background
                    // worker exception, this value will be non-null, and we
                    // rethrow the exception here to be further handled by the
                    // catch blocks below.
                    if (backgroundIOException != null) {
                        logger(LogLevel.DEBUG) {
                            "Cancelled command due to background IO exception $backgroundIOException"
                        }

                        // Get the exception out of backgroundIOException,
                        // then reset backgroundIOException to null, to ensure
                        // that in the next while-loop iteration we do not
                        // incorrectly end up inside this if-block again.
                        val exc = backgroundIOException!!
                        backgroundIOException = null

                        throw TransportLayerIO.BackgroundIOException(exc)
                    }

                    break
                } catch (e: AlertScreenException) {
                    // We enter this catch block if any alert screens appear
                    // _during_ the command execution. In such a case, the
                    // command is considered aborted, and we have to try again
                    // (if isIdempotent is set to true).
                    handleAlertScreens()
                } catch (e: TransportLayerIO.BackgroundIOException) {
                    val pumpTerminatedConnection = (e.cause is ApplicationLayerIO.ErrorCodeException) &&
                            (e.cause.appLayerPacket.command == ApplicationLayerIO.Command.CTRL_DISCONNECT)

                    // Background IO exceptions can happen for a number of reasons.
                    // To be on the safe side, we only try to reconnect if the exception
                    // happened due to the Combo terminating the connection on its end.

                    if (pumpTerminatedConnection) {
                        if (isIdempotent) {
                            logger(LogLevel.DEBUG) { "Pump terminated connection; will try to reconnect since this is an idempotent command" }
                            needsToReconnect = true
                        } else {
                            logger(LogLevel.DEBUG) {
                                "Pump terminated connection, but will not try to reconnect since this is a non-idempotent command"
                            }
                            throw e
                        }
                    } else
                        throw e
                } catch (e: ComboIOException) {
                    // IO exceptions typically happen because of connection failure.
                    // This includes cases like when the pump and phone are out of
                    // reach. Try to reconnect if this is an idempotent command.

                    if (isIdempotent) {
                        logger(LogLevel.DEBUG) { "Combo IO exception $e occurred; will try to reconnect since this is an idempotent command" }
                        needsToReconnect = true
                    } else {
                        // Don't bother if this command is not idempotent, since in that
                        // case, we can only perform one single attempt anyway.
                        logger(LogLevel.DEBUG) {
                            "Combo IO exception $e occurred, but will not try to reconnect since this is a non-idempotent command"
                        }
                        throw e
                    }
                }

                if (incrementAttemptNr)
                    attemptNr++
            }

            retval ?: throw CommandExecutionAttemptsFailedException()
        } finally {
            mutableCommandDispatchState.value = DispatchState.IDLE
        }
    }

    private suspend fun checkForAlerts() {
        pump.switchMode(PumpIO.Mode.COMMAND)
        val pumpStatus = pump.readCMDErrorWarningStatus()

        if (pumpStatus.warningOccurred || pumpStatus.errorOccurred) {
            pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)

            handleAlertScreens()
        }
    }

    private suspend fun handleAlertScreens() {
        parsedScreenFlow = rtNavigationContext.getParsedScreenFlow(processAlertScreens = false)

        try {
            parsedScreenFlow.first { parsedScreen ->
                when (parsedScreen) {
                    is ParsedScreen.AlertScreen -> {
                        logger(LogLevel.DEBUG) {
                            "Got alert screen with content ${parsedScreen.content}"
                        }
                        handleAlertScreenContent(parsedScreen.content)
                        false
                    }
                    else -> true
                }
            }
        } finally {
            parsedScreenFlow = rtNavigationContext.getParsedScreenFlow()
        }
    }

    private suspend fun handleAlertScreenContent(alertScreenContent: AlertScreenContent) {
        when (alertScreenContent) {
            // Alert screens blink. When the content is "blinked out",
            // it cannot be recognized, and is set as this type.
            // Ignore contents of this type. The next time
            // handleAlertScreenContent() is called, we hopefully
            // get recognizable content.
            is AlertScreenContent.None -> Unit
            // Error screen contents always cause a rethrow since all error
            // screens are considered non-recoverable errors that must not
            // be ignored / dismissed. Instead, let the code fail by rethrowing
            // the exception. The user needs to check out the error manually.
            is AlertScreenContent.Error -> throw AlertScreenException(alertScreenContent)
            is AlertScreenContent.Warning -> {
                // Check if the alert screen content changed in case
                // several warnings appear one after the other. In
                // such a case, we need to reset the dismissal count
                // to be able to properly dismiss followup warnings.
                if (lastObservedAlertScreenContent != alertScreenContent) {
                    lastObservedAlertScreenContent = alertScreenContent
                    dismissalCount = 0
                }

                val warningCode = alertScreenContent.code

                // W1 is the "reservoir almost empty" warning. Notify the caller
                // about this, then dismiss it.
                // W1 is the "battery almost empty" warning. Notify the caller
                // about this, then dismiss it.
                // W6 informs about an aborted TBR.
                // W7 informs about a finished TBR.
                // W8 informs about an aborted bolus.
                // All three are pure informational, and should be dismissed.
                // Any other warnings are intentionally rethrown for safety.
                when (warningCode) {
                    1 -> onEvent(Event.RESERVOIR_LOW)
                    2 -> onEvent(Event.BATTERY_LOW)
                    6, 7, 8 -> Unit
                    else -> throw AlertScreenException(alertScreenContent)
                }

                // Warning screens are dismissed by pressing CHECK twice.
                // First time, the CHECK button press transitions the state
                // on that screen from alert to confirm. Second time, the
                // screen is finally dismissed. Due to the blinking screen
                // though, we might end up getting the warning screen more
                // than twice, so use a counter to not accidentally press
                // CHECK more than twice.
                if (dismissalCount < 2) {
                    logger(LogLevel.DEBUG) { "Dismissing W$warningCode by short-pressing CHECK" }
                    rtNavigationContext.shortPressButton(RTNavigationButton.CHECK)
                    dismissalCount++
                }
            }
        }
    }
}
