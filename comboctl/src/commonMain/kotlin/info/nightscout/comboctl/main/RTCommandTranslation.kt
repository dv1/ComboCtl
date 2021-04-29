package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.BasicProgressStage
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.ProgressReporter
import info.nightscout.comboctl.base.ProgressStage
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.parser.AlertScreenContent
import info.nightscout.comboctl.parser.ParsedScreen
import kotlin.math.sign

private val logger = Logger.get("RTCommandTranslation")

// Gets an integer quantity from the current parsed screen.
// This must not be called if the current screen is unknown,
// otherwise, NoUsableRTScreenException is thrown.
// Since different screen types contain quantities with
// different names, a getScreenQuantity function literal
// needs to be provided that takes care of pulling the
// quantity from the current screen.
// If the current parsed screen was recognized, but its quantity
// currently does not show up (typically because it is blinking),
// then getScreenQuantity returns null. In some cases, a null
// return value may already be OK. In others, it is necessary
// to wait until the quantity shows up. To distinguish between
// these two, the waitUntilQuantityAvailable argument exists.
internal suspend fun getQuantityOnScreen(
    rtNavigationContext: RTNavigationContext,
    waitUntilQuantityAvailable: Boolean,
    getScreenQuantity: (screen: ParsedScreen) -> Int?
): Int? {
    while (true) {
        val parsedScreen = rtNavigationContext.getParsedScreen()
        if (parsedScreen is ParsedScreen.UnrecognizedScreen)
            throw NoUsableRTScreenException()

        val currentQuantity = getScreenQuantity(parsedScreen)

        if (currentQuantity != null) {
            return currentQuantity
        } else {
            if (waitUntilQuantityAvailable) {
                // Mark the current parsed screen as done, since
                // while it was recognized, its quantity currently
                // does not show up, so we try the next screen -
                // perhaps that one does show the quantity.
                rtNavigationContext.parsedScreenDone()
            } else
                return null
        }
    }
}

/**
 * Adjusts a displayed quantity until its value matches the given target value.
 *
 * Adjustments are done by sending long and short RT button presses (specifically
 * the UP and DOWN buttons). At first, a long button press is started to quickly
 * get close to the target quantity. Afterwards, finetuning is done using a series
 * of short RT button presses.
 *
 * The [getScreenQuantity] function literal takes the current [ParsedScreen] and
 * retrieves & returns the displayed quantity. If said quantity currently does not
 * show up (because it is blinking for example), then it returns null.
 *
 * @param rtNavigationContext RT navigation context to use for cycling through screens.
 * @param targetQuantity Quantity to target with the adjustments.
 * @param getScreenQuantity Function literal to get the displayed quantity.
 * @throws NoUsableRTScreenException If the initial parsed screen available in the
 *         context at the time of this function call is null (= unrecognized).
 *         Quantities of unrecognized screens cannot be adjusted.
 * @throws IllegalStateException if this function is called while the pump
 *         is not in the remote terminal mode.
 * @throws Exception Exceptions that were thrown in the getScreenQuantity callback.
 */
