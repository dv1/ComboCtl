package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.ComboException
import info.nightscout.comboctl.base.DisplayFrame
import kotlin.math.min

// Utility class for returning both the parsed value and the number
// of pattern matches that were parsed to get that value.
private data class ParsedValue<T>(val value: T, val numParsedPatternMatches: Int)

// Utility class a parsed time value.
private data class ParsedTime(val hours: Int, val minutes: Int)

/**
 * Reservoir state as shown on display.
 */
enum class ReservoirState {
    EMPTY,
    LOW,
    FULL
}

/**
 * Possible contents of [ParsedScreen.MainScreen].
 */
sealed class MainScreenContent {
    data class Normal(
        val currentTimeHours: Int,
        val currentTimeMinutes: Int,
        val activeBasalRateNumber: Int,
        val currentBasalRateFactor: Int
    ) : MainScreenContent()

    data class Stopped(
        val currentTimeHours: Int,
        val currentTimeMinutes: Int
    ) : MainScreenContent()

    data class Tbr(
        val currentTimeHours: Int,
        val currentTimeMinutes: Int,
        val remainingTbrDurationHours: Int,
        val remainingTbrDurationMinutes: Int,
        val tbrPercentage: Int,
        val activeBasalRateNumber: Int,
        val currentBasalRateFactor: Int
    ) : MainScreenContent()
}

/**
 * Result of a successful [parseDisplayFrame] call.
 *
 * Subclasses which have hour quantities use a 0..23 range for the hours.
 * (Even if the screen showed the hours in the 12-hour AM/PM format, they are
 * converted to the 24-hour format.) Minute quantities use a 0..59 range.
 *
 * Insulin units use an integer-encoded-decimal scheme. The last 3 digits of
 * the integer make up the 3 most significant fractional digits of a decimal.
 * For example, "37.5" is encoded as 37500, "10" as 10000, "0.02" as 20 etc.
 */
sealed class ParsedScreen {
    data class MainScreen(val content: MainScreenContent) : ParsedScreen()

    object BasalRateProfileSelectionMenuScreen : ParsedScreen()
    object BluetoothSettingsMenuScreen : ParsedScreen()
    object ExtendedBolusMenuScreen : ParsedScreen()
    object MultiwaveBolusMenuScreen : ParsedScreen()
    object MenuSettingsMenuScreen : ParsedScreen()
    object MyDataMenuScreen : ParsedScreen()
    object BasalRate1ProgrammingMenuScreen : ParsedScreen()
    object BasalRate2ProgrammingMenuScreen : ParsedScreen()
    object BasalRate3ProgrammingMenuScreen : ParsedScreen()
    object BasalRate4ProgrammingMenuScreen : ParsedScreen()
    object BasalRate5ProgrammingMenuScreen : ParsedScreen()
    object PumpSettingsMenuScreen : ParsedScreen()
    object ReminderSettingsMenuScreen : ParsedScreen()
    object TimeAndDateSettingsMenuScreen : ParsedScreen()
    object StandardBolusMenuScreen : ParsedScreen()
    object StopPumpMenuScreen : ParsedScreen()
    object TemporaryBasalRateMenuScreen : ParsedScreen()
    object TherapySettingsMenuScreen : ParsedScreen()

    data class WarningScreen(val warningNumber: Int) : ParsedScreen()
    data class ErrorScreen(val errorNumber: Int) : ParsedScreen()

    data class BasalRateTotalScreen(val totalNumUnits: Int) : ParsedScreen()
    data class BasalRateFactorSettingScreen(
        val beginHours: Int,
        val beginMinutes: Int,
        val endHours: Int,
        val endMinutes: Int,
        val numUnits: Int
    ) : ParsedScreen()

    data class TemporaryBasalRatePercentageScreen(val percentage: Int?) : ParsedScreen()
    data class TemporaryBasalRateDurationScreen(val hours: Int, val minutes: Int) : ParsedScreen()

    data class QuickinfoMainScreen(val availableUnits: Int, val reservoirState: ReservoirState) : ParsedScreen()

    data class TimeAndDateSettingsHourScreen(val hour: Int) : ParsedScreen()
    data class TimeAndDateSettingsMinuteScreen(val minute: Int) : ParsedScreen()
    data class TimeAndDateSettingsYearScreen(val year: Int) : ParsedScreen()
    data class TimeAndDateSettingsMonthScreen(val month: Int) : ParsedScreen()
    data class TimeAndDateSettingsDayScreen(val day: Int) : ParsedScreen()
}

/**
 * Exception thrown when during a [parseDisplayFrame] call something in a frame is found to be invalid.
 *
 * @param message The detail message.
 * @property displayFrame The frame with the invalid content.
 */
class DisplayFrameParseException(message: String, val displayFrame: DisplayFrame) : ComboException(message)

/**
 * Parses a given display frame, tries to recognize the frame, and extract information from it.
 *
 * @param displayFrame The display frame to parse.
 * @return The [ParsedScreen] of the parsing process, or null if the screen wasn't recognized.
 * @throws DisplayFrameParseException if the display frame was recognized but something
 *         in the frame is bogus/invalid (suspiciously high insulin amounts for example).
 */
