package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.base.PumpStateStoreAccessException
import info.nightscout.comboctl.linuxBlueZ.BlueZInterface
import info.nightscout.comboctl.main.PumpManager
import javafx.application.Application as JavafxApplication
import javafx.application.Application.launch as javafxLaunch
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.stage.Stage
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx

fun main() {
    javafxLaunch(Application::class.java)
}

// Let our class be its own coroutine scope, as explained here:
// https://domnikl.github.io/2019/02/kotlin-coroutines-and-javafx-threads/
// This allows for integrating coroutines with the JavaFX mainloop,
// avoiding multithreading issues.
class Application : JavafxApplication(), CoroutineScope {
    private val bluezInterface: BlueZInterface
    private val pumpStateStore = JsonPumpStateStore()
    private val pumpManager: PumpManager
    private var mainViewController: MainViewController? = null

    init {
        Logger.threshold = LogLevel.DEBUG
        bluezInterface = BlueZInterface()
        pumpManager = PumpManager(bluezInterface, pumpStateStore)
        pumpManager.setup { pumpAddress ->
            mainViewController?.onPumpUnpaired(pumpAddress)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx

    override fun start(primaryStage: Stage) {
        // classLoader is needed, see: https://stackoverflow.com/a/25217393/560774
        val loader = FXMLLoader(javaClass.classLoader.getResource("MainView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        mainViewController = loader.getController()
        if (mainViewController == null)
            throw NullPointerException("Could not access the MainViewController")

        // Need to suppress unchecked cast warnings, since these are produced
        // even though we use a safe cast here. This is a Kotlin limitation,
        // and only happens when safe-casting to a generic type. See:
        // https://stackoverflow.com/a/36570969/560774
        @Suppress("UNCHECKED_CAST")
        val pumpListView = scene.lookup("#pairedPumpListView") as? ListView<BluetoothAddress>
            ?: TODO("Could not access pump list view")

        pumpListView.items = mainViewController!!.pumpAddressList
        // Install a custom ListCell factory to show the pump ID along
        // with the Bluetooth address as the text of each cell.
        pumpListView.setCellFactory {
            object : ListCell<BluetoothAddress>() {
                override fun updateItem(item: BluetoothAddress?, empty: Boolean) {
                    super.updateItem(item, empty)

                    if (empty || item == null) {
                        setText(null)
                    } else {
                        val text = try {
                            val pumpID = pumpManager.getPumpID(item)
                            "$pumpID ($item)"
                        } catch (e: PumpStateStoreAccessException) {
                            null
                        }
                        setText(text)
                    }
                }
            }
        }

        mainViewController!!.setup(pumpManager, this, pumpListView.selectionModel)

        // Bind the pair button's disable property to the isPairingProperty to let
        // the button be automatically disabled when pairing is ongoing. This makes
        // it impossible for the user to again press that button even though pairing
        // is already being done.
        val pairButton = scene.lookup("#pairButton") as? Button
        assert(pairButton != null)
        pairButton!!.disableProperty().bind(mainViewController!!.isPairingProperty)

        primaryStage.title = "ComboCtl JavaFX test application"
        primaryStage.scene = scene
        primaryStage.show()
    }

    override fun stop() {
        mainViewController = null
        bluezInterface.shutdown()
    }
}
