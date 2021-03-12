package info.nightscout.comboctl.parser

/**
 * Two-dimensional binary pattern for searching in display frames.
 *
 * This stores pixels of a pattern as a boolean array. These pixels are
 * immutable and used for parsing display frames coming from the Combo.
 *
 * The pattern is not directly constructed out of a boolean array, since
 * that is impractical. Rather, it is constructed out of an array of strings.
 * This array is the "template" for the pattern, and its items are the "rows".
 * A whitespace character is interpreted as the boolean value "false", any
 * other character as "true". This makes it much easier to hardcode a pattern
 * template in a human-readable form. All template rows must have the exact
 * same length (at least 1 character), since patterns are rectangular. The
 * width property is derived from the length of the rows, while the height
 * equals the number of rows.
 *
 * The pixels BooleanArray contains the actual pixels, which are stored in
 * row-major order. That is: Given coordinates x and y (both starting at 0),
 * then the corresponding index in the array is (x + y * width).
 *
 * Pixels whose value is "true" are considered to be "set", while pixels with
 * the value "false" are considered to be "cleared". The number of set pixels
 * is available via the numSetPixels property. This amount is used when
 * resolving pattern match overlaps to decide if one of the overlapping matches
 * "wins" and the other has to be ignored.
 *
 * @param templateRows The string rows that make up the template.
 * @property width Width of the pattern, in pixels.
 * @property height Height of the pattern, in pixels.
 * @property pixels Boolean array housing the pixels.
 * @property numSetPixels Number of pixels in the array that are set
 *           (= whose value is true).
 */
class Pattern(templateRows: Array<String>) {
    val width: Int
    val height: Int
    val pixels: BooleanArray
    val numSetPixels: Int

    init {
        // Sanity checks. The pattern must have at least one row,
        // and rows must not be empty.
        height = templateRows.size
        if (height < 1)
            throw IllegalArgumentException("Could not generate pattern; no template rows available)")

        width = templateRows[0].length
        if (height < 1)
            throw IllegalArgumentException("Could not generate pattern; empty template row detected")

        // Initialize the pixels array and count the number of pixels.
        // The latter will be needed during pattern matching in case
        // matched patterns overlap in the display frame.

        pixels = BooleanArray(width * height) { false }

        var tempNumSetPixels = 0

        templateRows.forEachIndexed { y, row ->
            // Sanity check in case the pattern is malformed and
            // this row is of different length than the others.
            if (row.length != width)
                throw IllegalArgumentException(
                    "Not all rows are of equal length; row #0: $width row #$y: ${row.length}"
                )

            // Fill the pixel array with pixels from the template rows.
            // These contain whitespace for clear pixels and something
            // else (typically a solid block character) for set pixels.
            for (x in 0 until width) {
                val pixel = row[x] != ' '
                pixels[x + y * width] = pixel
                if (pixel)
                    tempNumSetPixels++
            }
        }

        numSetPixels = tempNumSetPixels
    }
}

/**
 * Available symbol glyphs.
 */
enum class Symbol {
    SMALL_CLOCK,
    SMALL_LOCK_CLOSED,
    SMALL_LOCK_OPENED,
    SMALL_CHECK,
    SMALL_LOW_BATTERY,
    SMALL_NO_BATTERY,
    SMALL_WARNING,
    SMALL_DIVIDE,
    SMALL_LOW_INSULIN,
    SMALL_NO_INSULIN,
    SMALL_CALENDAR,
    SMALL_SEPARATOR,
    SMALL_ARROW,
    SMALL_UNITS_PER_HOUR,
    SMALL_BOLUS,
    SMALL_MULTIWAVE,
    SMALL_SPEAKER,
    SMALL_ERROR,
    SMALL_DOT,
    SMALL_UP,
    SMALL_DOWN,
    SMALL_SUM,
    SMALL_BRACKET_RIGHT,
    SMALL_BRACKET_LEFT,
    SMALL_EXTENDED_BOLUS,
    SMALL_PERCENT,
    SMALL_BASAL,
    SMALL_MINUS,
    SMALL_WARRANTY,

    LARGE_DOT,
    LARGE_SEPARATOR,
    LARGE_WARNING,
    LARGE_PERCENT,
    LARGE_UNITS_PER_HOUR,
    LARGE_BASAL_SET,
    LARGE_RESERVOIR_FULL,
    LARGE_RESERVOIR_LOW,
    LARGE_RESERVOIR_EMPTY,
    LARGE_ARROW,
    LARGE_STOP,
    LARGE_CALENDAR,
    LARGE_TBR,
    LARGE_BOLUS,
    LARGE_MULTIWAVE,
    LARGE_MULTIWAVE_BOLUS,
    LARGE_EXTENDED_BOLUS,
    LARGE_BLUETOOTH_SETTINGS,
    LARGE_THERAPY_SETTINGS,
    LARGE_PUMP_SETTINGS,
    LARGE_MENU_SETTINGS,
    LARGE_BASAL,
    LARGE_MY_DATA,
    LARGE_ALARM_SETTINGS,
    LARGE_CHECK,
    LARGE_ERROR
}