fun parseDisplayFrame(displayFrame: DisplayFrame): ParsedScreen? {
    var result: ParsedScreen?

    // Find the pattern matches first. We'll parse the
    // resulting list to try to recognize the screen type.
    val matches = findPatternMatches(displayFrame)

    // First, try to parse screens that show a clock at
    // the top left corner. This is easy and quick to
    // check, and only a few screens have it.
    result = tryParseTopLeftClockScreens(displayFrame, matches)
    if (result != null)
        return result

    // Next, try to parse menu screens. These are
    // characterized by a large symbol at the bottom
    // center of the screen, and thus are easy and
    // quick to recognize.
    result = tryParseMenuScreen(matches)
    if (result != null)
        return result

    // Next, try to parse the menu title at the top
    // (if there is any) and match a screen using
    // that title. This also returns the number of
    // character pattern matches associated with the
    // title. This is useful if the title could not
    // be recognized and subsequent screen parse
    // functions need to skip the title.
    val (titleResult, numTitlePatternMatches) = tryParseScreenByTitle(displayFrame, matches)
    if (titleResult != null)
        return titleResult

    // Check if this is a warning / error screen.
    result = tryParseWarningOrMenuScreen(matches, numTitlePatternMatches)
    if (result != null)
        return result

    // Finally, try to parse miscellaneous screens.
    result = tryParseBasalRateTotalScreen(matches, numTitlePatternMatches)
    if (result != null)
        return result

    return null
}

/************* Parsers for main screen categories and misc screens *************/

private fun tryParseTopLeftClockScreens(displayFrame: DisplayFrame, matches: PatternMatches): ParsedScreen? {
    var result: ParsedScreen?

    if (matches.isEmpty())
        return null

    // Verify that the very first matched pattern (the clock symbol
    // at the top left) is actually present.
    if (matches[0].glyph != Glyph.SmallSymbol(Symbol.SMALL_CLOCK))
        return null

    // First attempt - perhaps this is a screen where one of the basal
    // rate factors is set. Check if it can be recognized as such-
    result = tryParseBasalRateFactorSettingScreen(matches)
    if (result != null)
        return result

    // Next, try the main screen (the one the user sees first
    // when the pump's LCD switches on).
    result = tryParseMainScreen(displayFrame, matches)
    if (result != null)
        return result

    return null
}

private fun tryParseMenuScreen(matches: PatternMatches): ParsedScreen? {
    // Menu screens are characterized by one icon at the bottom center
    // that identifies what menu screen this is. There is also a title
    // at the top, but that one is redundant for parsing purposes,
    // and is thus ignored here.

    // Need at least the menu icon.
    if (matches.isEmpty())
        return null

    val lastGlyph = matches.last().glyph

    when (lastGlyph) {
        Glyph.LargeSymbol(Symbol.LARGE_BOLUS) -> return ParsedScreen.StandardBolusMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_EXTENDED_BOLUS) -> return ParsedScreen.ExtendedBolusMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_MULTIWAVE) -> return ParsedScreen.MultiwaveBolusMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_BLUETOOTH_SETTINGS) -> return ParsedScreen.BluetoothSettingsMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_MENU_SETTINGS) -> return ParsedScreen.MenuSettingsMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_MY_DATA) -> return ParsedScreen.MyDataMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_BASAL) -> return ParsedScreen.BasalRateProfileSelectionMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_PUMP_SETTINGS) -> return ParsedScreen.PumpSettingsMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_REMINDER_SETTINGS) -> return ParsedScreen.ReminderSettingsMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_CALENDAR_AND_CLOCK) -> return ParsedScreen.TimeAndDateSettingsMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_STOP) -> return ParsedScreen.StopPumpMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_TBR) -> return ParsedScreen.TemporaryBasalRateMenuScreen
        Glyph.LargeSymbol(Symbol.LARGE_THERAPY_SETTINGS) -> return ParsedScreen.TherapySettingsMenuScreen
        else -> Unit
    }

    // Special case: If the semi-last glyph is a LARGE_BASAL symbol,
    // and the last glyph is a large digit, this may be one of the
    // basal rate programming menu screens.
    if ((matches.size >= 2) &&
        (lastGlyph is Glyph.LargeDigit) &&
        (matches[matches.size - 2].glyph == Glyph.LargeSymbol(Symbol.LARGE_BASAL))) {
        return when (lastGlyph.digit) {
            1 -> ParsedScreen.BasalRate1ProgrammingMenuScreen
            2 -> ParsedScreen.BasalRate2ProgrammingMenuScreen
            3 -> ParsedScreen.BasalRate3ProgrammingMenuScreen
            4 -> ParsedScreen.BasalRate4ProgrammingMenuScreen
            5 -> ParsedScreen.BasalRate5ProgrammingMenuScreen
            else -> null
        }
    }

    return null
}

