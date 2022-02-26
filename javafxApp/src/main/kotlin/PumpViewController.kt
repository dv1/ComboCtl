package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.main.BasalProfile
import info.nightscout.comboctl.main.NUM_COMBO_BASAL_PROFILE_FACTORS
import info.nightscout.comboctl.main.Pump
import java.io.File
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Pos
import javafx.scene.Scene
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

    private val mutableProgressProperty = SimpleObjectProperty<Double>()
    val progressProperty: ObjectProperty<Double> = mutableProgressProperty

    var dumpRTFrames: Boolean = false

    private var currentJob: Job? = null

    fun setup(pump: Pump, mainScope: CoroutineScope, stage: Stage) {
        this.pump = pump
        this.mainScope = mainScope
        this.stage = stage

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

            pump!!.connect()

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

    fun pressCheckButton() = pressRTButtons(listOf(ApplicationLayer.RTButton.CHECK))
    fun pressMenuButton() = pressRTButtons(listOf(ApplicationLayer.RTButton.MENU))
    fun pressUpButton() = pressRTButtons(listOf(ApplicationLayer.RTButton.UP))
    fun pressDownButton() = pressRTButtons(listOf(ApplicationLayer.RTButton.DOWN))
    fun pressBackButton() = pressRTButtons(listOf(ApplicationLayer.RTButton.MENU, ApplicationLayer.RTButton.UP))

    fun setRandomBasalProfile() {
        val randomBasalProfile = List(NUM_COMBO_BASAL_PROFILE_FACTORS) {
            when (val randomFactor = Random.nextInt(0, 10000)) {
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

    fun setTbr() = launchJob {
        val dialog = TbrDialog(this.stage!!)

        val result = dialog.showAndWait()

        if (result.isPresent) {
            val tbr = result.get()
            println("Setting TBR: $tbr")

            pump!!.setTbrProgressFlow
                .onEach {
                    println("TBR setting progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            pump!!.setTbr(percentage = tbr.first, durationInMinutes = tbr.second, type = Tbr.Type.NORMAL)
        }
    }

    fun fetchTDD() = launchJob {
        val tddHistory = pump!!.fetchTDDHistory()
        Alert(Alert.AlertType.INFORMATION, "TDD history: ${tddHistory.joinToString(" ")}").showAndWait()
    }

    fun readPumpStatus() = launchJob {
        pump!!.updateStatus()
        Alert(Alert.AlertType.INFORMATION, "Pump status: ${pump!!.statusFlow.value!!}").showAndWait()
    }

    fun deliverBolus() = launchJob {
        val dialog = BolusDialog(this.stage!!)

        val result = dialog.showAndWait()

        if (result.isPresent) {
            val bolusAmount = result.get()
            println("Delivering bolus; amount: $bolusAmount")

            pump!!.bolusDeliveryProgressFlow
                .onEach {
                    println("Bolus delivery progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            pump!!.deliverBolus(bolusAmount, Pump.StandardBolusReason.NORMAL)
        }
    }

    private fun askForConfirmation(description: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION)
        alert.title = "Requesting confirmation"
        alert.contentText = "Are you sure you want to $description?"

        val okButton = ButtonType("Yes", ButtonBar.ButtonData.YES)
        val noButton = ButtonType("No", ButtonBar.ButtonData.NO)
        alert.buttonTypes.setAll(okButton, noButton)

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

    private fun setBasalProfile(basalProfileFactors: List<Int>) = launchJob {
        pump!!.setBasalProfileFlow
            .onEach {
                println("Basal progress: $it")
                mutableProgressProperty.setValue(it.overallProgress)
            }
            .launchIn(mainScope!!)

        pump!!.setBasalProfile(BasalProfile(basalProfileFactors))
    }

    private fun pressRTButtons(buttons: List<ApplicationLayer.RTButton>) = launchJob {
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
