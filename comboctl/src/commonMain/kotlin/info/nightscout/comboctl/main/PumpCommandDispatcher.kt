package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayerIO
import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.toStringWithDecimal
import info.nightscout.comboctl.parser.ParsedScreen
import info.nightscout.comboctl.parser.ParsedScreenStream
import kotlinx.coroutines.delay

private val logger = Logger.get("PumpCommandDispatcher")

/** The number of integers in a basal profile. */
const val NUM_BASAL_PROFILE_FACTORS = 24

open class BolusDeliveryException(val totalAmount: Int, message: String) : ComboException(message)

class BolusNotDeliveredException(totalAmount: Int) :
    BolusDeliveryException(totalAmount, "Could not deliver bolus amount of ${totalAmount.toStringWithDecimal(1)} IU")

class BolusCancelledByUserException(deliveredAmount: Int, totalAmount: Int) :
    BolusDeliveryException(
        totalAmount,
        "Bolus cancelled (delivered amount: ${deliveredAmount.toStringWithDecimal(1)} IU  " +
                "total programmed amount: ${totalAmount.toStringWithDecimal(1)} IU"
    )

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
    private val parsedScreenStream = ParsedScreenStream(pump.displayFrameFlow)
    private val rtNavigationContext = RTNavigationContext(pump, parsedScreenStream)

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
     * each basal profile factor).
     *
     * @param basalProfile Basal profile to set.
     * @throws IllegalArgumentException if the basal profile does not contain
     *         exactly 24 values (the factors), or if at least one of the factors
     *         is <0.
     * @throws AlertScreenException if an alert occurs during this call.
     */
    suspend fun setBasalProfile(basalProfile: List<Int>) {
        pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)

        require(basalProfile.size == NUM_BASAL_PROFILE_FACTORS)
        require(basalProfile.all { it >= 0 })

        // Make sure any previously parsed screen is not reused,
        // since these may contain stale states.
        resetNavigation()

        basalProfileProgressReporter.reset()

        basalProfileProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(0))

        try {
            navigateToRTScreen(rtNavigationContext, ParsedScreen.BasalRateFactorSettingScreen::class)

            // Store the hours at which the current basal rate factor
            // begins so we can be sure that during screen cycling we
            // actually get to the next factor (which begins at
            // different hours).
            var previousBeginHours = (rtNavigationContext.getParsedScreen() as ParsedScreen.BasalRateFactorSettingScreen).beginHours

            for (index in basalProfile.indices) {
                val basalFactor = basalProfile[index]
                adjustQuantityOnScreen(rtNavigationContext, basalFactor) {
                    (it as ParsedScreen.BasalRateFactorSettingScreen).numUnits
                }

                basalProfileProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(index + 1))

                // By pushing MENU we move to the next basal rate factor.
                rtNavigationContext.pushButton(RTNavigationButton.MENU)

                while (true) {
                    val parsedScreen = rtNavigationContext.getParsedScreen()
                    parsedScreen as ParsedScreen.BasalRateFactorSettingScreen

                    if (parsedScreen.beginHours == previousBeginHours) {
                        // This is still the same basal rate factor screen,
                        // since the begin hours are the same. Invalidate the
                        // current parsed screen to force the context to get
                        // a new one the next time getParsedScreen() is called.
                        rtNavigationContext.parsedScreenDone()
                        continue
                    } else {
                        previousBeginHours = parsedScreen.beginHours
                        break
                    }
                }
            }

            // All factors are set. Press CHECK once to get back to the total
            // basal rate screen, and then CHECK again to store the new profile
            // and return to the main menu.

            rtNavigationContext.pushButton(RTNavigationButton.CHECK)
            waitUntilScreenAppears(rtNavigationContext, ParsedScreen.BasalRateTotalScreen::class)

            rtNavigationContext.pushButton(RTNavigationButton.CHECK)
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
     * @throws AlertScreenException if an alert occurs during this call, and it
     *         is not a W6 warning (those are handled by this function).
     */
    suspend fun setTemporaryBasalRate(percentage: Int, durationInMinutes: Int) {
        pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)

        // The Combo can only set TBRs of up to 500%, and the
        // duration can only be an integer multiple of 15 minutes.
        require((percentage >= 0) && (percentage <= 500))
        require((percentage % 10) == 0)
        require((percentage == 100) || ((durationInMinutes >= 15) && ((durationInMinutes % 15) == 0)))

        // Make sure any previously parsed screen is not reused,
        // since these may contain stale states.
        resetNavigation()

        tbrProgressReporter.reset()

        tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(0))

        try {
            var initialQuantityDistance: Int? = null

            // First, set the TBR percentage.
            navigateToRTScreen(rtNavigationContext, ParsedScreen.TemporaryBasalRatePercentageScreen::class)
            val initiallySeenTbrPercentage = adjustQuantityOnScreen(rtNavigationContext, percentage) {
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

            tbrProgressReporter.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(100))

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
            rtNavigationContext.pushButton(RTNavigationButton.CHECK)

            // Setting the TBR to 100 will cancel any currently ongoing TBR. This
            // in turn causes a warning screen with code W6 to appear. Dismiss that
            // warning when it appears. (Unfortunately, it is not possible to
            // configure the Combo such that that warning does not appear.)
            // If there was no TBR set (meaning, the percentage already was set
            // to 100), this is effectively a no-op, and no warning will appear.
            if ((initiallySeenTbrPercentage != 100) && (percentage == 100))
                rtNavigationContext.waitForAndDismissWarningScreen(6)
            else
                waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

            tbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Finished)
        } catch (e: Exception) {
            tbrProgressReporter.setCurrentProgressStage(BasicProgressStage.Aborted)
            throw e
        }
    }

    /**
     * Reads information from the pump's quickinfo screen.
     *
     * @throws AlertScreenException if an alert occurs during this call.
     * @throws NoUsableRTScreenException if the quickinfo screen could not be found.
     */
    suspend fun readQuickinfo(): ParsedScreen.QuickinfoMainScreen {
        pump.switchMode(PumpIO.Mode.REMOTE_TERMINAL)

        // Make sure any previously parsed screen is not reused,
        // since these may contain stale states.
        resetNavigation()

        navigateToRTScreen(rtNavigationContext, ParsedScreen.QuickinfoMainScreen::class)
        val parsedScreen = rtNavigationContext.getParsedScreen()
        // After parsing the quickinfo screen, exit back to the main screen by pressing BACK.
        rtNavigationContext.pushButton(RTNavigationButton.BACK)

        when (parsedScreen) {
            is ParsedScreen.QuickinfoMainScreen -> return parsedScreen
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
     */
    suspend fun deliverBolus(bolusAmount: Int, bolusStatusUpdateIntervalInMs: Long = 250) {
        pump.switchMode(PumpIO.Mode.COMMAND)

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
            } catch (e2: Exception) {
                logger(LogLevel.ERROR) { "Caught exception while cancelling bolus: $e" }
            }
            throw e
        }
    }

    private fun resetNavigation() {
        parsedScreenStream.reset()
        rtNavigationContext.parsedScreenDone()
    }
}
