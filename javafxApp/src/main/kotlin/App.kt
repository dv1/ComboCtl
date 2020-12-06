package info.nightscout.comboctl.javafxApp

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.linuxBlueZ.BlueZInterface
import javafx.application.Application
import javafx.application.Application.launch
import javafx.beans.binding.Bindings
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonType
import javafx.scene.control.ListView
import javafx.scene.control.TextInputDialog
import javafx.stage.Stage
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
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
    val bluezInterface: BlueZInterface
    val pumpStoreBackend = JsonPumpStateStoreBackend()
    val mainControl: MainControl

    init {
        bluezInterface = BlueZInterface()
        mainControl = MainControl(
            this,
            bluezInterface,
            pumpStoreBackend,
            { newPumpAddress, _, getPINDeferred -> askUserForPIN(newPumpAddress, getPINDeferred) }
        )
        mainControl.startBackgroundEventHandlingLoop()
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
        val loader = FXMLLoader(javaClass.getClassLoader().getResource("MainView.fxml"))
        val root: Parent = loader.load()
        val scene = Scene(root)

        val mainViewController: MainViewController = loader.getController()

        mainViewController.setup(
            mainControl,
            this,
            pumpStoreBackend,
            scene.lookup("#pairedPumpListView") as ListView<String>
        )

        primaryStage.title = "Hello World"
        primaryStage.scene = scene
        primaryStage.show()
    }

    override fun stop() {
        mainControl.stopDiscovery()
        mainControl.stopBackgroundEventHandlingLoop()
        bluezInterface.shutdown()
    }

    private fun askUserForPIN(pumpAddress: BluetoothAddress, getPINDeferred: CompletableDeferred<PairingPIN>) {
        val dialog = TextInputDialog("")
        dialog.setTitle("Pairing PIN required")
        dialog.setHeaderText("Enter the 10-digit pairing PIN as shown on the Combo's LCD (Combo Bluetooth address: $pumpAddress)")

        // Do checks to make sure the user can only enter 10 digits
        // (no non-numeric characters, and not a length other than 10).
        val numericStringRegex = "-?\\d+(\\.\\d+)?".toRegex()
        val okButton = dialog.getDialogPane().lookupButton(ButtonType.OK)
        val inputField = dialog.getEditor()
        val isInvalid = Bindings.createBooleanBinding(
            {
                val str = inputField.getText()
                !((str.length == 10) && str.matches(numericStringRegex))
            },
            inputField.textProperty()
        )
        okButton.disableProperty().bind(isInvalid)

        val result = dialog.showAndWait()
        result.ifPresent {
            enteredPINStr -> getPINDeferred.complete(PairingPIN(enteredPINStr.map { it - '0' }.toIntArray()))
        }
    }
}
