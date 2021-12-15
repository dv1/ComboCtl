package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DateTime
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.main.NUM_BASAL_PROFILE_FACTORS
import info.nightscout.comboctl.main.Pump
import info.nightscout.comboctl.main.PumpCommandDispatcher
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// Dialog for entering bolus dosage, in 0.1 IU steps.
// If the return value is null, the user pressed the Cancel button.
class BolusDialog(parentStage: Stage) : Dialog<Int?>() {
    private var dosageProperty = SimpleObjectProperty<Int>(0)

    init {
        initOwner(parentStage)

        val rootPane = BorderPane()
        rootPane.setTop(Label("Enter bolus dosage (in 0.1 IU steps)"))

        // Set the dosage spinner to use a range of 1-250, with
        // an initial value of 1 and a step size of 1. These
        // use 0.1 IU as unit, so "250" actually means 25.0 IU.
        val dosageSpinner = Spinner<Int>(1, 250, 1, 1)

        dosageSpinner.getValueFactory().valueProperty().bindBidirectional(dosageProperty)

        val hbox = HBox(dosageSpinner)
        hbox.setAlignment(Pos.CENTER)
        rootPane.setCenter(hbox)

        getDialogPane().setContent(rootPane)
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            when (buttonType) {
                ButtonType.OK -> dosageProperty.getValue()
                else -> null
            }
        }
    }
}

// Dialog for entering TBR details. Return value is a Pair, with the first
// value being the TBR percentage, the second being the TBR duration in minutes.
// If the return value is null, the user pressed the Cancel button.
class TbrDialog(parentStage: Stage) : Dialog<Pair<Int, Int>?>() {
    private var percentageProperty = SimpleObjectProperty<Int>(100)
    private var durationProperty = SimpleObjectProperty<Int>(15)

    init {
        initOwner(parentStage)

        val rootPane = BorderPane()
        rootPane.setTop(Label("Enter TBR"))

        // Set the percentage spinner to use a range of 0-500
        // (500% is the maximum percentage the Combo suports), with
        // an initial value of 100 and a step size of 10.
        val percentageSpinner = Spinner<Int>(0, 500, 100, 10)
        // Set the duration spinner to use a range of 0-1440
        // (1440 minutes or 24 hours is the maximum duration the
        // Combo suports), with an initial value of 15 and
        // a step size of 15.
        val durationSpinner = Spinner<Int>(15, 24 * 60, 15, 15)

        percentageSpinner.getValueFactory().valueProperty().bindBidirectional(percentageProperty)
        durationSpinner.getValueFactory().valueProperty().bindBidirectional(durationProperty)

        val hbox = HBox(percentageSpinner, durationSpinner)
        hbox.setAlignment(Pos.CENTER)
        rootPane.setCenter(hbox)

        getDialogPane().setContent(rootPane)
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            when (buttonType) {
                ButtonType.OK -> Pair(percentageProperty.getValue(), durationProperty.getValue())
                else -> null
            }
        }
    }
}

class PumpViewController {
    private var pump: Pump? = null
    private var mainScope: CoroutineScope? = null
    private var stage: Stage? = null

    private var mutableDisplayFrameImage = WritableImage(DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT)
    private val displayFramePixels = ByteArray(DISPLAY_FRAME_WIDTH * DISPLAY_FRAME_HEIGHT * 3)

    val displayFrameImage: Image = mutableDisplayFrameImage

    // JavaFX properties to notify observers about updated datetime
    // and progress values. The latter is used in several operations
    // that provide a progress reporter.

    private val mutableDateProperty = SimpleObjectProperty<LocalDate>()
    val dateProperty: ObjectProperty<LocalDate> = mutableDateProperty

    private val mutableHourProperty = SimpleObjectProperty<Int>()
    val hourProperty: ObjectProperty<Int> = mutableHourProperty

    private val mutableMinuteProperty = SimpleObjectProperty<Int>()
    val minuteProperty: ObjectProperty<Int> = mutableMinuteProperty

    private val mutableSecondProperty = SimpleObjectProperty<Int>()
    val secondProperty: ObjectProperty<Int> = mutableSecondProperty

    private val mutableProgressProperty = SimpleObjectProperty<Double>()
    val progressProperty: ObjectProperty<Double> = mutableProgressProperty

    var dumpRTFrames: Boolean = false

    private var currentJob: Job? = null

    fun setup(pump: Pump, mainScope: CoroutineScope, stage: Stage) {
        this.pump = pump
        this.mainScope = mainScope
        this.stage = stage

        // Initialize the date and time properties to the current datetime.
        useCurrentDateAndTime()

        // Fill the image view with a checkerboard pattern initially.
        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (((x + y) and 1) != 0) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }
        updateDisplayFrameImage()

