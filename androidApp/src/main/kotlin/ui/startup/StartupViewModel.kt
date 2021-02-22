package info.nightscout.comboctl.comboandroid.ui.startup

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import info.nightscout.comboctl.comboandroid.persist.SharedPrefsStoreProvider

class StartupViewModel : ViewModel() {

    private val _statusLiveData = MutableLiveData<Status>(Status.UNDEFINED)
    val statusLiveData: LiveData<Status> = _statusLiveData

    private val storeProvider = SharedPrefsStoreProvider.instance

    enum class Status {
        UNPAIRED, PAIRED, UNDEFINED
    }

    init {
        determineStatus()
    }

    private fun determineStatus() {
        _statusLiveData.postValue(
            if (storeProvider.getAvailableStoreAddresses().isEmpty())
                Status.UNPAIRED
            else
                Status.PAIRED
        )
    }

    fun onPairClicked() {
    }

    fun onConnectClicked() {
    }

    fun onUnpairClicked() {
        // TODO: unpair
        _statusLiveData.postValue(Status.UNPAIRED)
        // determineStatus()
    }
}