private fun tryParseScreenByTitle(displayFrame: DisplayFrame, matches: PatternMatches): ParsedValue<ParsedScreen?> {
    // Try to parse the title. If the frame contains a screen with
    // a title, then the first N matches will contain small character
    // glyphs that make up said title.
    val (titleString, numTitleCharacters) = parseTitleString(matches)

    // Get an ID for the title. This ID is language independent
    // and thus much more useful for identifying the screen here.
    val titleId = knownScreenTitles[titleString]

    val result = when (titleId) {
        TitleID.QUICK_INFO -> parseQuickinfoScreen(displayFrame, matches, numTitleCharacters)
        TitleID.TBR_PERCENTAGE -> parseTemporaryBasalRatePercentageScreen(matches, numTitleCharacters)
        TitleID.TBR_DURATION -> parseTemporaryBasalRateDurationScreen(matches, numTitleCharacters)
        TitleID.HOUR,
        TitleID.MINUTE,
        TitleID.YEAR,
        TitleID.MONTH,
        TitleID.DAY -> parseTimeAndDateSettingsScreen(displayFrame, matches, titleId, numTitleCharacters)

        else -> null
    }

    return ParsedValue(result, numTitleCharacters)
}

private fun tryParseWarningOrMenuScreen(matches: PatternMatches, numTitlePatternMatches: Int): ParsedScreen? {
    // Start at an offset that is past the screen title. The
    // warning and error screens have multiple possible titles
    // depending on the exact nature of the warning / error.
    // Identifying these screens based on the title would
    // require a significant amount of translations done in
    // the knownScreenTitles table. But we do not need that,
    // since these screens can simply be identified by the
    // warning / error symbols instead. So, we ignore the
    // title, and move straight to the large symbol at the
    // center left of the screen.
    var curMatchesOffset = numTitlePatternMatches

    // If the screen consists of only the title,
    // it is not a warning / error screen.
    if (curMatchesOffset >= matches.size)
        return null

    // Identify the symbol at the center left. Exit if
    // it is not a large symbol, or not one we expect.
    val warningOrErrorSymbol: Symbol = when (matches[curMatchesOffset].glyph) {
        Glyph.LargeSymbol(Symbol.LARGE_WARNING),
        Glyph.LargeSymbol(Symbol.LARGE_ERROR) -> (matches[curMatchesOffset].glyph as Glyph.LargeSymbol).symbol
        else -> return null
    }

    // Move to the next match. There must be matches
    // left after the symbol.
    curMatchesOffset++
    if (curMatchesOffset >= matches.size)
        return null

    // The next match is a large character, either a "W"
    // or an "E". The former identifies a warning, the
    // latter an error.
    when (matches[curMatchesOffset].glyph) {
        Glyph.LargeCharacter('W'),
        Glyph.LargeCharacter('E') -> Unit
        else -> return null
    }
    curMatchesOffset++

    // Past the large character, we can find the number
    // of the warning / error.
    val warningOrErrorNumberParseResult = parseInteger(matches, curMatchesOffset) ?: return null
    curMatchesOffset += warningOrErrorNumberParseResult.numParsedPatternMatches

    // In the warning / error screens, there are some
    // matches left after the warning / error number.
    // These are: a small check symbol, and some text.
    // We see if the check symbol is there (the text
    // is not of interest to us). If the check symbol
    // isn't there, this is not a warning / error screen.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.SmallSymbol(Symbol.SMALL_CHECK)))
        return null

    return when (warningOrErrorSymbol) {
        Symbol.LARGE_WARNING -> ParsedScreen.WarningScreen(warningOrErrorNumberParseResult.value)
        Symbol.LARGE_ERROR -> ParsedScreen.ErrorScreen(warningOrErrorNumberParseResult.value)
        else -> null
    }
}

private fun tryParseBasalRateTotalScreen(matches: PatternMatches, numTitlePatternMatches: Int): ParsedScreen? {
    // Start at an offset that is past the screen title.
    // We can identify this screen without having to
    // resort to the title.
    var curMatchesOffset = numTitlePatternMatches

    // There must be a LARGE_BASAL_SET symbol glyph after
    // the title in the matches list.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_BASAL_SET)))
        return null
    curMatchesOffset++

    // Following the icon, we can find the total number
    // of IUs in the basal rate.
    val totalNumUnitsParseResult = parseDecimal(matches, curMatchesOffset) ?: return null
    curMatchesOffset += totalNumUnitsParseResult.numParsedPatternMatches

    // Past the total amount of IUs, there's a small check
    // symbol. If it doesn't exist, this is not a basal
    // rate total screen.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeCharacter('u')))
        return null

    return ParsedScreen.BasalRateTotalScreen(totalNumUnits = totalNumUnitsParseResult.value)
}

/************* Parsers for top-left clock screens *************/

