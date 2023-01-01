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
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Spinner
import javafx.scene.image.Image
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.util.StringConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

private enum class BolusType(val type: ApplicationLayer.CMDDeliverBolusType, val label: String) {
    STANDARD(ApplicationLayer.CMDDeliverBolusType.STANDARD_BOLUS, "Standard bolus"),
    EXTENDED(ApplicationLayer.CMDDeliverBolusType.EXTENDED_BOLUS, "Extended bolus"),
    MULTIWAVE(ApplicationLayer.CMDDeliverBolusType.MULTIWAVE_BOLUS, "Multiwave bolus")
}

private data class BolusDosage(
    val totalAmount: Int,
    val immediateAmount: Int,
    val durationInMinutes: Int,
    val type: ApplicationLayer.CMDDeliverBolusType
)

// Dialog for entering bolus dosage, in 0.1 IU steps.
// If the result is null, the user pressed the Cancel button.
private class BolusDialog(parentStage: Stage) : Dialog<BolusDosage?>() {
    init {
        initOwner(parentStage)

        val rootPane = BorderPane()
        rootPane.top = Label("Enter bolus dosage (in 0.1 IU steps)")

        // Set the dosage spinners to use a range of 1-250, with
        // an initial value of 1 and a step size of 1. These
        // use 0.1 IU as unit, so "250" actually means 25.0 IU.
        val totalAmountSpinner = Spinner<Int>(1, 250, 1, 1)
        val immediateAmountSpinner = Spinner<Int>(1, 250, 1, 1)
        val durationSpinner = Spinner<Int>(15, 12 * 60, 15, 15)
        val typeComboBox = ComboBox<BolusType>()

        // We need the converter for the text in the combox' drop-down list view.
        typeComboBox.setCellFactory {
            object : ListCell<BolusType>() {
                override fun updateItem(item: BolusType?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        // We need the converter for the text inside the combobox itself.
        typeComboBox.converter = object : StringConverter<BolusType>() {
            override fun toString(obj: BolusType?): String =
                obj?.label ?: ""

            override fun fromString(str: String?): BolusType {
                return when (str) {
                    BolusType.STANDARD.label -> BolusType.STANDARD
                    BolusType.EXTENDED.label -> BolusType.EXTENDED
                    BolusType.MULTIWAVE.label -> BolusType.MULTIWAVE
                    else -> BolusType.STANDARD
                }
            }
        }
        typeComboBox.value = BolusType.STANDARD
        typeComboBox.items = FXCollections.observableList(BolusType.values().toList())

        val gridPane = GridPane()
        gridPane.add(Label("Total amount"), 0, 0)
        gridPane.add(Label("Immediate amount"), 0, 1)
        gridPane.add(Label("Duration"), 0, 2)
        gridPane.add(Label("Type"), 0, 3)
        gridPane.add(totalAmountSpinner, 1, 0)
        gridPane.add(immediateAmountSpinner, 1, 1)
        gridPane.add(durationSpinner, 1, 2)
        gridPane.add(typeComboBox, 1, 3)

        rootPane.center = gridPane

        dialogPane.content = rootPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            when (buttonType) {
                ButtonType.OK -> BolusDosage(
                    totalAmount = totalAmountSpinner.value,
                    immediateAmount = immediateAmountSpinner.value,
                    durationInMinutes = durationSpinner.value,
                    type = typeComboBox.value.type
                )
                else -> null
            }
        }
    }
}

// Dialog for entering TBR details. Return value is a Pair, with the first
// value being the TBR percentage, the second being the TBR duration in minutes.
// If the return value is null, the user pressed the Cancel button.
class TbrDialog(parentStage: Stage) : Dialog<Pair<Int, Int>?>() {
    init {
        initOwner(parentStage)

        val rootPane = BorderPane()
        rootPane.top = Label("Enter TBR")

        // Set the percentage spinner to use a range of 0-500
        // (500% is the maximum percentage the Combo supports), with
        // an initial value of 100 and a step size of 10.
        val percentageSpinner = Spinner<Int>(0, 500, 100, 10)
        // Set the duration spinner to use a range of 0-1440
        // (1440 minutes or 24 hours is the maximum duration the
        // Combo supports), with an initial value of 15 and
        // a step size of 15.
        val durationSpinner = Spinner<Int>(15, 24 * 60, 15, 15)

        val hbox = HBox(percentageSpinner, durationSpinner)
        hbox.alignment = Pos.CENTER
        rootPane.center = hbox

        dialogPane.content = rootPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            when (buttonType) {
                ButtonType.OK -> Pair(percentageSpinner.value, durationSpinner.value)
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

        pump.parsedDisplayFrameFlow
            .onEach { parsedDisplayFrame -> parsedDisplayFrame?.let { setDisplayFrame(it.displayFrame) } }
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
            val bolusDosage = result.get()
            println("Delivering bolus; dosage: $bolusDosage")

            pump!!.bolusDeliveryProgressFlow
                .onEach {
                    println("Bolus delivery progress: $it")
                    mutableProgressProperty.setValue(it.overallProgress)
                }
                .launchIn(mainScope!!)

            pump!!.deliverBolus(
                bolusDosage.totalAmount,
                bolusDosage.immediateAmount,
                durationInMinutes = bolusDosage.durationInMinutes,
                standardBolusReason = Pump.StandardBolusReason.NORMAL,
                bolusType = bolusDosage.type
            )
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
