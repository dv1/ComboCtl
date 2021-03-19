package info.nightscout.comboctl.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class DisplayFrameParsingTest {
    @Test
    fun checkFrameBasalRateFactorSettingParsing() {
        val result0 = parseDisplayFrame(testFrameBasalRateFactorSettingScreen0)
        assertEquals(
            ParseResult.BasalRateFactorSettingScreen(
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
            ParseResult.BasalRateFactorSettingScreen(
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
            ParseResult.BasalRateFactorSettingScreen(
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
            ParseResult.BasalRateFactorSettingScreen(
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
            ParseResult.BasalRateFactorSettingScreen(
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
            ParseResult.NormalMainScreen(
                currentTimeHours = 10,
                currentTimeMinutes = 20,
                activeBasalRateNumber = 1,
                currentBasalRateFactor = 200
            ),
            resultWithSeparator
        )

        val resultWithoutSeparator = parseDisplayFrame(testFrameMainScreenWithoutTimeSeparator)
        assertEquals(
            ParseResult.NormalMainScreen(
                currentTimeHours = 10,
                currentTimeMinutes = 20,
                activeBasalRateNumber = 1,
                currentBasalRateFactor = 200
            ),
            resultWithoutSeparator
        )
    }

    @Test
    fun checkMainScreenStoppedParsing() {
        val resultWithSeparator = parseDisplayFrame(testFrameMainScreenStoppedWithTimeSeparator)
        assertEquals(
            ParseResult.StoppedMainScreen(
                currentTimeHours = 10,
                currentTimeMinutes = 20
            ),
            resultWithSeparator
        )

        val resultWithoutSeparator = parseDisplayFrame(testFrameMainScreenStoppedWithoutTimeSeparator)
        assertEquals(
            ParseResult.StoppedMainScreen(
                currentTimeHours = 10,
                currentTimeMinutes = 20
            ),
            resultWithoutSeparator
        )
    }

    @Test
    fun checkMainScreenWithTbrInfoParsing() {
        val result = parseDisplayFrame(testFrameMainScreenWithTbrInfo)
        assertEquals(
            ParseResult.TbrMainScreen(
                currentTimeHours = 10,
                currentTimeMinutes = 21,
                remainingTbrDurationHours = 0,
                remainingTbrDurationMinutes = 30,
                tbrPercentage = 110,
                activeBasalRateNumber = 1,
                currentBasalRateFactor = 220
            ),
            result
        )
    }

    @Test
    fun checkStandardBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameStandardBolusMenuScreen)
        assert(result is ParseResult.StandardBolusMenuScreen)
    }

    @Test
    fun checkExtendedBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameExtendedBolusMenuScreen)
        assert(result is ParseResult.ExtendedBolusMenuScreen)
    }

    @Test
    fun checkMultiwaveBolusMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMultiwaveBolusMenuScreen)
        assert(result is ParseResult.MultiwaveBolusMenuScreen)
    }

    @Test
    fun checkBluetoothSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameBluetoothSettingsMenuScreen)
        assert(result is ParseResult.BluetoothSettingsMenuScreen)
    }

    @Test
    fun checkMenuSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMenuSettingsMenuScreen)
        assert(result is ParseResult.MenuSettingsMenuScreen)
    }

    @Test
    fun checkMyDataMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameMyDataMenuScreen)
        assert(result is ParseResult.MyDataMenuScreen)
    }

    @Test
    fun checkBasalRateProfileSelectionMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameBasalRateProfileSelectionMenuScreen)
        assert(result is ParseResult.BasalRateProfileSelectionMenuScreen)
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
            assertEquals(ParseResult.BasalRateProgrammingMenuScreen(i), result)
        }
    }

    @Test
    fun checkPumpSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFramePumpSettingsMenuScreen)
        assert(result is ParseResult.PumpSettingsMenuScreen)
    }

    @Test
    fun checkReminderSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameReminderSettingsMenuScreen)
        assert(result is ParseResult.ReminderSettingsMenuScreen)
    }

    @Test
    fun checkTimeAndDateSettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTimeAndDateSettingsMenuScreen)
        assert(result is ParseResult.TimeAndDateSettingsMenuScreen)
    }

    @Test
    fun checkStopPumpMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameStopPumpMenuScreen)
        assert(result is ParseResult.StopPumpMenuScreen)
    }

    @Test
    fun checkTemporaryBasalRateMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTemporaryBasalRateMenuScreen)
        assert(result is ParseResult.TemporaryBasalRateMenuScreen)
    }

    @Test
    fun checkTherapySettingsMenuScreenParsing() {
        val result = parseDisplayFrame(testFrameTherapySettingsMenuScreen)
        assert(result is ParseResult.TherapySettingsMenuScreen)
    }

    @Test
    fun checkQuickinfoMainScreenParsing() {
        val result = parseDisplayFrame(testFrameQuickinfoMainScreen)
        assertEquals(ParseResult.QuickinfoMainScreen(availableUnits = 213, reservoirState = ReservoirState.FULL), result)
    }

    @Test
    fun checkWarningScreenParsing() {
        val resultTbr = parseDisplayFrame(testFrameW6CancelTbrWarningScreen)
        assertEquals(ParseResult.WarningScreen(warningNumber = 6), resultTbr)

        // The main contents of the warning screen blink. During
        // the phase where the main contents aren't visible, we
        // cannot parse anything useful, so we do not expect a
        // result during that phase.
        val resultBolus0 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen0)
        assertEquals(null, resultBolus0)

        val resultBolus1 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen1)
        assertEquals(ParseResult.WarningScreen(warningNumber = 8), resultBolus1)

        // Same as above with regards to the blinking.
        val resultBolus2 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen2)
        assertEquals(null, resultBolus2)

        val resultBolus3 = parseDisplayFrame(testFrameW8CancelBolusWarningScreen3)
        assertEquals(ParseResult.WarningScreen(warningNumber = 8), resultBolus3)
    }

    @Test
    fun checkTemporaryBasalRatePercentageParsing() {
        val result100 = parseDisplayFrame(testFrameTemporaryBasalRatePercentage100Screen)
        assertEquals(ParseResult.TemporaryBasalRatePercentageScreen(percentage = 100), result100)

        val result110 = parseDisplayFrame(testFrameTemporaryBasalRatePercentage110Screen)
        assertEquals(ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110), result110)

        val testScreens = listOf(
            Pair(testFrameTbrPercentageEnglishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSpanishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageFrenchScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageItalianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageRussianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageTurkishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentagePolishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 100)),
            Pair(testFrameTbrPercentageCzechScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageHungarianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSlovakScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageRomanianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageCroatianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageDutchScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageGreekScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageFinnishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageNorwegianScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentagePortugueseScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageSwedishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageDanishScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110)),
            Pair(testFrameTbrPercentageGermanScreen, ParseResult.TemporaryBasalRatePercentageScreen(percentage = 110))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }

    @Test
    fun checkTemporaryBasalRateDurationParsing() {
        val testScreens = listOf(
            Pair(testFrameTbrDurationEnglishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSpanishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationFrenchScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationItalianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationRussianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationTurkishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationPolishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationCzechScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationHungarianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSlovakScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationRomanianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationCroatianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationDutchScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationGreekScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationFinnishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationNorwegianScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationPortugueseScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationSwedishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationDanishScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30)),
            Pair(testFrameTbrDurationGermanScreen, ParseResult.TemporaryBasalRateDurationScreen(hours = 0, minutes = 30))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }

    @Test
    fun checkBasalRateTotalScreenParsing() {
        val result0 = parseDisplayFrame(testFrameBasalRateTotalScreen0)
        assertEquals(ParseResult.BasalRateTotalScreen(totalNumUnits = 5160), result0)

        val result1 = parseDisplayFrame(testFrameBasalRateTotalScreen1)
        assertEquals(ParseResult.BasalRateTotalScreen(totalNumUnits = 56970), result1)
    }

    @Test
    fun checkTimeAndDateSettingsScreenParsing() {
        val resultHour12h = parseDisplayFrame(testTimeAndDateSettingsHour12hFormatScreen)
        assertEquals(ParseResult.TimeAndDateSettingsHourScreen(hour = 20), resultHour12h)

        val resultHour24h = parseDisplayFrame(testTimeAndDateSettingsHour24hFormatScreen)
        assertEquals(ParseResult.TimeAndDateSettingsHourScreen(hour = 10), resultHour24h)

        val testScreens = listOf(
            Pair(testTimeAndDateSettingsHourEnglishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 8)),
            Pair(testTimeAndDateSettingsMinuteEnglishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 35)),
            Pair(testTimeAndDateSettingsYearEnglishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthEnglishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayEnglishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSpanishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 8)),
            Pair(testTimeAndDateSettingsMinuteSpanishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 36)),
            Pair(testTimeAndDateSettingsYearSpanishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSpanishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySpanishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourFrenchScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 10)),
            Pair(testTimeAndDateSettingsMinuteFrenchScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 4)),
            Pair(testTimeAndDateSettingsYearFrenchScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthFrenchScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayFrenchScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourItalianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteItalianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 48)),
            Pair(testTimeAndDateSettingsYearItalianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthItalianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayItalianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourRussianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteRussianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 52)),
            Pair(testTimeAndDateSettingsYearRussianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthRussianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayRussianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourTurkishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 13)),
            Pair(testTimeAndDateSettingsMinuteTurkishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 53)),
            Pair(testTimeAndDateSettingsYearTurkishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthTurkishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayTurkishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourPolishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinutePolishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearPolishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthPolishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayPolishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourCzechScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteCzechScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearCzechScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthCzechScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayCzechScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourHungarianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteHungarianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearHungarianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthHungarianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayHungarianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSlovakScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteSlovakScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearSlovakScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSlovakScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySlovakScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourRomanianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteRomanianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearRomanianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthRomanianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayRomanianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourCroatianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteCroatianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearCroatianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthCroatianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayCroatianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourDutchScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteDutchScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearDutchScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthDutchScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayDutchScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourGreekScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteGreekScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearGreekScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthGreekScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayGreekScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourFinnishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteFinnishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearFinnishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthFinnishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayFinnishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourNorwegianScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteNorwegianScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearNorwegianScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthNorwegianScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayNorwegianScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourPortugueseScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinutePortugueseScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearPortugueseScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthPortugueseScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayPortugueseScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourSwedishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteSwedishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearSwedishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthSwedishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDaySwedishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourDanishScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 14)),
            Pair(testTimeAndDateSettingsMinuteDanishScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 34)),
            Pair(testTimeAndDateSettingsYearDanishScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthDanishScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayDanishScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 27)),

            Pair(testTimeAndDateSettingsHourGermanScreen, ParseResult.TimeAndDateSettingsHourScreen(hour = 10)),
            Pair(testTimeAndDateSettingsMinuteGermanScreen, ParseResult.TimeAndDateSettingsMinuteScreen(minute = 22)),
            Pair(testTimeAndDateSettingsYearGermanScreen, ParseResult.TimeAndDateSettingsYearScreen(year = 2015)),
            Pair(testTimeAndDateSettingsMonthGermanScreen, ParseResult.TimeAndDateSettingsMonthScreen(month = 4)),
            Pair(testTimeAndDateSettingsDayGermanScreen, ParseResult.TimeAndDateSettingsDayScreen(day = 21))
        )

        for ((testScreen, expectedResult) in testScreens) {
            val result = parseDisplayFrame(testScreen)
            assertEquals(expectedResult, result)
        }
    }
}
