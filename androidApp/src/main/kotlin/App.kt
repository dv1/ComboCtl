package info.nightscout.comboctl.comboandroid

import android.app.Application
import android.content.Context
import info.nightscout.comboctl.android.AndroidBluetoothInterface
import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.comboandroid.persist.SharedPrefsStoreProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class App : Application() {
    private val bluetoothInterface = AndroidBluetoothInterface(this)
    private val eventHandlingScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        _appContext = this
        super.onCreate()
        bluetoothInterface.setup()
        pumpStateStoreProvider = SharedPrefsStoreProvider(
            appContext.getSharedPreferences("combo_sp", MODE_PRIVATE)
        )
        mainControl = MainControl(bluetoothInterface, pumpStateStoreProvider)
        // Need to start event handling to get notified about discovered pumps etc.
        // This must be called _before_ any device discovery begins.
        mainControl.startEventHandling(eventHandlingScope)
    }

    companion object {
        private var _appContext: Context? = null
        val appContext
            get() = _appContext!!

        lateinit var pumpStateStoreProvider: SharedPrefsStoreProvider
            private set

        lateinit var mainControl: MainControl
            private set
    }
}
