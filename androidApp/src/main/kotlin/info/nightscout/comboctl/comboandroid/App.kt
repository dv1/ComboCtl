package info.nightscout.comboctl.comboandroid

import android.app.Application
import android.content.Context
import info.nightscout.comboctl.android.AndroidBluetoothInterface
import info.nightscout.comboctl.android.AndroidLoggerBackend
import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.Logger
import info.nightscout.comboctl.comboandroid.persist.SharedPrefsPumpStateStore
import info.nightscout.comboctl.main.PumpManager

class App : Application() {
    private val bluetoothInterface = AndroidBluetoothInterface(this)

    init {
        Logger.threshold = LogLevel.DEBUG
        Logger.backend = AndroidLoggerBackend()
    }

    override fun onCreate() {
        _appContext = this
        super.onCreate()
        bluetoothInterface.setup()
        pumpStateStore = SharedPrefsPumpStateStore(
            appContext.getSharedPreferences("combo_sp", MODE_PRIVATE)
        )
        pumpManager = PumpManager(bluetoothInterface, pumpStateStore)
        pumpManager.setup { onPumpUnpairedCallback() }
    }

    companion object {
        private var _appContext: Context? = null
        val appContext
            get() = _appContext!!

        lateinit var pumpStateStore: SharedPrefsPumpStateStore
            private set

        lateinit var pumpManager: PumpManager
            private set

        var onPumpUnpairedCallback: () -> Unit = { }
    }
}
