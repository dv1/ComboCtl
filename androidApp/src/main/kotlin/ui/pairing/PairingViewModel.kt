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

    fun startLifeCycle() {
        viewModelScope.launch {
            if (_state.value != State.UNINITIALIZED) return@launch
            _state.value = State.PAIRING
            withContext(Dispatchers.IO) {
                delay(1000 * 3)
            }
            _state.value = State.PIN_ENTRY
        }
    }

    fun stopLifeCycle() {
        // TODO
    }

    enum class State {
        UNINITIALIZED, PAIRING, PIN_ENTRY, PIN_VERIFICATION
    }
}
