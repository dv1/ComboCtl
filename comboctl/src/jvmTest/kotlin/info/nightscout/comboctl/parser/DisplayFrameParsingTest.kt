package info.nightscout.comboctl.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DisplayFrameParsingTest {
    @Test
    fun checkFrameBasalRateFactorSettingParsing() {
        val result0 = parseDisplayFrame(testFrameBasalRateFactorSettingScreen0)
        assertEquals(
            ParsedScreen.BasalRateFactorSettingScreen(
                beginHours = 2,
                beginMinutes = 0,
                endHours = 3,
                endMinutes = 0,
                numUnits = 120
            ),
            result0
        )

        val result1 = parseDisplayFrame(testFrameBasalRateFactorSettingScreen1)
        assertEquals(
            ParsedScreen.BasalRateFactorSettingScreen(
                beginHours = 2,
                beginMinutes = 0,
                endHours = 3,
                endMinutes = 0,
                numUnits = 10000
            ),
            result1
        )

        val resultAM = parseDisplayFrame(testFrameBasalRateFactorSettingScreenAM)
        assertEquals(
            ParsedScreen.BasalRateFactorSettingScreen(
                beginHours = 0,
                beginMinutes = 0,
                endHours = 1,
                endMinutes = 0,
                numUnits = 50
            ),
            resultAM
        )

        val resultAMPM = parseDisplayFrame(testFrameBasalRateFactorSettingScreenAMPM)
        assertEquals(
            ParsedScreen.BasalRateFactorSettingScreen(
                beginHours = 11,
                beginMinutes = 0,
                endHours = 12,
                endMinutes = 0,
                numUnits = 0
            ),
            resultAMPM
        )

        val resultPMAM = parseDisplayFrame(testFrameBasalRateFactorSettingScreenPMAM)
        assertEquals(
            ParsedScreen.BasalRateFactorSettingScreen(
                beginHours = 23,
                beginMinutes = 0,
                endHours = 0,
                endMinutes = 0,
                numUnits = 0
            ),
            resultPMAM
        )
    }

    @Test
    fun checkMainScreenParsing() {
        val resultWithSeparator = parseDisplayFrame(testFrameMainScreenWithTimeSeparator)
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Normal(
                    currentTimeHours = 10,
                    currentTimeMinutes = 20,
                    activeBasalRateNumber = 1,
                    currentBasalRateFactor = 200
                )
            ),
            resultWithSeparator
        )

        val resultWithoutSeparator = parseDisplayFrame(testFrameMainScreenWithoutTimeSeparator)
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Normal(
                    currentTimeHours = 10,
                    currentTimeMinutes = 20,
                    activeBasalRateNumber = 1,
                    currentBasalRateFactor = 200
                )
            ),
            resultWithoutSeparator
        )
    }

    @Test
    fun checkMainScreenStoppedParsing() {
        val resultWithSeparator = parseDisplayFrame(testFrameMainScreenStoppedWithTimeSeparator)
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Stopped(
                    currentTimeHours = 10,
                    currentTimeMinutes = 20
                )
            ),
            resultWithSeparator
        )

        val resultWithoutSeparator = parseDisplayFrame(testFrameMainScreenStoppedWithoutTimeSeparator)
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Stopped(
                    currentTimeHours = 10,
                    currentTimeMinutes = 20
                )
            ),
            resultWithoutSeparator
        )
    }

    @Test
    fun checkMainScreenWithTbrInfoParsing() {
        val result = parseDisplayFrame(testFrameMainScreenWithTbrInfo)
        assertEquals(
            ParsedScreen.MainScreen(
                MainScreenContent.Tbr(
                    currentTimeHours = 10,
                    currentTimeMinutes = 21,
                    remainingTbrDurationHours = 0,
                    remainingTbrDurationMinutes = 30,
                    tbrPercentage = 110,
                    activeBasalRateNumber = 1,
                    currentBasalRateFactor = 220
                )
            ),
            result
        )
    }

    @Test
    fun checkStandardBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameStandardBolusMenuScreen)
        assert(result is ParsedScreen.StandardBolusMenuScreen)
    }

    @Test
    fun checkExtendedBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameExtendedBolusMenuScreen)
        assert(result is ParsedScreen.ExtendedBolusMenuScreen)
    }

    @Test
    fun checkMultiwaveBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMultiwaveBolusMenuScreen)
        assert(result is ParsedScreen.MultiwaveBolusMenuScreen)
    }

    @Test
    fun checkBluetoothSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameBluetoothSettingsMenuScreen)
        assert(result is ParsedScreen.BluetoothSettingsMenuScreen)
    }

    @Test
    fun checkMenuSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMenuSettingsMenuScreen)
        assert(result is ParsedScreen.MenuSettingsMenuScreen)
    }

    @Test
    fun checkMyDataMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMyDataMenuScreen)
        assert(result is ParsedScreen.MyDataMenuScreen)
    }

    @Test
    fun checkBasalRateProfileSelectionMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameBasalRateProfileSelectionMenuScreen)
        assert(result is ParsedScreen.BasalRateProfileSelectionMenuScreen)
    }

    @Test
    fun checkBasalRateProgrammingMenuScreenParsing() {
        for (i in 1.rangeTo(5)) {
            val testFrame = when (i) {
                1 -> testFrameProgramBasalRate1MenuScreen
                2 -> testFrameProgramBasalRate2MenuScreen
                3 -> testFrameProgramBasalRate3MenuScreen
                4 -> testFrameProgramBasalRate4MenuScreen
                5 -> testFrameProgramBasalRate5MenuScreen
                // Should not happen since the range is fixed above.
                else -> fail("No test frame for index  $i")
            }
            val result = parseDisplayFrame(testFrame)
            assertEquals(
                when (i) {
                    1 -> ParsedScreen.BasalRate1ProgrammingMenuScreen
                    2 -> ParsedScreen.BasalRate2ProgrammingMenuScreen
                    3 -> ParsedScreen.BasalRate3ProgrammingMenuScreen
                    4 -> ParsedScreen.BasalRate4ProgrammingMenuScreen
                    5 -> ParsedScreen.BasalRate5ProgrammingMenuScreen
                    else -> fail("Invalid index  $i")
                },
                result
            )
        }
    }

    @Test
    fun checkPumpSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFramePumpSettingsMenuScreen)
        assert(result is ParsedScreen.PumpSettingsMenuScreen)
    }

    @Test
    fun checkReminderSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameReminderSettingsMenuScreen)
        assert(result is ParsedScreen.ReminderSettingsMenuScreen)
    }

    @Test
    fun checkTimeAndDateSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTimeAndDateSettingsMenuScreen)
        assert(result is ParsedScreen.TimeAndDateSettingsMenuScreen)
    }

    @Test
    fun checkStopPumpMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameStopPumpMenuScreen)
        assert(result is ParsedScreen.StopPumpMenuScreen)
    }

    @Test
    fun checkTemporaryBasalRateMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTemporaryBasalRateMenuScreen)
        assert(result is ParsedScreen.TemporaryBasalRateMenuScreen)
    }

    @Test
    fun checkTherapySettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTherapySettingsMenuScreen)
        assert(result is ParsedScreen.TherapySettingsMenuScreen)
    }

    @Test
    fun checkQuickinfoMainScreenParsing() {
        val result = parseDisplayFrame(testFrameQuickinfoMainScreen)
        assertEquals(ParsedScreen.QuickinfoMainScreen(availableUnits = 213, reservoirState = ReservoirState.FULL), result)
    }

    @Test
    fun checkWarningScreenParsing() {
        val resultTbr = parseDisplayFrame(testFrameW6CancelTbrWarningScreen)
        assertEquals(ParsedScreen.WarningScreen(warningNumber = 6), resultTbr)

        // The main contents of the warning screen blink. During
        // the phase where the main contents aren't visible, we
        // cannot parse anything useful, so we do not expect a
        // result during that phase.
        val resultBolus0 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen0)
        assertEquals(null, resultBolus0)

        val resultBolus1 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen1)
        assertEquals(ParsedScreen.WarningScreen(warningNumber = 8), resultBolus1)

        // Same as above with regards to the blinking.
        val resultBolus2 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen2)
        assertEquals(null, resultBolus2)

        val resultBolus3 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen3)
        assertEquals(ParsedScreen.WarningScreen(warningNumber = 8), resultBolus3)
    }

    @Test
    fun checkTemporaryBasalRatePercentageParsing() {
        val result100 = parseDisplayFrame(testFrameTemporaryBasalRatePercentage100Screen)
        assertEquals(ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 100), result100)

        val result110 = parseDisplayFrame(testFrameTemporaryBasalRatePercentage110Screen)
        assertEquals(ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110), result110)

        val resultNoPercentage = parseDisplayFrame(testFrameTemporaryBasalRateNoPercentageScreen)
        assertEquals(ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = null), resultNoPercentage)

        val testScreens = listOf(
            Pair(testFrameTbrPercentageEnglishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSpanishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageFrenchScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageItalianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageRussianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageTurkishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentagePolishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 100)),
            Pair(testFrameTbrPercentageCzechScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageHungarianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSlovakScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageRomanianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageCroatianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageDutchScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageGreekScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageFinnishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageNorwegianScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentagePortugueseScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSwedishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageDanishScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageGermanScreen, ParsedScreen.TemporaryBasalRatePercentageScreen(percentage = 110))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }

    @Test
    fun checkTemporaryBasalRateDurationParsing() {
        val testScreens = listOf(
            Pair(testFrameTbrDurationEnglishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSpanishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationFrenchScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationItalianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationRussianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationTurkishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationPolishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationCzechScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationHungarianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSlovakScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationRomanianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationCroatianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationDutchScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationGreekScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationFinnishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationNorwegianScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationPortugueseScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSwedishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationDanishScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationGermanScreen, ParsedScreen.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }

    @Test
    fun checkBasalRateTotalScreenParsing() {
        val result0 = parseDisplayFrame(testFrameBasalRateTotalScreen0)
        assertEquals(ParsedScreen.BasalRateTotalScreen(totalNumUnits = 5160), result0)

        val result1 = parseDisplayFrame(testFrameBasalRateTotalScreen1)
        assertEquals(ParsedScreen.BasalRateTotalScreen(totalNumUnits = 56970), result1)
    }

    @Test
    fun checkTimeAndDateSettingsScreenParsing() {
        val resultHour12h = parseDisplayFrame(testTimeAndDateSettingsHour12hFormatScreen)
        assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 20), resultHour12h)

        val resultHour24h = parseDisplayFrame(testTimeAndDateSettingsHour24hFormatScreen)
        assertEquals(ParsedScreen.TimeAndDateSettingsHourScreen(hour = 10), resultHour24h)

        val testScreens = listOf(
            Pair(testTimeAndDateSettingsHourEnglishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 8)),
            Pair(testTimeAndDateSettingsMinuteEnglishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 35)),
            Pair(testTimeAndDateSettingsYearEnglishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthEnglishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayEnglishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSpanishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 8)),
            Pair(testTimeAndDateSettingsMinuteSpanishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 36)),
            Pair(testTimeAndDateSettingsYearSpanishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSpanishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySpanishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourFrenchScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 10)),
            Pair(testTimeAndDateSettingsMinuteFrenchScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 4)),
            Pair(testTimeAndDateSettingsYearFrenchScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthFrenchScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayFrenchScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourItalianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteItalianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 48)),
            Pair(testTimeAndDateSettingsYearItalianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthItalianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayItalianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourRussianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteRussianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 52)),
            Pair(testTimeAndDateSettingsYearRussianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthRussianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayRussianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourTurkishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteTurkishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 53)),
            Pair(testTimeAndDateSettingsYearTurkishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthTurkishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayTurkishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourPolishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinutePolishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearPolishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthPolishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayPolishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourCzechScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteCzechScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearCzechScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthCzechScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayCzechScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourHungarianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteHungarianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearHungarianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthHungarianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayHungarianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSlovakScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteSlovakScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearSlovakScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSlovakScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySlovakScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourRomanianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteRomanianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearRomanianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthRomanianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayRomanianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourCroatianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteCroatianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearCroatianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthCroatianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayCroatianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourDutchScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteDutchScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearDutchScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthDutchScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayDutchScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourGreekScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteGreekScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearGreekScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthGreekScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayGreekScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourFinnishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteFinnishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearFinnishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthFinnishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayFinnishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourNorwegianScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteNorwegianScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearNorwegianScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthNorwegianScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayNorwegianScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourPortugueseScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinutePortugueseScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearPortugueseScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthPortugueseScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayPortugueseScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSwedishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteSwedishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearSwedishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSwedishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySwedishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourDanishScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteDanishScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearDanishScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthDanishScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayDanishScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourGermanScreen, ParsedScreen.TimeAndDateSettingsHourScreen(hour = 10)),
            Pair(testTimeAndDateSettingsMinuteGermanScreen, ParsedScreen.TimeAndDateSettingsMinuteScreen(minute = 22)),
            Pair(testTimeAndDateSettingsYearGermanScreen, ParsedScreen.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthGermanScreen, ParsedScreen.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayGermanScreen, ParsedScreen.TimeAndDateSettingsDayScreen(day = 21))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }
}
