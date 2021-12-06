package info.nightscout.comboctl.comboandroid.ui.startup

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import info.nightscout.comboctl.comboandroid.App
import kotlinx.coroutines.runBlocking

class StartupViewModel : ViewModel() {

    private val _statusLiveData = MutableLiveData<Status>(Status.UNDEFINED)
    val statusLiveData: LiveData<Status> = _statusLiveData

    enum class Status {
        UNPAIRED, PAIRED, UNDEFINED
    }

    init {
        determineStatus()

        App.onPumpUnpairedCallback = {
            determineStatus()
        }
    }

    private fun determineStatus() {
        _statusLiveData.postValue(
            if (App.pumpManager.getPairedPumpAddresses().isEmpty())
                Status.UNPAIRED
            else
                Status.PAIRED
        )
    }

    fun onUnpairClicked() {
        runBlocking {
            val bluetoothAddress = App.pumpManager.getPairedPumpAddresses().firstOrNull()

            bluetoothAddress?.let {
                val pump = App.pumpManager.acquirePump(it)
                pump.unpair()
                App.pumpManager.releasePump(it)
            }
        }
    }
}
