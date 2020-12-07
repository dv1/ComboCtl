package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.BluetoothException
import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.base.Pump
import info.nightscout.comboctl.base.toBluetoothAddress
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.scene.image.ImageView
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainViewController {
    private var mainControl: MainControl? = null
    private var mainScope: CoroutineScope? = null
    private var pumpStoreBackend: JsonPumpStateStoreBackend? = null
    private var listView: ListView<String>? = null

    private val pumpList: ObservableList<String> = FXCollections.observableArrayList<String>()

    private val pumpInstances = mutableMapOf<BluetoothAddress, Pump>()

    fun setup(
        mainControl: MainControl,
        mainScope: CoroutineScope,
        pumpStoreBackend: JsonPumpStateStoreBackend,
        listView: ListView<String>
    ) {
        this.mainControl = mainControl
        this.mainScope = mainScope
        this.pumpStoreBackend = pumpStoreBackend
        this.listView = listView

        listView.setItems(pumpList)
        resetPumpList()
    }

    fun startDiscovery() {
        require(mainControl != null)
        require(mainScope != null)
        try {
            mainControl!!.startDiscovery()
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

        val selectedItems = listView!!.getSelectionModel().getSelectedItems()

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

        val loader = FXMLLoader(javaClass.getClassLoader().getResource("PumpView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        val pumpViewController: PumpViewController = loader.getController()

        val pump = mainControl!!.getPump(
            pumpBluetoothAddress,
            { displayFrame -> pumpViewController.setDisplayFrame(displayFrame) }
        )
        pumpInstances[pumpBluetoothAddress] = pump

        pumpViewController.setup(
            pump,
            mainScope!!,
            scene.lookup("#displayFrameView") as ImageView
        )

        pumpViewStage.title = "Pump $pumpBluetoothAddress"
        pumpViewStage.scene = scene
        pumpViewStage.setOnHidden {
            var pumpToRemove = pumpInstances[pumpBluetoothAddress]
            if (pumpToRemove != null) {
                mainScope!!.launch {
                    pumpToRemove.disconnect()
                    pumpInstances.remove(pumpBluetoothAddress)
                }
            }
        }
        pumpViewStage.show()
    }

    private fun resetPumpList() {
        require(pumpStoreBackend != null)

        pumpList.clear()
        for (storeBluetoothAddress in pumpStoreBackend!!.getAvailableStoreAddresses())
            pumpList.add(storeBluetoothAddress.toString())
    }
}
