package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.base.Pump
import info.nightscout.comboctl.base.TransportLayerIO
import info.nightscout.comboctl.base.toBluetoothAddress
import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonType
import javafx.scene.control.ListView
import javafx.scene.control.TextInputDialog
import javafx.scene.image.ImageView
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainViewController {
    private var mainControl: MainControl? = null
    private var mainScope: CoroutineScope? = null
    private var pumpStateStore: JsonPumpStateStore? = null
    private var listView: ListView<String>? = null

    private val pumpList: ObservableList<String> = FXCollections.observableArrayList()

    private val pumpInstances = mutableMapOf<BluetoothAddress, Pump>()

    fun setup(
        mainControl: MainControl,
        mainScope: CoroutineScope,
        pumpStateStore: JsonPumpStateStore,
        listView: ListView<String>
    ) {
        this.mainControl = mainControl
        this.mainScope = mainScope
        this.pumpStateStore = pumpStateStore
        this.listView = listView

        listView.items = pumpList
        resetPumpList()
    }

    fun startDiscovery() {
        require(mainControl != null)
        require(mainScope != null)
        try {
            mainControl!!.startDiscovery(
                mainScope!!,
                false,
                300,
                { reason -> println("Discovery stopped due to reason: $reason") },
                { newPumpAddress, _ ->
                    withContext(mainScope!!.coroutineContext) {
                        askUserForPIN(newPumpAddress)
                    }
                }
            )
        } catch (e: IllegalStateException) {
            println("Attempted to start discovery even though it is running already")
        } catch (e: BluetoothException) {
            println("Bluetooth interface exception: $e")
        }
    }

    fun stopDiscovery() {
        require(mainControl != null)
        mainControl!!.stopDiscovery()
    }

    fun openPumpView() {
        require(mainControl != null)
        require(listView != null)

        val selectedItems = listView!!.selectionModel.selectedItems

        lateinit var pumpBluetoothAddressStr: String
        try {
            pumpBluetoothAddressStr = selectedItems[0]
        } catch (e: IndexOutOfBoundsException) {
            return
        }

        lateinit var pumpBluetoothAddress: BluetoothAddress
        try {
            pumpBluetoothAddress = pumpBluetoothAddressStr.toBluetoothAddress()
        } catch (e: IllegalArgumentException) {
            return
        }

        if (pumpInstances.contains(pumpBluetoothAddress))
            return

        val pumpViewStage = Stage()

        val loader = FXMLLoader(javaClass.classLoader.getResource("PumpView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        val pumpViewController: PumpViewController = loader.getController()

        val pump = runBlocking {
            mainControl!!.acquirePump(pumpBluetoothAddress)
        }
        pumpInstances[pumpBluetoothAddress] = pump

        pumpViewController.setup(
            pump,
            mainScope!!,
            scene.lookup("#displayFrameView") as ImageView
        )

        pumpViewStage.title = "Pump $pumpBluetoothAddress"
        pumpViewStage.scene = scene
        pumpViewStage.setOnHidden {
            val pumpToRemove = pumpInstances[pumpBluetoothAddress]
            if (pumpToRemove != null) {
                mainScope!!.launch {
                    pumpToRemove.disconnect()
                    pumpInstances.remove(pumpBluetoothAddress)
                    mainControl!!.releasePump(pumpBluetoothAddress)
                }
            }
        }
        pumpViewStage.show()
    }

    fun onNewPairedPump(pumpAddress: BluetoothAddress) {
        resetPumpList()
    }

    fun onPumpUnpaired(pumpAddress: BluetoothAddress) {
        resetPumpList()
    }

    private fun resetPumpList() {
        require(pumpStateStore != null)

        pumpList.clear()
        for (stateBluetoothAddress in pumpStateStore!!.getAvailablePumpStateAddresses())
            pumpList.add(stateBluetoothAddress.toString())
    }

    private suspend fun askUserForPIN(pumpAddress: BluetoothAddress): PairingPIN {
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
        if (result.isPresent)
            return PairingPIN(result.get().map { it - '0' }.toIntArray())
        else
            throw TransportLayerIO.PairingAbortedException()
    }
}
