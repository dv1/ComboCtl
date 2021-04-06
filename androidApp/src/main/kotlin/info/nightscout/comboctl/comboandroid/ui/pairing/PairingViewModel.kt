package info.nightscout.comboctl.comboandroid.ui.pairing

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.base.TransportLayerIO
import info.nightscout.comboctl.comboandroid.App
import info.nightscout.comboctl.main.PumpManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingViewModel : ViewModel() {
    private val _state = MutableLiveData<State>(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private val _pwValidatedLiveData = MutableLiveData<Boolean>(false)
    val pwValidatedLiveData: LiveData<Boolean> = _pwValidatedLiveData

    private var pairingPINDeferred: CompletableDeferred<PairingPIN>? = null
    private var pairingJob: Job? = null

    var password: String = ""
        set(value) {
            field = value
            _pwValidatedLiveData.postValue(value.length == 10)
        }

    fun startLifeCycle() {
        if (_state.value == State.CANCELLED) {
            _state.value = State.UNINITIALIZED
        } else if (_state.value != State.UNINITIALIZED)
            return

        _state.value = State.PAIRING

        pairingPINDeferred = CompletableDeferred<PairingPIN>()

        pairingJob = viewModelScope.launch {
            val result = App.pumpManager.pairWithNewPump(
                discoveryDuration = 300,
                pumpPairingPINCallback = { _, _ ->
                    withContext(viewModelScope.coroutineContext) {
                        _state.value = State.PIN_ENTRY
                        pairingPINDeferred!!.await()
                    }
                }
            )
            if (result !is PumpManager.PairingResult.Success)
                _state.postValue(State.DISCOVERY_STOPPED)
        }
    }

    fun onOkClicked() {
        pairingPINDeferred!!.complete(PairingPIN(password.map { it - '0' }.toIntArray()))
        _state.value = State.COMPLETE_PAIRING
    }

    fun onCancelClicked() {
        pairingPINDeferred!!.completeExceptionally(TransportLayerIO.PairingAbortedException())
        _state.postValue(State.CANCELLED)
    }

    fun stopLifeCycle() {
        pairingPINDeferred!!.completeExceptionally(TransportLayerIO.PairingAbortedException())
        pairingJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopLifeCycle()
    }

    enum class State {
        UNINITIALIZED, PAIRING, PIN_ENTRY, COMPLETE_PAIRING, CANCELLED, DISCOVERY_STOPPED
    }
}