private fun tryParseBasalRateFactorSettingScreen(matches: PatternMatches): ParsedScreen? {
    // Start at 1 to skip the clock symbol. This functio is called
    // by tryParseTopLeftClockScreens() precisely _because_ that
    // function already checked for that clock symbol.
    var curMatchesOffset = 1

    // Parse the begin time of the basal rate factor.
    val beginTimeParseResult = parseTime(matches, curMatchesOffset) ?: return null
    curMatchesOffset += beginTimeParseResult.numParsedPatternMatches

    // There is a minus symbol between the begin and end times
    // that keeps them visually separated.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.SmallSymbol(Symbol.SMALL_MINUS)))
        return null
    curMatchesOffset++

    // Parse the end time of the basal rate factor.
    val endTimeParseResult = parseTime(matches, curMatchesOffset) ?: return null
    curMatchesOffset += endTimeParseResult.numParsedPatternMatches

    // Next, wee expect a LARGE_BASAL symbol.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_BASAL)))
        return null
    curMatchesOffset++

    // The number of IUs per hour for the current factor follow.
    val numUnitsParseResult = parseDecimal(matches, curMatchesOffset) ?: return null
    curMatchesOffset += numUnitsParseResult.numParsedPatternMatches

    // Finallly, there is an U/h symbol.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_UNITS_PER_HOUR)))
        return null

    return ParsedScreen.BasalRateFactorSettingScreen(
        beginHours = beginTimeParseResult.value.hours,
        beginMinutes = beginTimeParseResult.value.minutes,
        endHours = endTimeParseResult.value.hours,
        endMinutes = endTimeParseResult.value.minutes,
        numUnits = numUnitsParseResult.value
    )
}

private fun tryParseMainScreen(displayFrame: DisplayFrame, matches: PatternMatches): ParsedScreen? {
    // Start at 1 to skip the clock symbol. This functio is called
    // by tryParseTopLeftClockScreens() precisely _because_ that
    // function already checked for that clock symbol.
    var curMatchesOffset = 1

    // Right after the clock symbol, the main screen shows
    // the current time. Parse that one and move past it.
    val currentTimeParseResult = parseTime(matches, curMatchesOffset) ?: return null
    curMatchesOffset += currentTimeParseResult.numParsedPatternMatches
    if (curMatchesOffset >= matches.size)
        return null

    val hours = currentTimeParseResult.value.hours
    val minutes = currentTimeParseResult.value.minutes

    // There must always be some more matches after the time.
    // If not, then this is not a valid main screen.
    if (curMatchesOffset >= matches.size)
        return null

    // There are variations of the "default" main screen. One is
    // a main screen that shows the LARGE_STOP symbol. That one is
    // active when the pump is stopped. Another variant is when a
    // TBR is active. The main screen shows additional info then.
    when {
        matches.last().glyph == Glyph.LargeSymbol(Symbol.LARGE_STOP) -> {
            // If there is a stop symbol at the center, this is the
            // stopped variant of the main screen.

            return ParsedScreen.MainScreen(
                MainScreenContent.Stopped(
                    currentTimeHours = hours,
                    currentTimeMinutes = minutes
                )
            )
        }
        matches[curMatchesOffset].glyph == Glyph.SmallSymbol(Symbol.SMALL_ARROW) -> {
            // Another variant is the TBR main screen. In that one,
            // there is a SMALL_ARROW to the right of the time, followed
            // by the remaining TBR duration. If that SMALL_ARROW symbol
            // is there, parse this as a main screen with extra TBR info.

            curMatchesOffset++ // Move past the arrow icon.

            return tryParseMainScreenWithTbrInfo(displayFrame, matches, curMatchesOffset, hours, minutes)
        }
        else -> {
            // This is a default, normal main screen.
            return tryParseNormalMainScreen(displayFrame, matches, curMatchesOffset, hours, minutes)
        }
    }
}

private fun tryParseNormalMainScreen(
    displayFrame: DisplayFrame,
    matches: PatternMatches,
    matchesOffset: Int,
    hours: Int,
    minutes: Int
): ParsedScreen? {
    // Start parsing right after the SMALL_ARROW symbol (tryParseMainScreen()
    // takes care of checking for that symbol glyph already.)
    var curMatchesOffset = matchesOffset

    // Directly past the SMALL_ARROW symbol there is the LARGE_BASAL symbol.
    // On the screen, it is located at the center left.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_BASAL)))
        return null
    curMatchesOffset++

    // The current basal rate factor follows.
    val currentBasalRateFactorParseResult = parseDecimal(matches, curMatchesOffset) ?: return null
    curMatchesOffset += currentBasalRateFactorParseResult.numParsedPatternMatches

    // Next comes an U/h symbol.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_UNITS_PER_HOUR)))
        return null
    curMatchesOffset++

    // Next comes a small digit that indicates which one of the basal rates
    // is currently active. This is a value from 1 to 5.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph !is Glyph.SmallDigit))
        return null
    val activeBasalRateNumber = (matches[curMatchesOffset].glyph as Glyph.SmallDigit).digit
    if ((activeBasalRateNumber < 1) || (activeBasalRateNumber > 5))
        throw DisplayFrameParseException(
            "Main screen with TBR info contains bogus active basal rate number $activeBasalRateNumber",
            displayFrame
        )

    return ParsedScreen.MainScreen(
        MainScreenContent.Normal(
            currentTimeHours = hours,
            currentTimeMinutes = minutes,
            activeBasalRateNumber = activeBasalRateNumber,
            currentBasalRateFactor = currentBasalRateFactorParseResult.value
        )
    )
}