/**
 * Class specifying a glyph.
 *
 * A "glyph" is a character, digit, or symbol for which a pattern exists that
 * can be search for in a Combo display frame. Glyphs can be "small" or "large"
 * (this primarily refers to the glyph's height). During pattern matching,
 * if matches overlap, and one match is for a small glyph and the other is for
 * a large glyph, the large one "wins", and the small match is ignored.
 *
 * By using the sealed class and its subclasses, it becomes possible to add
 * context to the hard-coded patterns below. When a pattern matches a subregion
 * in a frame, the corresponding Glyph subclass informs about what the discovered
 * subregion stands for.
 *
 * @property isLarge true if this is a "large" glyph.
 */
sealed class Glyph(val isLarge: Boolean) {
    data class SmallDigit(val digit: Int) : Glyph(false)
    data class SmallCharacter(val character: Char) : Glyph(false)
    data class SmallSymbol(val symbol: info.nightscout.comboctl.parser.Symbol) : Glyph(false)
    data class LargeDigit(val digit: Int) : Glyph(true)
    data class LargeCharacter(val character: Char) : Glyph(true)
    data class LargeSymbol(val symbol: info.nightscout.comboctl.parser.Symbol) : Glyph(true)
}

/**
 * Map of hard-coded patterns, each associated with a glyph specifying what the pattern stands for.
 */
