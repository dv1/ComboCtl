package info.nightscout.comboctl.comboandroid.ui.pairing

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.PairingPIN
import info.nightscout.comboctl.base.TransportLayerIO
import info.nightscout.comboctl.comboandroid.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PairingViewModel : ViewModel() {
    private val _state = MutableLiveData<State>(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private val _pwValidatedLiveData = MutableLiveData<Boolean>(false)
    val pwValidatedLiveData: LiveData<Boolean> = _pwValidatedLiveData

    private val discoveryScope = CoroutineScope(Dispatchers.Default)

    private var pairingPINDeferred: CompletableDeferred<PairingPIN>? = null

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

        App.mainControl.startDiscovery(
            discoveryScope,
            true,
            300,
            { },
            { _, _ ->
                withContext(viewModelScope.coroutineContext) {
                    _state.value = State.PIN_ENTRY
                    pairingPINDeferred!!.await()
                }
            }
        )
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
        // TODO
    }

    enum class State {
        UNINITIALIZED, PAIRING, PIN_ENTRY, COMPLETE_PAIRING, CANCELLED
    }
}