private fun tryParseMainScreenWithTbrInfo(
    displayFrame: DisplayFrame,
    matches: PatternMatches,
    matchesOffset: Int,
    hours: Int,
    minutes: Int
): ParsedScreen? {
    // Start parsing right after the SMALL_ARROW symbol (tryParseMainScreen()
    // takes care of checking for that symbol glyph already.)
    var curMatchesOffset = matchesOffset

    // Directly past the SMALL_ARROW symbol there is the remaining TBR duration.
    val remainingTbrDurationParseResult = parseTime(matches, curMatchesOffset) ?: return null
    curMatchesOffset += remainingTbrDurationParseResult.numParsedPatternMatches

    // After the TBR duration the next match should be the LARGE_BASAL symbol.
    // On the screen, it is located at the center left.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_BASAL)))
        return null
    curMatchesOffset++

    // Directly after the LARGE_BASAL symbol there is a small arrow that points
    // up- or downwards, depending on whether the TBR percentage is >100% or
    // <100%. We do check for these symbols to verify that this inded is
    // a main screen with TBR info.
    if (curMatchesOffset >= matches.size)
        return null
    when (matches[curMatchesOffset].glyph) {
        Glyph.SmallSymbol(Symbol.SMALL_UP),
        Glyph.SmallSymbol(Symbol.SMALL_DOWN) -> Unit
        else -> return null
    }
    curMatchesOffset++

    // The TBR percentage follows.
    val tbrPercentageParseResult = parseInteger(matches, curMatchesOffset) ?: return null
    curMatchesOffset += tbrPercentageParseResult.numParsedPatternMatches

    // After the percentage integere, there must be a large percent symbol.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_PERCENT)))
        return null
    curMatchesOffset++

    // Next comes a small digit that indicates which one of the basal rates
    // is currently active. This is a value from 1 to 5.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph !is Glyph.SmallDigit))
        return null
    val activeBasalRateNumber = (matches[curMatchesOffset].glyph as Glyph.SmallDigit).digit
    if ((activeBasalRateNumber < 1) || (activeBasalRateNumber > 5))
        throw DisplayFrameParseException(
            "Main screen with TBR info contains bogus active basal rate number $activeBasalRateNumber",
            displayFrame
        )
    curMatchesOffset++

    // The current basal rate factor follows.
    val currentBasalRateFactorParseResult = parseDecimal(matches, curMatchesOffset) ?: return null

    return ParsedScreen.MainScreen(
        MainScreenContent.Tbr(
            currentTimeHours = hours,
            currentTimeMinutes = minutes,
            remainingTbrDurationHours = remainingTbrDurationParseResult.value.hours,
            remainingTbrDurationMinutes = remainingTbrDurationParseResult.value.minutes,
            tbrPercentage = tbrPercentageParseResult.value,
            activeBasalRateNumber = activeBasalRateNumber,
            currentBasalRateFactor = currentBasalRateFactorParseResult.value
        )
    )
}

/************* Parsers that identify screens by parsing the title *************/

private fun parseQuickinfoScreen(
    displayFrame: DisplayFrame,
    matches: PatternMatches,
    numTitleCharacters: Int
): ParsedScreen.QuickinfoMainScreen? {
    // A quickinfo screen always contains at least the title string,
    // the reservoir state symbol, and at least one digit (the number
    // of units in the reservoir).
    if (matches.size < (numTitleCharacters + 2))
        return null

    // Start to parse right after the matches that make up the title.
    val curMatchesOffset = numTitleCharacters

    // Check the reservoir state symbol.
    val reservoirState = when (matches[curMatchesOffset + 0].glyph) {
        Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_EMPTY) -> ReservoirState.EMPTY
        Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_LOW) -> ReservoirState.LOW
        Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_FULL) -> ReservoirState.FULL
        else -> return null
    }

    // Next, parse the number of IUs available in the reservoir.
    val availableUnitsParseResult = parseInteger(matches, curMatchesOffset + 1)
    if (availableUnitsParseResult == null)
        return null

    // Sanity check. Reservoirs cannot handle more than 350 IU.
    // TODO: This needs to be adjusted in case the Combo can be
    // configured to support U200 and U500 insulin.
    // TODO: Is 350 the correct limit, or should it be 315 instead?
    if (availableUnitsParseResult.value > 350)
        throw DisplayFrameParseException(
            "Bogus insulin amount (${availableUnitsParseResult.value} IU) found in quickinfo screen",
            displayFrame
        )

    return ParsedScreen.QuickinfoMainScreen(
        availableUnits = availableUnitsParseResult.value,
        reservoirState = reservoirState
    )
}

private fun parseTemporaryBasalRatePercentageScreen(matches: PatternMatches, numTitleCharacters: Int): ParsedScreen? {
    // Start to parse right after the matches that make up the title.
    var curMatchesOffset = numTitleCharacters

    // Next, we expect the LARGE_BASAL symbol to be there.
    // It is located at the center left.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_BASAL)))
        return null
    curMatchesOffset++

    // The currently picked TBR percentage follows. The screen may currently
    // not be showing the actual percentage (since it blinks). If so, then
    // there are no integer digits before the percentage symbol. In that
    // case we just continue to the percentage symbol check below.
    val percentageParseResult = parseInteger(matches, curMatchesOffset)
    if (percentageParseResult != null)
        curMatchesOffset += percentageParseResult.numParsedPatternMatches

    // Right after the TBR percentage there must be a LARGE_PERCENT symbol.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_PERCENT)))
        return null

    // Set percentage to the parsed integer value, or to null in case the
    // percentage is currently blinking and not shown.
    return ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = percentageParseResult?.value)
}

