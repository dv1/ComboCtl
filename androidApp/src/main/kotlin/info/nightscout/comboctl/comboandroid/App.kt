package info.nightscout.comboctl.comboandroid

import android.app.Application
import android.content.Context
import info.nightscout.comboctl.android.AndroidBluetoothInterface
import info.nightscout.comboctl.base.MainControl
import info.nightscout.comboctl.comboandroid.persist.SharedPrefsPumpStateStore

class App : Application() {
    private val bluetoothInterface = AndroidBluetoothInterface(this)

    override fun onCreate() {
        _appContext = this
        super.onCreate()
        bluetoothInterface.setup()
        pumpStateStore = SharedPrefsPumpStateStore(
            appContext.getSharedPreferences("combo_sp", MODE_PRIVATE)
        )
        mainControl = MainControl(bluetoothInterface, pumpStateStore)
        mainControl.setup()
    }

    companion object {
        private var _appContext: Context? = null
        val appContext
            get() = _appContext!!

        lateinit var pumpStateStore: SharedPrefsPumpStateStore
            private set

        lateinit var mainControl: MainControl
            private set
    }
}
