package info.nightscout.comboctl.comboandroid

import android.app.Application
import android.content.Context
import info.nightscout.comboctl.android.AndroidBluetoothInterface

class App : Application() {
    val bluetoothInterface = AndroidBluetoothInterface(this)

    override fun onCreate() {
        _appContext = this
        super.onCreate()
        bluetoothInterface.setup()
    }

    companion object {
        private var _appContext: Context? = null
        val appContext
            get() = _appContext!!

        fun getSharedPreferences() = appContext.getSharedPreferences("combo_sp", MODE_PRIVATE)
    }
}