private fun parseTemporaryBasalRateDurationScreen(matches: PatternMatches, numTitleCharacters: Int): ParsedScreen? {
    // Start to parse right after the matches that make up the title.
    var curMatchesOffset = numTitleCharacters

    // Next, we expect the LARGE_ARROW symbol to be there.
    // It is located at the center left.
    if ((curMatchesOffset >= matches.size) || (matches[curMatchesOffset].glyph != Glyph.LargeSymbol(Symbol.LARGE_ARROW)))
        return null
    curMatchesOffset++

    // Next comes the currently picked TBR duration.
    val durationParseResult = parseTime(matches, curMatchesOffset) ?: return null

    return ParsedScreen.TemporaryBasalRateDurationScreen(
        hours = durationParseResult.value.hours,
        minutes = durationParseResult.value.minutes
    )
}

private fun parseTimeAndDateSettingsScreen(
    displayFrame: DisplayFrame,
    matches: PatternMatches,
    titleID: TitleID,
    numTitleCharacters: Int
): ParsedScreen? {
    // Start to parse right after the matches that make up the title.
    var curMatchesOffset = numTitleCharacters

    // We do expect more content after the title. If there isn't
    // any, then this is not a TimeAndDate screen.
    if (curMatchesOffset >= matches.size)
        return null

    // This screen is special in that it contains multiple types
    // of information, but only one can be visible at the same time.
    // These "sub-screens" all use either the LARGE_CLOCK or the
    // LARGE_CALENDAR symbol. We cannot therefore use the symbol
    // alone for identifying that sub-screen - we use the title ID
    // for that. In any case, either one of these two symbols must
    // follow the title, otherwise this is not a TimeAndDate screen.

    when (matches[curMatchesOffset].glyph) {
        Glyph.LargeSymbol(Symbol.LARGE_CLOCK),
        Glyph.LargeSymbol(Symbol.LARGE_CALENDAR) -> Unit
        else -> return null
    }

    curMatchesOffset++

    // All sub-screens show an integer after the icon (except for
    // the sub-screen that picks the date format, but we anyway
    // do not need to care about that one). The partcular
    // meaning of this integer depends on the sub-screen.
    val integerParseResult = parseInteger(matches, curMatchesOffset, ParseIntegerMode.LARGE_DIGITS_ONLY)
        ?: return null
    curMatchesOffset += integerParseResult.numParsedPatternMatches

    // The parsed integer may need adjustment if this is the
    // "hour" sub-screen and if a 12-hour time format is used.
    // The ComboCtl API always deals with 24-hour time formats.
    var integer = integerParseResult.value
    if (titleID == TitleID.HOUR) {
        if (((matches.size - curMatchesOffset) >= 2) && (matches[curMatchesOffset + 1].glyph == Glyph.SmallCharacter('M'))) {
            when (matches[curMatchesOffset].glyph) {
                // 12 AM means hour 0 in 24-hour format.
                Glyph.SmallCharacter('A') ->
                    if (integer == 12)
                        integer = 0
                // The values from 1PM to 11PM refer to
                // hours 13 to 23 in 24-hour format. 12PM
                // however means 12 in 24-hour format, so
                // treat that one differently.
                Glyph.SmallCharacter('P') ->
                    if (integer != 12)
                        integer += 12
                else -> Unit
            }
        }
    }

    return when (titleID) {
        TitleID.HOUR -> ParsedScreen.TimeAndDateSettingsHourScreen(integer)
        TitleID.MINUTE -> ParsedScreen.TimeAndDateSettingsMinuteScreen(integer)
        TitleID.YEAR -> ParsedScreen.TimeAndDateSettingsYearScreen(integer)
        TitleID.MONTH -> ParsedScreen.TimeAndDateSettingsMonthScreen(integer)
        TitleID.DAY -> ParsedScreen.TimeAndDateSettingsDayScreen(integer)
        else ->
            throw DisplayFrameParseException(
                "Invalid title ID $titleID when processing TimeAndDate settings screen",
                displayFrame
            )
    }
}

/************* Utility code for the parsers above *************/

private val timeRegex = "(\\d\\d):?(\\d\\d)(AM|PM)?|(\\d\\d)(AM|PM)".toRegex()
private const val asciiDigitOffset = '0'.toInt()

private fun amPmTo24Hours(hours: Int, amPm: String) =
    if ((hours == 12) && (amPm == "AM"))
        0
    else if ((hours != 12) && (amPm == "PM"))
        hours + 12
    else
        hours

