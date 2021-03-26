package info.nightscout.comboctl.comboandroid.ui.pairing

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingViewModel : ViewModel() {
    private val _state = MutableLiveData<State>(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private val _pwValidatedLiveData = MutableLiveData<Boolean>(false)
    val pwValidatedLiveData: LiveData<Boolean> = _pwValidatedLiveData

    var password: String = ""
        set(value) {
            field = value
            _pwValidatedLiveData.postValue(value.length == 10)
        }

    fun startLifeCycle() {
        if (_state.value == State.CANCELLED) {
            _state.value = State.UNINITIALIZED
        } else if (_state.value != State.UNINITIALIZED) return
        viewModelScope.launch {
            _state.value = State.PAIRING
            withContext(Dispatchers.IO) {
                delay(1000 * 2)
            }
            _state.value = State.PIN_ENTRY
        }
    }

    fun onOkClicked() {
        _state.value = State.COMPLETE_PAIRING
    }

    fun onCancelClicked() {
        _state.postValue(State.CANCELLED)
    }

    fun stopLifeCycle() {
        // TODO
    }

    enum class State {
        UNINITIALIZED, PAIRING, PIN_ENTRY, COMPLETE_PAIRING, CANCELLED
    }
}
