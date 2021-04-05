package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.linuxBlueZ.BlueZInterface
import javafx.application.Application
import javafx.application.Application.launch
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.stage.Stage
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch

fun main() {
    launch(App::class.java)
}

// Let our class be its own coroutine scope, as explained here:
// https://domnikl.github.io/2019/02/kotlin-coroutines-and-javafx-threads/
// This allows for integrating coroutines with the JavaFX mainloop,
// avoiding multithreading issues.
class App : Application(), CoroutineScope {
    private val bluezInterface: BlueZInterface
    private val pumpStateStore = JsonPumpStateStore()
    private val mainControl: MainControl
    private var mainViewController: MainViewController? = null

    init {
        val scope = this
        bluezInterface = BlueZInterface()
        mainControl = MainControl(bluezInterface, pumpStateStore)
        mainControl.setup { pumpAddress ->
            if (mainViewController != null)
                mainViewController!!.onPumpUnpaired(pumpAddress)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx

    override fun start(primaryStage: Stage) {
        // This is a debugging coroutine to check that nothing is blocking the
        // entire thread. If the thread is blocked (for example by blocking IO),
        // then this coroutine also won't run, indicated by the counter not
        // being printed.
        (this as CoroutineScope).launch {
            var counter = 1
            while (true) {
                delay(1000)
                println("Counter: $counter")
                counter++
            }
        }

        // getClassLoader() is needed, see: https://stackoverflow.com/a/25217393/560774
        val loader = FXMLLoader(javaClass.classLoader.getResource("MainView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        mainViewController = loader.getController()

        mainViewController!!.setup(
            mainControl,
            this,
            pumpStateStore,
            scene.lookup("#pairedPumpListView") as ListView<String>
        )

        primaryStage.title = "Hello World"
        primaryStage.scene = scene
        primaryStage.show()
    }

    override fun stop() {
        mainViewController = null
        /* mainControl.stopDiscovery()
        runBlocking { mainControl.stopEventHandling() } */
        bluezInterface.shutdown()
    }

    /* private fun askUserForPIN(pumpAddress: BluetoothAddress, getPINDeferred: CompletableDeferred<PairingPIN>) {
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
        result.ifPresent {
            enteredPINStr -> getPINDeferred.complete(PairingPIN(enteredPINStr.map { it - '0' }.toIntArray()))
        }
    } */
}
