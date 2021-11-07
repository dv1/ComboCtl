package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.DateTime
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.toStringWithDecimal
import info.nightscout.comboctl.parser.AlertScreenException
import info.nightscout.comboctl.parser.ParsedScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = Logger.get("PumpCommandDispatcher")

/** The number of integers in a basal profile. */
const val NUM_BASAL_PROFILE_FACTORS = 24

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
     * Basal profile setting stage.
     *
     * @property numSetFactors How many basal rate factors have been set by now.
     *           When the basal profile has been fully set, this value equals
     *           the value of totalNumFactors.
     * @property totalNumFactors Total number of basal rate factors.
     */
    data class SettingBasalProfile(val numSetFactors: Int, val totalNumFactors: Int = NUM_BASAL_PROFILE_FACTORS) :
        ProgressStage("settingBasalProfile")

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
class PumpCommandDispatcher(private val pump: Pump) {
    private val rtNavigationContext = RTNavigationContext(pump)
    private var parsedScreenFlow = rtNavigationContext.getParsedScreenFlow(ignoreAlertScreens = false)
    private val commandMutex = Mutex()

    private val basalProfileProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.SettingBasalProfile::class
        )
    )

    private val tbrProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.SettingTBRPercentage::class,
            RTCommandProgressStage.SettingTBRDuration::class
        )
    )

    private val bolusDeliveryProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.DeliveringBolus::class
        )
    )

    private val historyProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.FetchingTDDHistory::class,
            RTCommandProgressStage.FetchingTBRHistory::class
        )
    )

    private val setDateTimeProgressReporter = ProgressReporter(
        listOf(
            RTCommandProgressStage.SettingDateTimeHour::class,
            RTCommandProgressStage.SettingDateTimeMinute::class,
            RTCommandProgressStage.SettingDateTimeYear::class,
            RTCommandProgressStage.SettingDateTimeMonth::class,
            RTCommandProgressStage.SettingDateTimeDay::class
        )
    )

    /**
     * [StateFlow] for reporting progress during the [setBasalProfile] call.
     *
     * See the [ProgressReporter] documentation for details.
     *
     * This flow only consists of one stage (aside from Finished/Aborted/Idle),
     * and that is [RTCommandProgressStage.SettingBasalProfile].
     */
    val basalProfileProgressFlow = basalProfileProgressReporter.progressFlow

    /**
     * Sets the Combo's basal profile via the remote terminal (RT) mode.
     *
     * This function suspends until the basal profile is fully set. The,
     * [bolusDeliveryProgressFlow] can be used to get informed about the basal
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
     * @param basalProfile Basal profile to set.
     * @throws IllegalArgumentException if the basal profile does not contain
     *         exactly 24 values (the factors), or if at least one of the factors
     *         is <0, or if the factors aren't correctly rounded (for example,
     *         when trying to use 0.03 as one of the 24 factors).
     * @throws AlertScreenException if an alert occurs during this call.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun setBasalProfile(basalProfile: List<Int>) =
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, expectedWarningCode = null) {
        require(basalProfile.size == NUM_BASAL_PROFILE_FACTORS)

        basalProfile.forEachIndexed { index, factor ->
            require(factor >= 0) { "Basal profile factor #$index is <0 (value: $factor)" }
            require(
                ((factor >= 50) && (factor <= 1000) && ((factor % 10) == 0)) || // 0.05 to 1 IU range rounding check with the 0.01 IU steps
                ((factor >= 1000) && (factor <= 10000) && ((factor % 50) == 0)) // 1 to 10 IU range rounding check with the 0.05 IU steps
            ) { "Basal profile factor #$index is not correctly rounded (value: $factor)" }
        }

        basalProfileProgressReporter.reset()

        basalProfileProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(0))

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

                basalProfileProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(index + 1))

                // By pushing MENU we move to the next basal rate factor.
                rtNavigationContext.shortPressButton(RTNavigationButton.MENU)

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

            basalProfileProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            basalProfileProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
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
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, expectedWarningCode = 6) {
        // The Combo can only set TBRs of up to 500%, and the
        // duration can only be an integer multiple of 15 minutes.
        require((percentage >= 0) && (percentage <= 500))
        require((percentage % 10) == 0)
        require((percentage == 100) || ((durationInMinutes >= 15) && ((durationInMinutes % 15) == 0)))

        tbrProgressReporter.reset()

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
    suspend fun readQuickinfo() = dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, expectedWarningCode = null) {
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
     *        in 0.1 IU units, so for example, "57" means 5.7 IU.
     * @param bolusStatusUpdateIntervalInMs Interval between status updates,
     *        in milliseconds.
     * @throws BolusNotDeliveredException if the pump did not deliver the bolus.
     *         This typically happens because the pump is currently stopped.
     * @throws BolusCancelledByUserException when the bolus was cancelled by the user.
     * @throws BolusAbortedDueToErrorException when the bolus delivery failed due
     *         to an error.
     * @throws IllegalStateException if the [Pump] instance's background worker
     *         has failed or the pump is not connected.
     */
    suspend fun deliverBolus(bolusAmount: Int, bolusStatusUpdateIntervalInMs: Long = 250) =
        dispatchCommand(PumpIO.Mode.COMMAND, expectedWarningCode = null) {
        logger(LogLevel.DEBUG) { "Beginning bolus delivery of ${bolusAmount.toStringWithDecimal(1)} IU" }
        val didDeliver = pump.deliverCMDStandardBolus(bolusAmount)
        if (!didDeliver) {
            logger(LogLevel.ERROR) { "Bolus delivery did not commence" }
            throw BolusNotDeliveredException(bolusAmount)
        }

        bolusDeliveryProgressReporter.reset()

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
        dispatchCommand<History>(pumpMode = null, expectedWarningCode = null) {
        // Calling dispatchCommand with pump mode set to null, since we
        // may have to switch between modes multiple times.

        historyProgressReporter.reset()

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

            // Check if an alert appeared. Alert screens can show up at any moment,
            // but typically, they appear when the user is done with the current
            // operation and returns to the main screen.
            checkForAlerts(null)

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
        dispatchCommand(PumpIO.Mode.REMOTE_TERMINAL, expectedWarningCode = null) {
        setDateTimeProgressReporter.reset()

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
    suspend fun getDateTime() = dispatchCommand<DateTime>(PumpIO.Mode.COMMAND, expectedWarningCode = null) {
        pump.readCMDDateTime()
    }

    // Dispatches higher-level pump commands by running the specified block
    // and applying extra post-run alert checks. Also, the pump mode is
    // switched prior to running the block if required. Finally, all of this
    // happens inside the commandMutex to make sure the commands never
    // run concurrently (since this is not supported by the Combo).
    private suspend fun <T> dispatchCommand(
        pumpMode: PumpIO.Mode?,
        expectedWarningCode: Int?,
        block: suspend () -> T
    ): T = commandMutex.withLock {
        check(pump.isConnected()) { "Pump is not connected" }

        if (pumpMode != null)
            pump.switchMode(pumpMode)

        val retval = block.invoke()

        // Check if an alert appeared. Alert screens can show up at any moment,
        // but typically, they appear when the user is done with the current
        // operation and returns to the main screen.
        checkForAlerts(expectedWarningCode)

        retval
    }

    private suspend fun checkForAlerts(expectedWarningCode: Int?) {
        pump.switchMode(PumpIO.Mode.COMMAND)
        val pumpStatus = pump.readCMDErrorWarningStatus()

        try {
            if (pumpStatus.warningOccurred || pumpStatus.errorOccurred) {
                pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
                // Since the parsed screen flow gets data from an upstream
                // hot flow, collect() would normally never end. However,
                // an alert was seen, so at some point, the flow will see
                // an alert screen and throw an AlertScreenException.
                parsedScreenFlow.collect()
            }
        } catch (e: AlertScreenException) {
            if ((expectedWarningCode == null) || (e.getSingleWarningCode() != expectedWarningCode))
                throw e
        }
    }
}
