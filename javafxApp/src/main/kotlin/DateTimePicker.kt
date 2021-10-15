package info.nightscout.comboctl.javafxApp

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.control.DatePicker
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.util.StringConverter

// DateTimePicker from https://stackoverflow.com/a/33953850/560774

class DateTimePicker(val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : DatePicker() {
    private var dateTimeValue = SimpleObjectProperty<LocalDateTime>(LocalDateTime.now())

    init {
        converter = object : StringConverter<LocalDate>() {
            override fun toString(value: LocalDate?): String {
                return if (dateTimeValue.get() != null) dateTimeValue.get().format(formatter) else ""
            }

            override fun fromString(value: String?): LocalDate? {
                if (value == null) {
                    dateTimeValue.set(null)
                    return null
                }

                dateTimeValue.set(LocalDateTime.parse(value, formatter))
                return dateTimeValue.get().toLocalDate()
            }
        }

        // Synchronize changes to the underlying date value back to the dateTimeValue
        valueProperty().addListener { _, _, new ->
            if (new == null) {
                dateTimeValue.set(null)
            } else {
                if (dateTimeValue.get() == null) {
                    dateTimeValue.set(LocalDateTime.of(new, LocalTime.now()))
                } else {
                    val time = dateTimeValue.get().toLocalTime()
                    dateTimeValue.set(LocalDateTime.of(new, time))
                }
            }
        }

        // Synchronize changes to dateTimeValue back to the underlying date value
        dateTimeValue.addListener { _, _, new ->
            valueProperty().set(new?.toLocalDate())
        }

        // Persist changes onblur
        editor.focusedProperty().addListener { _, _, new ->
            if (!new)
                simulateEnterPressed()
        }
    }

    fun dateTimeValueProperty() = dateTimeValue

    private fun simulateEnterPressed() =
        editor.fireEvent(KeyEvent(editor, editor, KeyEvent.KEY_PRESSED, null, null, KeyCode.ENTER, false, false, false, false))
}