        pump.displayFrameFlow
            .onEach { displayFrame -> setDisplayFrame(displayFrame) }
            .launchIn(mainScope)
    }

    fun connectPump() {
        require(pump != null)
        require(mainScope != null)

        mainScope!!.launch {
            // Update the progress property during the connect operation.
            pump!!.connectProgressFlow
                .onEach {
                    println("Connect progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            // Connect to the pump and let it initially run in the COMMAND mode.
            pump!!.connectAsync(mainScope!!, initialMode = PumpIO.Mode.COMMAND).await()

            // We are done with the COMMAND mode, now switch to REMOTE_TERMINAL.
            pump!!.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
        }
    }

    fun unpairPump() {
        require(pump != null)
        require(mainScope != null)

        if (!askForConfirmation("unpair the pump"))
            return

        mainScope!!.launch {
            pump!!.unpair()
            stage!!.close()
        }
    }

    fun cancelCurrentCommand() {
        require(pump != null)
        require(mainScope != null)

        if (currentJob != null) {
            if (!askForConfirmation("cancel the current command"))
                return

            println("Cancelling current command")
            currentJob?.cancel()
            currentJob = null
        }
    }

    fun pressCheckButton() = pressRTButtons(listOf(PumpIO.Button.CHECK))
    fun pressMenuButton() = pressRTButtons(listOf(PumpIO.Button.MENU))
    fun pressUpButton() = pressRTButtons(listOf(PumpIO.Button.UP))
    fun pressDownButton() = pressRTButtons(listOf(PumpIO.Button.DOWN))
    fun pressBackButton() = pressRTButtons(listOf(PumpIO.Button.MENU, PumpIO.Button.UP))

    fun setRandomBasalProfile() {
        val randomBasalProfile = List(NUM_BASAL_PROFILE_FACTORS) {
            val randomFactor = Random.nextInt(0, 10000)
            when (randomFactor) {
                in 50..1000 -> ((randomFactor + 5) / 10) * 10 // round to the next integer 0.01 IU multiple
                else -> ((randomFactor + 25) / 50) * 50 // round to the next integer 0.05 IU multiple
            }
        }
        println("Random basal profile: ${randomBasalProfile.joinToString(" ")}")
        setBasalProfile(randomBasalProfile)
    }

    fun setFixedBasalProfile() {
        val fixedBasalProfile = listOf(
            900, 1000, 2000, 2100, 950, 940, 1250, 5600,
            500, 700, 1850, 3000, 4000, 1100, 450, 480,
            230, 1150, 1300, 4500, 2800, 2250, 1000, 1100
        )
        setBasalProfile(fixedBasalProfile)
    }

    fun getBasalProfile() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)

        pumpCommandDispatcher.basalProfileAccessFlow
            .onEach {
                println("Basal progress: $it")
                mutableProgressProperty.setValue(it.overallProgress)
            }
            .launchIn(mainScope!!)

        val basalProfile = pumpCommandDispatcher.getBasalProfile()
        println("Basal profile: $basalProfile")
        Alert(Alert.AlertType.INFORMATION, "Basal profile: $basalProfile").showAndWait()
    }

    fun setTbr() = launchJob {
        val dialog = TbrDialog(this.stage!!)

        val result = dialog.showAndWait()

        if (result.isPresent()) {
            val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
            val tbr = result.get()
            println("Setting TBR: $tbr")

            pumpCommandDispatcher.tbrProgressFlow
                .onEach {
                    println("TBR setting progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            pumpCommandDispatcher.setTemporaryBasalRate(tbr.first, tbr.second)
        }
    }

    fun getCurrentBasalRateFactor() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
        val basalRateFactor = pumpCommandDispatcher.readCurrentBasalRateFactor()
        println("Current basal rate factor: $basalRateFactor")
        Alert(Alert.AlertType.INFORMATION, "Current basal rate factor: $basalRateFactor").showAndWait()
    }

    fun readPumpStatus() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
        val pumpStatus = pumpCommandDispatcher.readPumpStatus()
        Alert(Alert.AlertType.INFORMATION, "Pump status: $pumpStatus").showAndWait()
    }

    fun deliverBolus() = launchJob {
        val dialog = BolusDialog(this.stage!!)

        val result = dialog.showAndWait()

        if (result.isPresent()) {
            val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
            val bolusAmount = result.get()
            println("Delivering bolus; amount: $bolusAmount")

            pumpCommandDispatcher.bolusDeliveryProgressFlow
                .onEach {
                    println("Bolus delivery progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            pumpCommandDispatcher.deliverBolus(bolusAmount)
        }
    }

    fun fetchHistory() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)

        pumpCommandDispatcher.historyProgressFlow
            .onEach {
                println("Fetch history progress: $it")
                mutableProgressProperty.setValue(it.overallProgress)
            }
            .launchIn(mainScope!!)

        val history = pumpCommandDispatcher.fetchHistory(
            setOf(
                PumpCommandDispatcher.HistoryPart.HISTORY_DELTA,
                PumpCommandDispatcher.HistoryPart.TDD_HISTORY,
                PumpCommandDispatcher.HistoryPart.TBR_HISTORY
            )
        )

        Alert(Alert.AlertType.INFORMATION, "History: $history").showAndWait()
    }

    fun setDatetime() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
        val localDate = dateProperty.getValue()
        val dateTime = DateTime(
            year = localDate.getYear(),
            month = localDate.getMonthValue(),
            day = localDate.getDayOfMonth(),
            hour = hourProperty.getValue(),
            minute = minuteProperty.getValue(),
            second = secondProperty.getValue()
        )

        pumpCommandDispatcher.setDateTimeProgressFlow
            .onEach {
                println("Set datetime progress: $it")
                mutableProgressProperty.setValue(it.overallProgress)
            }
            .launchIn(mainScope!!)

        pumpCommandDispatcher.setDateTime(dateTime)
    }

    fun getDatetime() = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)
        val dateTime = pumpCommandDispatcher.getDateTime()

        mutableDateProperty.setValue(LocalDate.of(dateTime.year, dateTime.month, dateTime.day))
        mutableHourProperty.setValue(dateTime.hour)
        mutableMinuteProperty.setValue(dateTime.minute)
        mutableSecondProperty.setValue(dateTime.second)
    }

    fun useCurrentDateAndTime() {
        val currentLocalDateTime = LocalDateTime.now()
        mutableDateProperty.setValue(currentLocalDateTime.toLocalDate())
        mutableHourProperty.setValue(currentLocalDateTime.getHour())
        mutableMinuteProperty.setValue(currentLocalDateTime.getMinute())
        mutableSecondProperty.setValue(currentLocalDateTime.getSecond())
    }

    private fun askForConfirmation(description: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION)
        alert.setTitle("Requesting confirmation")
        alert.setContentText("Are you sure you want to $description?")

        val okButton = ButtonType("Yes", ButtonBar.ButtonData.YES)
        val noButton = ButtonType("No", ButtonBar.ButtonData.NO)
        alert.getButtonTypes().setAll(okButton, noButton)

        var ok = true
        alert.showAndWait().ifPresent { buttonType ->
            ok = (buttonType.buttonData == ButtonBar.ButtonData.YES)
        }

        return ok
    }

    private fun launchJob(setRTModeAtEnd: Boolean = true, block: suspend () -> Unit) {
        require(pump != null)
        require(mainScope != null)

        if (currentJob != null) {
            Alert(Alert.AlertType.ERROR, "Another command is currently being executed").showAndWait()
            return
        }

        currentJob = mainScope!!.launch {
            try {
                block.invoke()
                if (setRTModeAtEnd)
                    pump!!.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Got exception while running command: $e")
            } finally {
                currentJob = null
            }
        }
    }

    private fun setBasalProfile(basalProfile: List<Int>) = launchJob {
        val pumpCommandDispatcher = PumpCommandDispatcher(pump!!)

        pumpCommandDispatcher.basalProfileAccessFlow
            .onEach {
                println("Basal progress: $it")
                mutableProgressProperty.setValue(it.overallProgress)
            }
            .launchIn(mainScope!!)

        pumpCommandDispatcher.setBasalProfile(basalProfile)
    }

    private fun pressRTButtons(buttons: List<PumpIO.Button>) = launchJob {
        pump!!.sendShortRTButtonPress(buttons)
    }

    private var frameIdx = 0

    // This dumps a DisplayFrame as a Netpbm .PBM image file, which is
    // perfectly suitable for black-and-white frames such as the ones
    // that come from the Combo in the remote terminal mode.
    private fun dumpFrame(displayFrame: DisplayFrame) {
        File("frame${frameIdx.toString().padStart(5, '0')}.pbm").bufferedWriter().use { out ->
            out.write("P1\n")
            out.write("$DISPLAY_FRAME_WIDTH $DISPLAY_FRAME_HEIGHT\n")
            for (y in 0 until DISPLAY_FRAME_HEIGHT) {
                for (x in 0 until DISPLAY_FRAME_WIDTH) {
                    out.write(if (displayFrame.getPixelAt(x, y)) "1" else "0")
                }
                out.write("\n")
            }
        }
        frameIdx += 1
    }

    private fun setDisplayFrame(displayFrame: DisplayFrame) {
        println("New display frame")

        for (y in 0 until DISPLAY_FRAME_HEIGHT) {
            for (x in 0 until DISPLAY_FRAME_WIDTH) {
                val pixel = (if (displayFrame.getPixelAt(x, y)) 0x00 else 0xFF).toByte()
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 0] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 1] = pixel
                displayFramePixels[(x + y * DISPLAY_FRAME_WIDTH) * 3 + 2] = pixel
            }
        }

        if (dumpRTFrames)
            dumpFrame(displayFrame)

        updateDisplayFrameImage()
    }

    private fun updateDisplayFrameImage() {
        val pixelWriter = mutableDisplayFrameImage.pixelWriter
        pixelWriter.setPixels(
            0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT,
            PixelFormat.getByteRgbInstance(),
            displayFramePixels,
            0,
            DISPLAY_FRAME_WIDTH * 3
        )
    }
}
