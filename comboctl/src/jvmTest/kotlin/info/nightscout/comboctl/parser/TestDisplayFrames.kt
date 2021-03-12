package info.nightscout.comboctl.parser

import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS

private fun makeDisplayFrame(templateRows: Array<String>): DisplayFrame {
    assert(templateRows.size == DISPLAY_FRAME_HEIGHT)
    assert(templateRows[0].length == DISPLAY_FRAME_WIDTH)

    val pixels = BooleanArray(NUM_DISPLAY_FRAME_PIXELS) { false }

    for (y in 0 until DISPLAY_FRAME_HEIGHT) {
        assert(templateRows[y].length == DISPLAY_FRAME_WIDTH)
        for (x in 0 until DISPLAY_FRAME_WIDTH)
            pixels[x + y * DISPLAY_FRAME_WIDTH] = (templateRows[y][x] != ' ')
    }

    return DisplayFrame(pixels)
}

val mainMenuDisplayFrame = makeDisplayFrame(arrayOf(
    "  ███     ███   ███        █████  ███                                                           ",
    " █ █ █   █   █ █   █       █     █   █                                                          ",
    "█  █  █      █     █       ████  █   █                                                          ",
    "█  ██ █     █     █            █  ████                                                          ",
    "█     █    █     █             █     █                                                          ",
    " █   █    █     █          █   █    █                                                           ",
    "  ███    █████ █████        ███   ██                                                            ",
    "                                                                                                ",
    "                                  ████              ████        ████                            ",
    "     ███████                     ██  ██            ██  ██      ██  ██                           ",
    "     ███████                    ██    ██          ██    ██    ██    ██                          ",
    "     ██   ██                    ██    ██          ██    ██    ██    ██     ██  ██    ██ ██      ",
    "     ██   ███████               ██    ██          ██    ██    ██    ██     ██  ██    ██ ██      ",
    "     ██   ███████               ██    ██           ██  ██      ██  ██      ██  ██    ██ ██      ",
    "███████   ██   ██               ██    ██            ████        ████       ██  ██   ██  ██      ",
    "███████   ██   ██               ██    ██           ██  ██      ██  ██      ██  ██   ██  █████   ",
    "██   ██   ██   ██   █           ██    ██          ██    ██    ██    ██     ██  ██   ██  ███ ██  ",
    "██   ██   ██   ██  ██           ██    ██          ██    ██    ██    ██     ██  ██  ██   ██  ██  ",
    "██   ██   ██   ██   █           ██    ██          ██    ██    ██    ██     ██  ██  ██   ██  ██  ",
    "██   ██   ██   ██   █           ██    ██          ██    ██    ██    ██     ██  ██  ██   ██  ██  ",
    "██   ██   ██   ██   █           ██    ██   ███    ██    ██    ██    ██     ██  ██ ██    ██  ██  ",
    "██   ██   ██   ██   █            ██  ██    ███     ██  ██      ██  ██      ██  ██ ██    ██  ██  ",
    "██   ██   ██   ██  ███            ████     ███      ████        ████        ████  ██    ██  ██  ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                ",
    "                                                                                                "
))

val standardBolusDisplayFrame = makeDisplayFrame(arrayOf(
    "                         ████ █████   █   █   █ ███     █   ████  ███                           ",
    "                        █       █    █ █  █   █ █  █   █ █  █   █ █  █                          ",
    "                        █       █   █   █ ██  █ █   █ █   █ █   █ █   █                         ",
    "                         ███    █   █████ █ █ █ █   █ █████ ████  █   █                         ",
    "                            █   █   █   █ █  ██ █   █ █   █ █ █   █   █                         ",
    "                            █   █   █   █ █   █ █  █  █   █ █  █  █  █                          ",
    "                        ████    █   █   █ █   █ ███   █   █ █   █ ███                           ",
    "                                                                                                ",
    "                                 ████   ███  █     █   █  ████                                  ",
    "                                 █   █ █   █ █     █   █ █                                      ",
    "                                 █   █ █   █ █     █   █ █                                      ",
    "                                 ████  █   █ █     █   █  ███                                   ",
    "                                 █   █ █   █ █     █   █     █                                  ",
    "                                 █   █ █   █ █     █   █     █                                  ",
    "                                 ████   ███  █████  ███  ████                                   ",
    "                                                                                                ",
    "                                                                                                ",
    "                                           ██████                                               ",
    "                                           ██████                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                           ██  ██                                               ",
    "                                        █████  ████████                                         ",
    "                                        █████  ████████                                         "
))