suspend fun adjustQuantityOnScreen(
    rtNavigationContext: RTNavigationContext,
    targetQuantity: Int,
    getScreenQuantity: (screen: ParsedScreen) -> Int?
): Int {
    check(rtNavigationContext.pump.currentModeFlow.value == PumpIO.Mode.REMOTE_TERMINAL)

    // Get the currently displayed quantity; if the quantity is currently not
    // retrievable, wait until it is so we have a non-null initial quantity.
    var currentQuantity = getQuantityOnScreen(rtNavigationContext, waitUntilQuantityAvailable = true, getScreenQuantity)
    var previousQuantity: Int? = null
    var quantityParsed = true

    val initiallySeenQuantity = currentQuantity!!

    if (currentQuantity == targetQuantity) {
        logger(LogLevel.DEBUG) { "Quantity on screen is already set to the target quantity $targetQuantity; not adjusting anything" }
        return targetQuantity
    }

    logger(LogLevel.DEBUG) { "Initial quantity on screen before adjusting: $initiallySeenQuantity" }

    // Perform the long RT button press.

    var button = if (currentQuantity > targetQuantity)
        RTNavigationButton.DOWN
    else
        RTNavigationButton.UP

    try {
        rtNavigationContext.pump.startLongRTButtonPress(button.rtButtonCodes) {
            // This check prevents a redundant getQuantityOnScreen
            // call the first time this block is called.
            if (!quantityParsed) {
                // Get the newly displayed quantity. We do not wait until  it is
                // non-null, since such a wait takes a while. If we get null as
                // value, we simply continue with the current quantity instead.
                // That way, we do not slow down the increment/decrement
                // during the long RT button pressing.
                val newQuantity = getQuantityOnScreen(rtNavigationContext, waitUntilQuantityAvailable = false, getScreenQuantity)

                if (newQuantity != null) {
                    previousQuantity = currentQuantity
                    currentQuantity = newQuantity
                }
            }

            val retval = if (currentQuantity == targetQuantity) {
                // Matching value, we can exit.
                false
            } else if ((previousQuantity != null) && ((previousQuantity!! - targetQuantity).sign != (currentQuantity!! - targetQuantity).sign)) {
                // If previousQuantity is "before" targetQuantity while
                // currentQuantity is "after" targetQuantity, then we went
                // past the target value, and we can exit.
                false
            } else {
                rtNavigationContext.parsedScreenDone()
                quantityParsed = false
                true
            }

            retval
        }

        rtNavigationContext.pump.waitForLongRTButtonPressToFinish()
    } catch (e: Exception) {
        // Make sure the long RT button press is _always_ finished
        // to not cause state related issues like failing short RT
        // button presses later on.
        rtNavigationContext.pump.stopLongRTButtonPress()
        throw e
    }

    // Perform the short RT button press.

    // Invalidate the current screen to be 100% sure that the quantity
    // we start the short RT button press with is really the one that
    // is currently being displayed.
    rtNavigationContext.parsedScreenDone()

    // Get the currently displayed quantity; if the quantity is currently not
    // retrievable, wait until it is so we have a non-null initial quantity.
    currentQuantity = getQuantityOnScreen(rtNavigationContext, waitUntilQuantityAvailable = true, getScreenQuantity)
    previousQuantity = null
    quantityParsed = true

    button = if (currentQuantity!! > targetQuantity)
        RTNavigationButton.DOWN
    else
        RTNavigationButton.UP

    while (true) {
        // This check prevents a redundant getQuantityOnScreen
        // call the first time this loop iterates.
        if (!quantityParsed) {
            // Unlike the code for the long RT button presses, here, we _do_
            // wait for a non-null parsed screen so we always know what the
            // current quantity is. This is because unlike with the long RT
            // button press, the short one does not auto-increment just
            // because the button is being held down. We have to constantly
            // check that the displayed quantity still isn't the target one,
            // otherwise we may overshoot and adjust improperly.
            val newQuantity = getQuantityOnScreen(rtNavigationContext, waitUntilQuantityAvailable = true, getScreenQuantity)

            previousQuantity = currentQuantity
            currentQuantity = newQuantity
        }

        if (currentQuantity == targetQuantity) {
            // Matching value, we can exit.
            break
        } else if ((previousQuantity != null) && ((previousQuantity!! - targetQuantity).sign != (currentQuantity!! - targetQuantity).sign)) {
            // If previousQuantity is "before" targetQuantity while
            // currentQuantity is "after" targetQuantity, then we went
            // past the target value, and we can exit.
            break
        } else {
            rtNavigationContext.pushButton(button)
            // Mark this screen as done to ensure that we get a fresh
            // new parsed screen during the next loop iteration.
            rtNavigationContext.parsedScreenDone()
            quantityParsed = false
        }
    }

    return initiallySeenQuantity
}

internal const val NumBasalProfileFactors = 24

object RTCommandProgressStage {
    /**
     * Basal profile setting stage.
     *
     * @property numSetFactors How many basal rate factors have been set by now.
     *           When the basal profile has been fully set, this value equals
     *           the value of totalNumFactors.
     * @property totalNumFactors Total number of basal rate factors.
     */
    data class SettingBasalProfile(val numSetFactors: Int, val totalNumFactors: Int = NumBasalProfileFactors) : ProgressStage("settingBasalProfile")

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
}