val glyphPatterns = mapOf<Glyph, Pattern>(
    Glyph.LargeSymbol(Symbol.LARGE_DOT) to Pattern(arrayOf(
        "    ",
        "    ",
        "    ",
        "    ",
        "    ",
        "    ",
        "    ",
        "    ",
        "███ ",
        "███ ",
        "███ ",
        "    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_SEPARATOR) to Pattern(arrayOf(
        "    ",
        "    ",
        "    ",
        "███ ",
        "███ ",
        "███ ",
        "    ",
        "    ",
        "███ ",
        "███ ",
        "███ ",
        "    ",
        "    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_WARNING) to Pattern(arrayOf(
        "       ██       ",
        "      ████      ",
        "      █  █      ",
        "     ██  ██     ",
        "     █    █     ",
        "    ██ ██ ██    ",
        "    █  ██  █    ",
        "   ██  ██  ██   ",
        "   █   ██   █   ",
        "  ██   ██   ██  ",
        "  █          █  ",
        " ██    ██    ██ ",
        " █            █ ",
        "████████████████",
        " ███████████████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_PERCENT) to Pattern(arrayOf(
        " ██    ██",
        "████  ██ ",
        "████  ██ ",
        " ██  ██  ",
        "     ██  ",
        "    ██   ",
        "    ██   ",
        "   ██    ",
        "   ██    ",
        "  ██     ",
        "  ██  ██ ",
        " ██  ████",
        " ██  ████",
        "██    ██ "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_UNITS_PER_HOUR) to Pattern(arrayOf(
        "██  ██    ██ ██    ",
        "██  ██    ██ ██    ",
        "██  ██    ██ ██    ",
        "██  ██   ██  ██    ",
        "██  ██   ██  █████ ",
        "██  ██   ██  ███ ██",
        "██  ██  ██   ██  ██",
        "██  ██  ██   ██  ██",
        "██  ██  ██   ██  ██",
        "██  ██ ██    ██  ██",
        "██  ██ ██    ██  ██",
        " ████  ██    ██  ██"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_BASAL_SET) to Pattern(arrayOf(
        "     ███████     ",
        "     ███████     ",
        "     ██ █ ██     ",
        "     ███ ████████",
        "     ██ █ ███████",
        "████████ █ █ █ ██",
        "███████ █ █ █ ███",
        "██ █ █ █ █ █ █ ██",
        "███ █ █ █ █ █ ███",
        "██ █ █ █ █ █ █ ██",
        "███ █ █ █ █ █ ███",
        "██ █ █ █ █ █ █ ██",
        "███ █ █ █ █ █ ███",
        "██ █ █ █ █ █ █ ██"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_FULL) to Pattern(arrayOf(
        "████████████████████    ",
        "████████████████████    ",
        "████████████████████ ███",
        "██████████████████████ █",
        "██████████████████████ █",
        "██████████████████████ █",
        "██████████████████████ █",
        "████████████████████ ███",
        "████████████████████    ",
        "████████████████████    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_LOW) to Pattern(arrayOf(
        "████████████████████    ",
        "█      █  █  █  ████    ",
        "█      █  █  █  ████ ███",
        "█               ██████ █",
        "█               ██████ █",
        "█               ██████ █",
        "█               ██████ █",
        "█               ████ ███",
        "█               ████    ",
        "████████████████████    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_RESERVOIR_LOW) to Pattern(arrayOf(
        "████████████████████    ",
        "█      █  █  █  █  █    ",
        "█      █  █  █  █  █ ███",
        "█                  ███ █",
        "█                    █ █",
        "█                    █ █",
        "█                  ███ █",
        "█                  █ ███",
        "█                  █    ",
        "████████████████████    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_ARROW) to Pattern(arrayOf(
        "        ██      ",
        "        ███     ",
        "        ████    ",
        "        █████   ",
        "        ██████  ",
        "███████████████ ",
        "████████████████",
        "████████████████",
        "███████████████ ",
        "        ██████  ",
        "        █████   ",
        "        ████    ",
        "        ███     ",
        "        ██      "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_EXTENDED_BOLUS) to Pattern(arrayOf(
        "█████████████   ",
        "█████████████   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         ██   ",
        "██         █████",
        "██         █████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_MULTIWAVE) to Pattern(arrayOf(
        "██████          ",
        "██████          ",
        "██  ██          ",
        "██  ██          ",
        "██  ██          ",
        "██  ██          ",
        "██  ████████████",
        "██  ████████████",
        "██            ██",
        "██            ██",
        "██            ██",
        "██            ██",
        "██            ██",
        "██            ██"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_BOLUS) to Pattern(arrayOf(
        "   ██████      ",
        "   ██████      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "   ██  ██      ",
        "█████  ████████",
        "█████  ████████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_MULTIWAVE_BOLUS) to Pattern(arrayOf(
        "██████         ",
        "██████         ",
        "██  ██         ",
        "██  ██         ",
        "██  ██         ",
        "██  ██         ",
        "██  ██ ██ ██ ██",
        "██  ██ ██ ██ ██",
        "██             ",
        "██           ██",
        "██           ██",
        "██             ",
        "██           ██",
        "██           ██"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_STOP) to Pattern(arrayOf(
        "    ████████    ",
        "   ██████████   ",
        "  ████████████  ",
        " ██████████████ ",
        "████████████████",
        "█  █   █   █  ██",
        "█ ███ ██ █ █ █ █",
        "█  ██ ██ █ █  ██",
        "██ ██ ██ █ █ ███",
        "█  ██ ██   █ ███",
        "████████████████",
        " ██████████████ ",
        "  ████████████  ",
        "   ██████████   ",
        "    ████████    "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_CALENDAR) to Pattern(arrayOf(
        "       █████     ",
        "      █     █    ",
        "██████   █   █   ",
        "█   █    █    █  ",
        "█████    █    █  ",
        "█ █ █    ███  █  ",
        "█████            ",
        "█ █ █     ███████",
        "██████    █     █",
        "█ █ █ █   █    ██",
        "█████████ █   █ █",
        "█ █ █ █ █ ██ █  █",
        "█████████ █ █   █",
        " ████████ ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_TBR) to Pattern(arrayOf(
        "     ███████        ██    ██",
        "     ███████       ████  ██ ",
        "     ██   ██       ████  ██ ",
        "     ██   ███████   ██  ██  ",
        "     ██   ███████       ██  ",
        "███████   ██   ██      ██   ",
        "███████   ██   ██      ██   ",
        "██   ██   ██   ██     ██    ",
        "██   ██   ██   ██     ██    ",
        "██   ██   ██   ██    ██     ",
        "██   ██   ██   ██    ██  ██ ",
        "██   ██   ██   ██   ██  ████",
        "██   ██   ██   ██   ██  ████",
        "██   ██   ██   ██  ██    ██ "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_BASAL) to Pattern(arrayOf(
        "     ███████     ",
        "     ███████     ",
        "     ██   ██     ",
        "     ██   ███████",
        "     ██   ███████",
        "███████   ██   ██",
        "███████   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██",
        "██   ██   ██   ██"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_PUMP_SETTINGS) to Pattern(arrayOf(
        "███████████       ",
        "███████████       ",
        "████████████      ",
        "██       ███      ",
        "██       ████     ",
        "█████████████     ",
        "██       ██████   ",
        "███████████████ ██",
        "███████████████  █",
        "                ██",
        "           █   █ █",
        "           ██ █  █",
        "           █ █   █",
        "           ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_PUMP_SETTINGS) to Pattern(arrayOf(
        "███████████       ",
        "███████████       ",
        "████████████      ",
        "██       ███      ",
        "██       ████     ",
        "█████████████     ",
        "██       ██████   ",
        "███████████████ ██",
        "███████████████  █",
        "                ██",
        "           █   █ █",
        "           ██ █  █",
        "           █ █   █",
        "           ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_THERAPY_SETTINGS) to Pattern(arrayOf(
        "   ████        ",
        "   █  █        ",
        "   █  █        ",
        "████  ████     ",
        "█        █     ",
        "█        █     ",
        "████  ████     ",
        "   █  █        ",
        "   █  █ ███████",
        "   ████ █     █",
        "        █    ██",
        "        █   █ █",
        "        ██ █  █",
        "        █ █   █",
        "        ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_BLUETOOTH_SETTINGS) to Pattern(arrayOf(
        "  ██████       ",
        " ███ ████      ",
        " ███  ███      ",
        "████ █ ███     ",
        "████ ██ ██     ",
        "██ █ █ ███     ",
        "███   ████     ",
        "████ ██        ",
        "███   █ ███████",
        "██ █ █  █     █",
        "████ ██ █    ██",
        "████ █  █   █ █",
        " ███  █ ██ █  █",
        " ███ ██ █ █   █",
        "  █████ ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_MENU_SETTINGS) to Pattern(arrayOf(
        "   █████████ ",
        "   █       █ ",
        "   █       █ ",
        "█████████  █ ",
        "█████████  █ ",
        "█████████  █ ",
        "█████████  █ ",
        "█████        ",
        "█████ ███████",
        "█████ █     █",
        "█████ █    ██",
        "█████ █   █ █",
        "█████ ██ █  █",
        "█████ █ █   █",
        "      ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_MY_DATA) to Pattern(arrayOf(
        "       ████   ",
        "      ██████  ",
        "     ████████ ",
        "     ██    ██ ",
        "            █ ",
        "███████     █ ",
        "█     █    █  ",
        "█ ███ █ ███   ",
        "█     █       ",
        "█ ███ █ ████  ",
        "█     █ █████ ",
        "█     █ ██████",
        "███████ ██████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_ALARM_SETTINGS) to Pattern(arrayOf(
        "      █          ",
        "     █ █         ",
        "     ███         ",
        "    █ █ █        ",
        "   █   █ █       ",
        "   █  █ ██       ",
        "   █   █ █       ",
        "   █  █ █        ",
        "  █    █  ███████",
        "  █   █ █ █     █",
        " █     █  █    ██",
        "█████████ █   █ █",
        "     ███  ██ █  █",
        "      █   █ █   █",
        "          ███████"
    )),
    Glyph.LargeSymbol(Symbol.LARGE_CHECK) to Pattern(arrayOf(
        "            ███",
        "           ███ ",
        "          ███  ",
        "         ███   ",
        "███     ███    ",
        " ███   ███     ",
        "  ███ ███      ",
        "   █████       ",
        "    ███        ",
        "     █         "
    )),
    Glyph.LargeSymbol(Symbol.LARGE_ERROR) to Pattern(arrayOf(
        "     █████     ",
        "   █████████   ",
        "  ███████████  ",
        " ███ █████ ███ ",
        " ██   ███   ██ ",
        "████   █   ████",
        "█████     █████",
        "██████   ██████",
        "█████     █████",
        "████   █   ████",
        " ██   ███   ██ ",
        " ███ █████ ███ ",
        "  ███████████  ",
        "   █████████   ",
        "     █████     "
    )),

    Glyph.LargeDigit(0) to Pattern(arrayOf(
        "  ████  ",
        " ██  ██ ",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        " ██  ██ ",
        "  ████  "
    )),
    Glyph.LargeDigit(1) to Pattern(arrayOf(
        "    ██  ",
        "   ███  ",
        "  ████  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  ",
        "    ██  "
    )),
    Glyph.LargeDigit(2) to Pattern(arrayOf(
        "  ████  ",
        " ██  ██ ",
        "██    ██",
        "██    ██",
        "      ██",
        "      ██",
        "     ██ ",
        "    ██  ",
        "   ██   ",
        "  ██    ",
        " ██     ",
        "██      ",
        "██      ",
        "██      ",
        "████████"
    )),
    Glyph.LargeDigit(3) to Pattern(arrayOf(
        " █████  ",
        "██   ██ ",
        "      ██",
        "      ██",
        "      ██",
        "     ██ ",
        "   ███  ",
        "     ██ ",
        "      ██",
        "      ██",
        "      ██",
        "      ██",
        "      ██",
        "██   ██ ",
        " █████  "

    )),
    Glyph.LargeDigit(4) to Pattern(arrayOf(
        "     ██ ",
        "    ███ ",
        "    ███ ",
        "   ████ ",
        "   █ ██ ",
        "  ██ ██ ",
        "  █  ██ ",
        " ██  ██ ",
        "██   ██ ",
        "████████",
        "     ██ ",
        "     ██ ",
        "     ██ ",
        "     ██ ",
        "     ██ "

    )),
    Glyph.LargeDigit(5) to Pattern(arrayOf(
        "███████ ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "██████  ",
        "     ██ ",
        "      ██",
        "      ██",
        "      ██",
        "      ██",
        "      ██",
        "      ██",
        "██   ██ ",
        " █████  "
    )),
    Glyph.LargeDigit(6) to Pattern(arrayOf(
        "    ███ ",
        "   ██   ",
        "  ██    ",
        " ██     ",
        " ██     ",
        "██      ",
        "██████  ",
        "███  ██ ",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        " ██  ██ ",
        "  ████  "
    )),
    Glyph.LargeDigit(7) to Pattern(arrayOf(
        "████████",
        "      ██",
        "      ██",
        "     ██ ",
        "     ██ ",
        "    ██  ",
        "    ██  ",
        "   ██   ",
        "   ██   ",
        "   ██   ",
        "  ██    ",
        "  ██    ",
        "  ██    ",
        "  ██    ",
        "  ██    "
    )),
    Glyph.LargeDigit(8) to Pattern(arrayOf(
        "  ████  ",
        " ██  ██ ",
        "██    ██",
        "██    ██",
        "██    ██",
        " ██  ██ ",
        "  ████  ",
        " ██  ██ ",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        " ██  ██ ",
        "  ████  "
    )),
    Glyph.LargeDigit(9) to Pattern(arrayOf(
        "  ████  ",
        " ██  ██ ",
        "██    ██",
        "██    ██",
        "██    ██",
        "██    ██",
        " ██  ███",
        "  ██████",
        "      ██",
        "     ██ ",
        "     ██ ",
        "    ██  ",
        "    ██  ",
        "   ██   ",
        " ███    "
    )),

    Glyph.LargeCharacter('E') to Pattern(arrayOf(
        "████████",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "███████ ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "██      ",
        "████████"
    )),
    Glyph.LargeCharacter('W') to Pattern(arrayOf(
        "██      ██",
        "██      ██",
        "██      ██",
        "██      ██",
        "██  ██  ██",
        "██  ██  ██",
        "██  ██  ██",
        "██  ██  ██",
        "██  ██  ██",
        "██  ██  ██",
        "██  ██  ██",
        "██ ████ ██",
        "██████████",
        " ███  ███ ",
        "  █    █  "
    )),
    Glyph.LargeCharacter('u') to Pattern(arrayOf(
        "      ",
        "      ",
        "      ",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        "██  ██",
        " ████ "
    )),

    Glyph.SmallSymbol(Symbol.SMALL_CLOCK) to Pattern(arrayOf(
        "  ███  ",
        " █ █ █ ",
        "█  █  █",
        "█  ██ █",
        "█     █",
        " █   █ ",
        "  ███  "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_UNITS_PER_HOUR) to Pattern(arrayOf(
        "█  █    █ █   ",
        "█  █   █  █   ",
        "█  █   █  █ █ ",
        "█  █  █   ██ █",
        "█  █  █   █  █",
        "█  █ █    █  █",
        " ██  █    █  █"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_LOCK_CLOSED) to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        "█████",
        "██ ██",
        "██ ██",
        "█████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_LOCK_OPENED) to Pattern(arrayOf(
        " ███     ",
        "█   █    ",
        "█   █    ",
        "    █████",
        "    ██ ██",
        "    ██ ██",
        "    █████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_CHECK) to Pattern(arrayOf(
        "    █",
        "   ██",
        "█ ██ ",
        "███  ",
        " █   ",
        "     ",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_DIVIDE) to Pattern(arrayOf(
        "     ",
        "    █",
        "   █ ",
        "  █  ",
        " █   ",
        "█    ",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_LOW_BATTERY) to Pattern(arrayOf(
        "██████████ ",
        "█        █ ",
        "███      ██",
        "███       █",
        "███      ██",
        "█        █ ",
        "██████████ "

    )),
    Glyph.SmallSymbol(Symbol.SMALL_NO_BATTERY) to Pattern(arrayOf(
        "██████████ ",
        "█        █ ",
        "█        ██",
        "█         █",
        "█        ██",
        "█        █ ",
        "██████████ "

    )),
    Glyph.SmallSymbol(Symbol.SMALL_LOW_INSULIN) to Pattern(arrayOf(
        "█████████████    ",
        "█  █  █  █ ██ ███",
        "█  █  █  █ ████ █",
        "█          ████ █",
        "█          ████ █",
        "█          ██ ███",
        "█████████████    "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_NO_INSULIN) to Pattern(arrayOf(
        "█████████████    ",
        "█  █  █  █  █ ███",
        "█  █  █  █  ███ █",
        "█             █ █",
        "█           ███ █",
        "█           █ ███",
        "█████████████    "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_CALENDAR) to Pattern(arrayOf(
        "███████",
        "█     █",
        "███████",
        "█ █ █ █",
        "███████",
        "█ █ ███",
        "███████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_DOT) to Pattern(arrayOf(
        "     ",
        "     ",
        "     ",
        "     ",
        "     ",
        " ██  ",
        " ██  "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_SEPARATOR) to Pattern(arrayOf(
        "     ",
        " ██  ",
        " ██  ",
        "     ",
        " ██  ",
        " ██  ",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_ARROW) to Pattern(arrayOf(
        "    █   ",
        "    ██  ",
        "███████ ",
        "████████",
        "███████ ",
        "    ██  ",
        "    █   "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_DOWN) to Pattern(arrayOf(
        "  ███  ",
        "  ███  ",
        "  ███  ",
        "███████",
        " █████ ",
        "  ███  ",
        "   █   "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_UP) to Pattern(arrayOf(
        "   █   ",
        "  ███  ",
        " █████ ",
        "███████",
        "  ███  ",
        "  ███  ",
        "  ███  "

    )),
    Glyph.SmallSymbol(Symbol.SMALL_SUM) to Pattern(arrayOf(
        "██████",
        "█    █",
        " █    ",
        "  █   ",
        " █    ",
        "█    █",
        "██████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_BOLUS) to Pattern(arrayOf(
        " ███   ",
        " █ █   ",
        " █ █   ",
        " █ █   ",
        " █ █   ",
        " █ █   ",
        "██ ████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_MULTIWAVE) to Pattern(arrayOf(
        "███     ",
        "█ █     ",
        "█ █     ",
        "█ ██████",
        "█      █",
        "█      █",
        "█      █"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_EXTENDED_BOLUS) to Pattern(arrayOf(
        "███████ ",
        "█     █ ",
        "█     █ ",
        "█     █ ",
        "█     █ ",
        "█     █ ",
        "█     ██"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_SPEAKER) to Pattern(arrayOf(
        "   ██ ",
        "  █ █ ",
        "██  █ ",
        "██  ██",
        "██  █ ",
        "  █ █ ",
        "   ██ "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_ERROR) to Pattern(arrayOf(
        "  ███  ",
        " █████ ",
        "██ █ ██",
        "███ ███",
        "██ █ ██",
        " █████ ",
        "  ███  "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_WARNING) to Pattern(arrayOf(
        "   █   ",
        "  ███  ",
        "  █ █  ",
        " █ █ █ ",
        " █   █ ",
        "█  █  █",
        "███████"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_BRACKET_LEFT) to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        " █   ",
        " █   ",
        " █   ",
        "  █  ",
        "   █ ",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_BRACKET_RIGHT) to Pattern(arrayOf(
        " █   ",
        "  █  ",
        "   █ ",
        "   █ ",
        "   █ ",
        "  █  ",
        " █   ",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_PERCENT) to Pattern(arrayOf(
        "██   ",
        "██  █",
        "   █ ",
        "  █  ",
        " █   ",
        "█  ██",
        "   ██"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_BASAL) to Pattern(arrayOf(
        "  ████  ",
        "  █  ███",
        "███  █ █",
        "█ █  █ █",
        "█ █  █ █",
        "█ █  █ █",
        "█ █  █ █"
    )),
    Glyph.SmallSymbol(Symbol.SMALL_MINUS) to Pattern(arrayOf(
        "     ",
        "     ",
        "█████",
        "     "
    )),
    Glyph.SmallSymbol(Symbol.SMALL_WARRANTY) to Pattern(arrayOf(
        " ███ █  ",
        "  ██  █ ",
        " █ █   █",
        "█      █",
        "█   █ █ ",
        " █  ██  ",
        "  █ ███ "
    )),

    Glyph.SmallDigit(0) to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█  ██",
        "█ █ █",
        "██  █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallDigit(1) to Pattern(arrayOf(
        "  █  ",
        " ██  ",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  ",
        " ███ "
    )),
    Glyph.SmallDigit(2) to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "    █",
        "   █ ",
        "  █  ",
        " █   ",
        "█████"
    )),
    Glyph.SmallDigit(3) to Pattern(arrayOf(
        "█████",
        "   █ ",
        "  █  ",
        "   █ ",
        "    █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallDigit(4) to Pattern(arrayOf(
        "   █ ",
        "  ██ ",
        " █ █ ",
        "█  █ ",
        "█████",
        "   █ ",
        "   █ "

    )),
    Glyph.SmallDigit(5) to Pattern(arrayOf(
        "█████",
        "█    ",
        "████ ",
        "    █",
        "    █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallDigit(6) to Pattern(arrayOf(
        "  ██ ",
        " █   ",
        "█    ",
        "████ ",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallDigit(7) to Pattern(arrayOf(
        "█████",
        "    █",
        "   █ ",
        "  █  ",
        " █   ",
        " █   ",
        " █   "
    )),
    Glyph.SmallDigit(8) to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        " ███ ",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallDigit(9) to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        " ████",
        "    █",
        "   █ ",
        " ██  "
    )),

    Glyph.SmallCharacter('A') to Pattern(arrayOf(
        "  █  ",
        " █ █ ",
        "█   █",
        "█████",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('a') to Pattern(arrayOf(
        " ███ ",
        "    █",
        " ████",
        "█   █",
        " ████"
    )),
    Glyph.SmallCharacter('Ä') to Pattern(arrayOf(
        "█   █",
        " ███ ",
        "█   █",
        "█   █",
        "█████",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('ă') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        "  █  ",
        " █ █ ",
        "█   █",
        "█████",
        "█   █"
    )),
    Glyph.SmallCharacter('Á') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        " ███ ",
        "█   █",
        "█████",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('á') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        "  █  ",
        " █ █ ",
        "█   █",
        "█████",
        "█   █"
    )),
    Glyph.SmallCharacter('ã') to Pattern(arrayOf(
        " █  █",
        "█ ██ ",
        "  █  ",
        " █ █ ",
        "█   █",
        "█████",
        "█   █"
    )),

    Glyph.SmallCharacter('æ') to Pattern(arrayOf(
        " ████",
        "█ █  ",
        "█ █  ",
        "████ ",
        "█ █  ",
        "█ █  ",
        "█ ███"
    )),

    Glyph.SmallCharacter('B') to Pattern(arrayOf(
        "████ ",
        "█   █",
        "█   █",
        "████ ",
        "█   █",
        "█   █",
        "████ "
    )),
    Glyph.SmallCharacter('C') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█    ",
        "█    ",
        "█    ",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('ć') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        " ████",
        "█    ",
        "█    ",
        "█    ",
        " ████"
    )),
    Glyph.SmallCharacter('č') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        " ████",
        "█    ",
        "█    ",
        "█    ",
        " ████"
    )),
    Glyph.SmallCharacter('Ç') to Pattern(arrayOf(
        " ████",
        "█    ",
        "█    ",
        "█    ",
        " ████",
        "  █  ",
        " ██  "
    )),

    Glyph.SmallCharacter('D') to Pattern(arrayOf(
        "███  ",
        "█  █ ",
        "█   █",
        "█   █",
        "█   █",
        "█  █ ",
        "███  "
    )),
    Glyph.SmallCharacter('E') to Pattern(arrayOf(
        "█████",
        "█    ",
        "█    ",
        "████ ",
        "█    ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('É') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        "█████",
        "█    ",
        "████ ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('Ê') to Pattern(arrayOf(
        "  █  ",
        " █ █ ",
        "█████",
        "█    ",
        "████ ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('ę') to Pattern(arrayOf(
        "█████",
        "█    ",
        "████ ",
        "█    ",
        "█████",
        "  █  ",
        "  ██ "
    )),
    Glyph.SmallCharacter('F') to Pattern(arrayOf(
        "█████",
        "█    ",
        "█    ",
        "████ ",
        "█    ",
        "█    ",
        "█    "
    )),
    Glyph.SmallCharacter('G') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█    ",
        "█ ███",
        "█   █",
        "█   █",
        " ████"
    )),
    Glyph.SmallCharacter('H') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "█████",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('I') to Pattern(arrayOf(
        " ███ ",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  ",
        " ███ "
    )),
    Glyph.SmallCharacter('i') to Pattern(arrayOf(
        " █ ",
        "   ",
        "██ ",
        " █ ",
        " █ ",
        " █ ",
        "███"
    )),
    Glyph.SmallCharacter('í') to Pattern(arrayOf(
        "  █",
        " █ ",
        "███",
        " █ ",
        " █ ",
        " █ ",
        "███"
    )),
    Glyph.SmallCharacter('İ') to Pattern(arrayOf(
        " █ ",
        "   ",
        "███",
        " █ ",
        " █ ",
        " █ ",
        "███"
    )),

    Glyph.SmallCharacter('J') to Pattern(arrayOf(
        "  ███",
        "   █ ",
        "   █ ",
        "   █ ",
        "   █ ",
        "█  █ ",
        " ██  "
    )),
    Glyph.SmallCharacter('K') to Pattern(arrayOf(
        "█   █",
        "█  █ ",
        "█ █  ",
        "██   ",
        "█ █  ",
        "█  █ ",
        "█   █"
    )),
    Glyph.SmallCharacter('L') to Pattern(arrayOf(
        "█    ",
        "█    ",
        "█    ",
        "█    ",
        "█    ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('ł') to Pattern(arrayOf(
        " █   ",
        " █   ",
        " █ █ ",
        " ██  ",
        "██   ",
        " █   ",
        " ████"
    )),
    Glyph.SmallCharacter('M') to Pattern(arrayOf(
        "█   █",
        "██ ██",
        "█ █ █",
        "█ █ █",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('N') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "██  █",
        "█ █ █",
        "█  ██",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('Ñ') to Pattern(arrayOf(
        " █  █",
        "█ ██ ",
        "█   █",
        "██  █",
        "█ █ █",
        "█  ██",
        "█   █"
    )),
    Glyph.SmallCharacter('ň') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        "█   █",
        "██  █",
        "█ █ █",
        "█  ██",
        "█   █"
    )),

    Glyph.SmallCharacter('O') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('Ö') to Pattern(arrayOf(
        "█   █",
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('ó') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('ø') to Pattern(arrayOf(
        "     █",
        "  ███ ",
        " █ █ █",
        " █ █ █",
        " █ █ █",
        "  ███ ",
        " █    "
    )),
    Glyph.SmallCharacter('ő') to Pattern(arrayOf(
        " █  █",
        "█  █ ",
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),

    Glyph.SmallCharacter('P') to Pattern(arrayOf(
        "████ ",
        "█   █",
        "█   █",
        "████ ",
        "█    ",
        "█    ",
        "█    "
    )),
    Glyph.SmallCharacter('Q') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        "█ █ █",
        "█  █ ",
        " ██ █"
    )),
    Glyph.SmallCharacter('R') to Pattern(arrayOf(
        "████ ",
        "█   █",
        "█   █",
        "████ ",
        "█ █  ",
        "█  █ ",
        "█   █"
    )),
    Glyph.SmallCharacter('S') to Pattern(arrayOf(
        " ████",
        "█    ",
        "█    ",
        " ███ ",
        "    █",
        "    █",
        "████ "
    )),
    Glyph.SmallCharacter('ś') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        " ████",
        "█    ",
        " ███ ",
        "    █",
        "████ "
    )),
    Glyph.SmallCharacter('š') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        " ████",
        "█    ",
        " ███ ",
        "    █",
        "████ "
    )),

    Glyph.SmallCharacter('T') to Pattern(arrayOf(
        "█████",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  ",
        "  █  "
    )),
    Glyph.SmallCharacter('U') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('u') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "█  ██",
        " ██ █"
    )),
    Glyph.SmallCharacter('Ü') to Pattern(arrayOf(
        "█   █",
        "     ",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('ú') to Pattern(arrayOf(
        "   █ ",
        "  █  ",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('ů') to Pattern(arrayOf(
        "  █  ",
        " █ █ ",
        "█ █ █",
        "█   █",
        "█   █",
        "█   █",
        " ███ "
    )),
    Glyph.SmallCharacter('V') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " █ █ ",
        "  █  "
    )),
    Glyph.SmallCharacter('W') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "█ █ █",
        "█ █ █",
        "█ █ █",
        " █ █ "
    )),
    Glyph.SmallCharacter('X') to Pattern(arrayOf(
        "█   █",
        "█   █",
        " █ █ ",
        "  █  ",
        " █ █ ",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('Y') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        " █ █ ",
        "  █  ",
        "  █  ",
        "  █  "
    )),
    Glyph.SmallCharacter('ý') to Pattern(arrayOf(
        "   █ ",
        "█ █ █",
        "█   █",
        " █ █ ",
        "  █  ",
        "  █  ",
        "  █  "
    )),
    Glyph.SmallCharacter('Z') to Pattern(arrayOf(
        "█████",
        "    █",
        "   █ ",
        "  █  ",
        " █   ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('ź') to Pattern(arrayOf(
        "  █  ",
        "█████",
        "    █",
        "  ██ ",
        " █   ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('ž') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        "█████",
        "   █ ",
        "  █  ",
        " █   ",
        "█████"
    )),

    Glyph.SmallCharacter('б') to Pattern(arrayOf(
        "█████",
        "█    ",
        "█    ",
        "████ ",
        "█   █",
        "█   █",
        "████ "
    )),
    Glyph.SmallCharacter('ъ') to Pattern(arrayOf(
        "██  ",
        " █  ",
        " █  ",
        " ██ ",
        " █ █",
        " █ █",
        " ██ "
    )),
    Glyph.SmallCharacter('м') to Pattern(arrayOf(
        "█   █",
        "██ ██",
        "█ █ █",
        "█   █",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('л') to Pattern(arrayOf(
        " ████",
        " █  █",
        " █  █",
        " █  █",
        " █  █",
        " █  █",
        "██  █"
    )),
    Glyph.SmallCharacter('ю') to Pattern(arrayOf(
        "█  █ ",
        "█ █ █",
        "█ █ █",
        "███ █",
        "█ █ █",
        "█ █ █",
        "█  █ "
    )),
    Glyph.SmallCharacter('а') to Pattern(arrayOf(
        "  █  ",
        " █ █ ",
        "█   █",
        "█   █",
        "█████",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('п') to Pattern(arrayOf(
        "█████",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('я') to Pattern(arrayOf(
        " ████",
        "█   █",
        "█   █",
        " ████",
        "  █ █",
        " █  █",
        "█   █"
    )),
    Glyph.SmallCharacter('й') to Pattern(arrayOf(
        " █ █ ",
        "  █  ",
        "█   █",
        "█  ██",
        "█ █ █",
        "██  █",
        "█   █"
    )),
    Glyph.SmallCharacter('д') to Pattern(arrayOf(
        "  ██ ",
        " █ █ ",
        " █ █ ",
        "█  █ ",
        "█  █ ",
        "█████",
        "█   █"
    )),
    Glyph.SmallCharacter('ж') to Pattern(arrayOf(
        "█ █ █",
        "█ █ █",
        " ███ ",
        " ███ ",
        "█ █ █",
        "█ █ █",
        "█ █ █"
    )),
    Glyph.SmallCharacter('ы') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        "██  █",
        "█ █ █",
        "█ █ █",
        "██  █"
    )),
    Glyph.SmallCharacter('у') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        " ███ ",
        "  █  ",
        " █   ",
        "█    "
    )),
    Glyph.SmallCharacter('ч') to Pattern(arrayOf(
        " █   █",
        " █   █",
        " █   █",
        " █  ██",
        "  ██ █",
        "     █",
        "     █"
    )),
    Glyph.SmallCharacter('з') to Pattern(arrayOf(
        "  ███ ",
        " █   █",
        "     █",
        "   ██ ",
        "     █",
        " █   █",
        "  ███ "
    )),
    Glyph.SmallCharacter('ц') to Pattern(arrayOf(
        "█  █ ",
        "█  █ ",
        "█  █ ",
        "█  █ ",
        "█  █ ",
        "█████",
        "    █"
    )),
    Glyph.SmallCharacter('и') to Pattern(arrayOf(
        "█   █",
        "█  ██",
        "█ █ █",
        "█ █ █",
        "█ █ █",
        "██  █",
        "█   █"
    )),

    Glyph.SmallCharacter('Σ') to Pattern(arrayOf(
        "█████",
        "█    ",
        " █   ",
        "  █  ",
        " █   ",
        "█    ",
        "█████"
    )),
    Glyph.SmallCharacter('Δ') to Pattern(arrayOf(
        "  █  ",
        "  █  ",
        " █ █ ",
        " █ █ ",
        "█   █",
        "█   █",
        "█████"
    )),
    Glyph.SmallCharacter('Φ') to Pattern(arrayOf(
        "  █  ",
        " ███ ",
        "█ █ █",
        "█ █ █",
        "█ █ █",
        " ███ ",
        "  █  "
    )),
    Glyph.SmallCharacter('Λ') to Pattern(arrayOf(
        "  █  ",
        " █ █ ",
        " █ █ ",
        "█   █",
        "█   █",
        "█   █",
        "█   █"
    )),
    Glyph.SmallCharacter('Ω') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        "█   █",
        "█   █",
        " █ █ ",
        "██ ██"
    )),
    Glyph.SmallCharacter('υ') to Pattern(arrayOf(
        "█   █",
        "█   █",
        "█   █",
        " ███ ",
        "  █  ",
        "  █  ",
        "  █  "
    )),
    Glyph.SmallCharacter('Θ') to Pattern(arrayOf(
        " ███ ",
        "█   █",
        "█   █",
        "█ █ █",
        "█   █",
        "█   █",
        " ███ "
    ))
)