// Result: <hours, minutes, numMatches>
private fun parseTime(matches: PatternMatches, matchesOffset: Int): ParsedValue<ParsedTime>? {
    // Parse strings that specify a time.
    //
    // These time formats are used by the Combo:
    //     HH:MM
    //     HH:MM(AM/PM)
    //     HH(AM/PM)
    //
    // Examples:
    //   14:00
    //   11:47AM
    //   09PM
    //
    // To be able to handle all of these without too much
    // convoluted (and error prone) parsing code, we use
    // regex in this case.
    //
    // We also count the pattern matches here that make
    // up the time and return this (if a valid time string
    // is found). That information is useful for skipping
    // to the next matches.
    //
    // NOTE: "matches" refer to *pattern* matches here.
    // These are not to be confused with regex matches.

    var matchesAsString = ""
    var numMatches = 0

    val numAvailableMatches = matches.size - matchesOffset
    // The shortest possible string is "09PM", so min. 4 matches are required.
    if (numAvailableMatches < 4)
        return null

    // Scan the matches and convert them to a string that can
    // be parsed by the regex. Limit the amount of scanned
    // pattern matches to a maximum of 7, since the largest
    // possible time string is 7 characters long.
    for (i in 0 until min(matches.size - matchesOffset, 7)) {
        val glyph = matches[i + matchesOffset].glyph

        matchesAsString += when (glyph) {
            // Valid glyphs are converted to characters and added to the string.
            is Glyph.SmallDigit -> (glyph.digit + asciiDigitOffset).toChar()
            is Glyph.LargeDigit -> (glyph.digit + asciiDigitOffset).toChar()
            is Glyph.SmallCharacter -> glyph.character
            is Glyph.LargeCharacter -> glyph.character
            Glyph.SmallSymbol(Symbol.SMALL_SEPARATOR) -> ':'
            Glyph.LargeSymbol(Symbol.LARGE_SEPARATOR) -> ':'

            // Invalid glyph -> the time string ended, stop scan.
            else -> break
        }

        // Count the matches that make up the decimal.
        numMatches++
    }

    // Now apply the regex. Exit if the string does not match.
    val regexResult = timeRegex.find(matchesAsString) ?: return null

    // Analyze the regex find result.
    // The Regex result groups are:
    //
    // #0: The entire string
    // #1: Hours from a HH:MM or HH:MM(AM/PM) format
    // #2: Minutes from a HH:MM or HH:MM(AM/PM) format
    // #3: AM/PM specifier from a HH:MM or HH:MM(AM/PM) format
    // #4: Hours from a HH(AM/PM) format
    // #5: AM/PM specifier from a HH(AM/PM) format
    //
    // Groups without a found value are set to null.

    val regexGroups = regexResult.groups
    var hours = 0
    var minutes = 0

    if (regexGroups[1] != null) {
        // Possibility 1: This is a time string that matches
        // one of these two formats:
        //
        //     HH:MM
        //     HH:MM(AM/PM)
        //
        // This means that group #2 must not be null, since it
        // contains the minutes, and these are required here.

        if (regexGroups[2] == null)
            return null

        hours = regexGroups[1]!!.value.toInt()
        minutes = regexGroups[2]!!.value.toInt()

        // If there is an AM/PM specifier, convert the hour
        // to the 24-hour format.
        if (regexGroups[3] != null)
            hours = amPmTo24Hours(hours, regexGroups[3]!!.value)
    } else if (regexGroups[4] != null) {
        // Possibility 2: This is a time string that matches
        // this format:
        //
        //     HH(AM/PM)
        //
        // This means that group #5 must not be null, since it
        // contains the AM/PM specifier, and it is required here.

        if (regexGroups[5] == null)
            return null

        hours = amPmTo24Hours(
            regexGroups[4]!!.value.toInt(),
            regexGroups[5]!!.value
        )
    }

    return ParsedValue(ParsedTime(hours, minutes), numMatches)
}

private enum class ParseIntegerMode {
    ALL_DIGITS,
    SMALL_DIGITS_ONLY,
    LARGE_DIGITS_ONLY
}

private fun parseInteger(
    matches: PatternMatches,
    matchesOffset: Int,
    parseMode: ParseIntegerMode = ParseIntegerMode.ALL_DIGITS
): ParsedValue<Int>? {
    // This parses integers found in the pattern matches.
    //
    // We also count the pattern matches here that make
    // up the decimal and return this (if a valid decimal
    // is found). That information is useful for skipping
    // to the next matches.

    var integer = 0
    var integerEndOffset = -1
    var numMatches = 0

    // Scan the matches, starting at matchesOffset, until
    // either an invalid glyph is found, or the end of
    // the matches list is reached.
    for (index in matchesOffset until matches.size) {
        val glyph = matches[index].glyph

        when (glyph) {
            // This is a digit. Append it to the integer.
            is Glyph.SmallDigit ->
                when (parseMode) {
                    ParseIntegerMode.ALL_DIGITS,
                    ParseIntegerMode.SMALL_DIGITS_ONLY -> integer = integer * 10 + glyph.digit
                    else -> break
                }
            is Glyph.LargeDigit ->
                when (parseMode) {
                    ParseIntegerMode.ALL_DIGITS,
                    ParseIntegerMode.LARGE_DIGITS_ONLY -> integer = integer * 10 + glyph.digit
                    else -> break
                }

            // Invalid glyph found - abort scan.
            else -> {
                integerEndOffset = index
                break
            }
        }

        // Count the matches that make up the integer.
        numMatches++
    }

    // If the end of the integer was found right at the
    // very first match, then there's no integer to parse.
    if (integerEndOffset == matchesOffset)
        return null

    return ParsedValue(integer, numMatches)
}