/**
 * Sets the Combo's basal profile via the remote terminal (RT) mode.
 *
 * This function suspends until the basal profile is fully set. Optionally,
 * a [ProgressReporter] can be specified to get informed about the basal
 * profile setting progress. Since setting a profile can take quite a
 * while, it is recommended to make use of this to show some sort of progress
 * indicator on a GUI.
 *
 * The supplied [basalProfile] must contain exactly 24 integers (once per
 * basal profile factor).
 *
 * @param rtNavigationContext RT navigation context to use for cycling through screens.
 * @param basalProfile Basal profile to set.
 * @param progressReporter [ProgressReporter] for tracking basal profile setting progress.
 * @throws IllegalArgumentException if the basal profile does not contain
 *         exactly 24 values (factors), or if at least one of the factors
 *         is <0.
 * @throws IllegalStateException if this function is called while the pump
 *         is not in the remote terminal mode.
 */
suspend fun setBasalProfile(
    rtNavigationContext: RTNavigationContext,
    basalProfile: List<Int>,
    progressReporter: ProgressReporter? = null
) {
    require(basalProfile.size == NumBasalProfileFactors)
    require(basalProfile.all { it >= 0 })
    check(rtNavigationContext.pump.currentModeFlow.value == PumpIO.Mode.REMOTE_TERMINAL)

    progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(0))

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

            progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingBasalProfile(index + 1))

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

        progressReporter?.setCurrentProgressStage(BasicProgressStage.Finished)
    } catch (e: Exception) {
        progressReporter?.setCurrentProgressStage(BasicProgressStage.Aborted)
        throw e
    }
}

/**
 * Sets the Combo's current temporary basal rate (TBR) via the remote terminal (RT) mode.
 *
 * This function suspends until the TBR is fully set. Optionally, a
 * [ProgressReporter] can be specified to get informed about the TBR setting
 * progress. Since setting a TBR can take a while, it is recommended to
 * make use of this to show some sort of progress indicator on a GUI.
 *
 * If the percentage is 100, any ongoing TBR will be cancelled.
 *
 * [percentage] must be in the range 0-500 (specifying the % of the TBR),
 * and an integer multiple of 10.
 * [durationInMinutes] must be at least 15 (since the Combo cannot do TBRs
 * that are shorter than 15 minutes), and must an integer multiple of 15.
 * However, if [percentage] is 100, then the value of [durationInMinutes]
 * is ignored.
 *
 * @param rtNavigationContext RT navigation context to use for cycling through screens.
 * @param percentage TBR percentage to set.
 * @param durationInMinutes TBR duration in minutes to set.
 * @param progressReporter [ProgressReporter] for tracking TBR setting progress.
 * @throws IllegalArgumentException if the percentage is not in the 0-500 range,
 *         or if the percentage value is not an integer multiple of 10, or if
 *         the duration is <15 or not an integer multiple of 15 (see the note
 *         about duration being ignored with percentage 100 above though).
 * @throws IllegalStateException if this function is called while the pump
 *         is not in the remote terminal mode.
 */
suspend fun setTemporaryBasalRate(
    rtNavigationContext: RTNavigationContext,
    percentage: Int,
    durationInMinutes: Int,
    progressReporter: ProgressReporter? = null
) {
    // The Combo can only set TBRs of up to 500%, and the
    // duration can only be an integer multiple of 15 minutes.
    require((percentage >= 0) && (percentage <= 500))
    require((percentage % 10) == 0)
    require((percentage == 100) || ((durationInMinutes >= 15) && ((durationInMinutes % 15) == 0)))

    progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(0))

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
                    progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(settingProgress))
                }
            }

            currentPercentage
        }

        progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRPercentage(100))

        // If the percentage is 100%, we are done (and navigating to
        // the duration screen is not possible). Otherwise, continue.
        if (percentage != 100) {
            initialQuantityDistance = null

            progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(0))

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
                        progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(settingProgress))
                    }
                }

                currentDuration
            }
        }

        progressReporter?.setCurrentProgressStage(RTCommandProgressStage.SettingTBRDuration(100))

        // TBR set. Press CHECK to confirm it and exit back to the main menu.
        rtNavigationContext.pushButton(RTNavigationButton.CHECK)
        waitUntilScreenAppears(rtNavigationContext, ParsedScreen.MainScreen::class)

        progressReporter?.setCurrentProgressStage(BasicProgressStage.Finished)
    } catch (e: Exception) {
        progressReporter?.setCurrentProgressStage(BasicProgressStage.Aborted)
        throw e
    }
}
