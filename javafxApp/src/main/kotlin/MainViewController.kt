package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.base.nullPairingPIN
import info.nightscout.comboctl.main.Pump
import info.nightscout.comboctl.main.PumpManager
import javafx.beans.binding.Bindings
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ProgressBar
import javafx.scene.control.SelectionModel
import javafx.scene.control.TextInputDialog
import javafx.scene.image.ImageView
import javafx.scene.layout.Pane
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainViewController {
    private var pumpManager: PumpManager? = null
    private var mainScope: CoroutineScope? = null
    private var pumpUISelectionModel: SelectionModel<BluetoothAddress>? = null

    private var pairingJob: Job? = null

    private val pumpInstances = mutableMapOf<BluetoothAddress, Pump>()

    // List containing Bluetooth addresses of paired pumps.
    // Used for notifying observing UI controls about added/removed pumps.
    val pumpAddressList: ObservableList<BluetoothAddress> = FXCollections.observableArrayList()

    // Boolean property for notifying observers that pairing is (in)active.
    // Used for enabling/disabling pairing UI controls.
    private val mutableIsPairingProperty = SimpleObjectProperty<Boolean>()
    val isPairingProperty: ObjectProperty<Boolean> = mutableIsPairingProperty

    // Initializes the states in this controller.
    // The pumpUISelectionModel is needed for when the user double-clicks
    // a pump entry in the list to open it. The selection model is needed
    // to determine which item was selected and clicked on.
    fun setup(
        pumpManager: PumpManager,
        mainScope: CoroutineScope,
        pumpUISelectionModel: SelectionModel<BluetoothAddress>
    ) {
        this.pumpManager = pumpManager
        this.mainScope = mainScope
        this.pumpUISelectionModel = pumpUISelectionModel

        mutableIsPairingProperty.value = false

        resetPumpList()
    }

    fun pairWithNewPump() {
        require(pumpManager != null)
        require(mainScope != null)

        if (pairingJob != null)
            return

        mutableIsPairingProperty.value = true

        pairingJob = mainScope!!.launch {
            try {
                pumpManager!!.pairingProgressFlow
                    .onEach { println("Pairing progress: $it") }
                    .launchIn(mainScope!!)

                val result = pumpManager!!.pairWithNewPump(
                    discoveryDuration = 300
                ) { newPumpAddress, _ ->
                    // We must run askUserForPIN() in a JavaFX context.
                    // Otherwise, crashes happen, since JavaFX UI controls
                    // must not be operated in any thread other than the
                    // JavaFX thread.
                    withContext(Dispatchers.Main) {
                        askUserForPIN(newPumpAddress)
                    }
                }

                println("Pairing result: $result")

                if (result is PumpManager.PairingResult.Success)
                    pumpAddressList.add(result.bluetoothAddress)
            } catch (e: IllegalStateException) {
                println("Attempted to start discovery even though it is running already")
            } catch (e: BluetoothException) {
                println("Bluetooth interface exception: $e")
            } finally {
                pairingJob = null
                mutableIsPairingProperty.value = false
            }
        }
    }

    fun stopPairing() {
        pairingJob?.cancel()
        pairingJob = null
    }

    fun openPumpView() {
        require(pumpManager != null)
        require(pumpUISelectionModel != null)

        val pumpBluetoothAddress = pumpUISelectionModel!!.selectedItem ?: return

        if (pumpInstances.contains(pumpBluetoothAddress))
            return

        val pumpViewStage = Stage()

        val loader = FXMLLoader(javaClass.classLoader.getResource("PumpView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        val pumpViewController: PumpViewController = loader.getController()

        val pump = runBlocking {
            pumpManager!!.acquirePump(pumpBluetoothAddress)
        }
        pumpInstances[pumpBluetoothAddress] = pump

        pumpViewController.setup(
            pump,
            mainScope!!,
            pumpViewStage
        )

        val displayFrameView = scene.lookup("#displayFrameView") as ImageView
        displayFrameView.image = pumpViewController.displayFrameImage

        val progressBar = scene.lookup("#progressBar") as? ProgressBar
            ?: TODO("Could not access progress bar")
        progressBar.progressProperty().bind(pumpViewController.progressProperty)

        val dumpRTFramesCheckbox = scene.lookup("#dumpRTFramesCheckbox") as? CheckBox
            ?: TODO("Could not access dump RT frames checkbox")
        dumpRTFramesCheckbox.selectedProperty().addListener { _, _, newValue ->
            println("${if (newValue) "Enabling" else "Disabling"} RT frame dumping")
            pumpViewController.dumpRTFrames = newValue
        }

        // Bind the parent pane's width and height property to the imageview
        // to make sure the imageview always is resized to fill the parent pane.
        val parentPane = displayFrameView.parent as Pane
        displayFrameView.fitWidthProperty().bind(parentPane.widthProperty())
        displayFrameView.fitHeightProperty().bind(parentPane.heightProperty())

        val pumpID = pumpManager!!.getPumpID(pumpBluetoothAddress)

        pumpViewStage.title = "Pump $pumpID ($pumpBluetoothAddress)"
        pumpViewStage.scene = scene
        pumpViewStage.setOnHidden {
            val pumpToRemove = pumpInstances[pumpBluetoothAddress]
            if (pumpToRemove != null) {
                mainScope!!.launch {
                    pumpToRemove.disconnect()
                    pumpInstances.remove(pumpBluetoothAddress)
                    pumpManager!!.releasePump(pumpBluetoothAddress)
                }
            }
        }
        pumpViewStage.show()
    }

    fun onPumpUnpaired(pumpAddress: BluetoothAddress) =
        // Remove the pump from the address list in the mainScope,
        // since changes in the JavaFX UI must not be performed
        // outside of the JavaFX main thread.
        runBlocking(mainScope!!.coroutineContext) {
            pumpAddressList.remove(pumpAddress)
        }

    private fun resetPumpList() {
        require(pumpManager != null)

        pumpAddressList.clear()

        for (bluetoothAddress in pumpManager!!.getPairedPumpAddresses()) {
            pumpAddressList.add(bluetoothAddress)
        }
    }

    private fun askUserForPIN(pumpAddress: BluetoothAddress): PairingPIN {
        val dialog = TextInputDialog("")
        dialog.title = "Pairing PIN required"
        dialog.headerText = "Enter the 10-digit pairing PIN as shown on the Combo's LCD (Combo Bluetooth address: $pumpAddress)"

        // Do checks to make sure the user can only enter 10 digits
        // (no non-numeric characters, and not a length other than 10).
        val numericStringRegex = "-?\\d+(\\.\\d+)?".toRegex()
        val okButton = dialog.dialogPane.lookupButton(ButtonType.OK)
        val inputField = dialog.editor
        val isInvalid = Bindings.createBooleanBinding(
            {
                val str = inputField.text
                !((str.length == 10) && str.matches(numericStringRegex))
            },
            inputField.textProperty()
        )
        okButton.disableProperty().bind(isInvalid)

        val result = dialog.showAndWait()
        return if (result.isPresent) {
            PairingPIN(result.get().map { it - '0' }.toIntArray())
        } else {
            // TODO: Test if this works properly
            pairingJob?.cancel()
            nullPairingPIN()
        }
    }
}