private fun parseDecimal(matches: PatternMatches, matchesOffset: Int): ParsedValue<Int>? {
    // This parses decimal values like "0.22" or "123".
    // Decimals are encoded as integers whose last 3 digits
    // are the fractional. So, for example, decimal "12"
    // becomes integer 12000. Decimal "4.11" becomes integer
    // 4110 etc. This allows for storing decimals with
    // fractional portions without having to resort to
    // floating-point numbers (which may round the value).
    //
    // We also count the pattern matches here that make
    // up the decimal and return this (if a valid decimal
    // is found). That information is useful for skipping
    // to the next matches.

    var decimal = 0
    var decimalPointOffset = -1
    var decimalEndOffset = -1
    var numMatches = 0

    // Scan the matches, starting at matchesOffset, until
    // either an invalid glyph is found, or the end of
    // the matches list is reached.
    for (index in matchesOffset until matches.size) {
        val glyph = matches[index].glyph

        when (glyph) {
            // This is a digit. Append it to the decimal.
            is Glyph.SmallDigit -> decimal = decimal * 10 + glyph.digit
            is Glyph.LargeDigit -> decimal = decimal * 10 + glyph.digit

            // This is a decimal point. Record where it is
            // so that we can adjust the "decimal" integer
            // properly later.
            Glyph.SmallSymbol(Symbol.SMALL_DOT),
            Glyph.LargeSymbol(Symbol.LARGE_DOT) -> decimalPointOffset = index

            // Invalid glyph found - abort scan.
            else -> {
                decimalEndOffset = index
                break
            }
        }

        // Count the matches that make up the decimal.
        numMatches++
    }

    // If the end of the decimal was found right at the
    // very first match, then there's no decimal to parse.
    if (decimalEndOffset == 0)
        return null

    // This happens if the end of the matches list was reached.
    if (decimalEndOffset < 0)
        decimalEndOffset = matches.size

    // Adjust the integer to include the fractional part.
    if (decimalPointOffset > 0) {
        // A decimal point was found. Check how many fractional
        // digits were added. If less than 3, we have to pad
        // the integer with zeroes by repeatedly multiplying it
        // with 10.

        val numFractionals = (decimalEndOffset - decimalPointOffset - 1)
        if (numFractionals < 3) {
            for (i in 0 until (3 - numFractionals))
                decimal *= 10
        }
    } else {
        // There is no decimal point, so this is an integer.
        // Multiply it by 1000 to account for the implicit
        // three 0 fractional digits.
        decimal *= 1000
    }

    return ParsedValue(decimal, numMatches)
}

// Parses a title string and outputs it plus the number of matches
// that make up the string. Note that in the string, some whitespaces
// may be inserted if the space between the matches indicates the
// presence of a whitespace. These whitespaces are NOT added to the
// number of matched patterns, however. So, for example, the title
// "hello world" on screen would produce a "hello world" string,
// but the number of matched patterns would be 10, not 11, since
// the whitespace is not counted there. The reason for this is that
// the number of matched patterns is used for advancing *within*
// the matches list, and the whitespaces do not exist in that list.
private fun parseTitleString(matches: PatternMatches): ParsedValue<String> {
    var titleString = ""
    var lastMatch: PatternMatch? = null

    var numMatchedCharacters = 0

    stringScanLoop@ for (match in matches) {
        val glyph = match.glyph

        // Check if there's a newline or space between the matches.
        // If so, we'll insert a whitespace character into the string.
        val prependWhitespace = if (lastMatch != null)
            checkForWhitespaceAndNewline(lastMatch, match)
        else
            false

        val character = when (glyph) {
            is Glyph.SmallCharacter -> glyph.character
            Glyph.SmallSymbol(Symbol.SMALL_DOT) -> '.'
            Glyph.SmallSymbol(Symbol.SMALL_SEPARATOR) -> ':'
            Glyph.SmallSymbol(Symbol.SMALL_DIVIDE) -> '/'
            Glyph.SmallSymbol(Symbol.SMALL_BRACKET_LEFT) -> '('
            Glyph.SmallSymbol(Symbol.SMALL_BRACKET_RIGHT) -> ')'
            Glyph.SmallSymbol(Symbol.SMALL_MINUS) -> '-'
            else -> break@stringScanLoop
        }

        if (prependWhitespace)
            titleString += ' '

        titleString += character
        numMatchedCharacters++

        lastMatch = match
    }

    return ParsedValue(titleString.toUpperCase(), numMatchedCharacters)
}

// If true, then there is a whitespace between the matches,
// or the second match is located in a line below the first one.
private fun checkForWhitespaceAndNewline(firstMatch: PatternMatch, secondMatch: PatternMatch): Boolean {
    val y1 = firstMatch.y
    val y2 = secondMatch.y

    if ((y1 + firstMatch.pattern.height + 1) == y2)
        return true

    val x1 = firstMatch.x
    val x2 = secondMatch.x

    if ((x1 + firstMatch.pattern.width + 1 + 3) < x2)
        return true

    return false
}
