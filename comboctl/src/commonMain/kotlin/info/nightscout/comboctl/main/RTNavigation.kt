package info.nightscout.comboctl.main

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.Logger

private val logger = Logger.get("RTNavigation")

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

