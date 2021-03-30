package info.nightscout.comboctl.comboandroid.ui.session

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.*
import info.nightscout.comboctl.comboandroid.App
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SessionViewModel : ViewModel() {

    private val _screenLiveData = MutableLiveData<Bitmap>()
    val screenLiveData: LiveData<Bitmap> = _screenLiveData

    private val _state = MutableLiveData(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private var pump: Pump? = null

    fun onMenuClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(PumpIO.Button.MENU)
        }
    }
    fun onCheckClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(PumpIO.Button.CHECK)
        }
    }
    fun onUpClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(PumpIO.Button.UP)
        }
    }
    fun onDownClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(PumpIO.Button.DOWN)
        }
    }
    fun onBackClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(listOf(PumpIO.Button.UP, PumpIO.Button.MENU))
        }
    }
    fun onUpDownClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(listOf(PumpIO.Button.UP, PumpIO.Button.DOWN))
        }
    }

    fun startLifeCycle() {
        if (_state.value != State.UNINITIALIZED) return

        viewModelScope.launch {
            _state.value = State.CONNECTING
            val pumpLocal = App.mainControl.getPairedPumpAddresses().firstOrNull()?.let {
                App.mainControl.acquirePump(it)
            }
            pump = pumpLocal
            if (pumpLocal == null) {
                _state.value = State.NO_PUMP_FOUND
                return@launch
            }
            try {
                pumpLocal.connect(viewModelScope).join()
            } catch (e: Exception) {
                _state.value = State.NO_PUMP_FOUND
            }

            _state.value = State.CONNECTED

            pumpLocal.displayFrameFlow.collect {
                val bitmap = Bitmap.createBitmap(DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(NUM_DISPLAY_FRAME_PIXELS)
                for (i in 0 until NUM_DISPLAY_FRAME_PIXELS) {
                    pixels[i] = if (it[i]) Color.GREEN else Color.DKGRAY
                }
                bitmap.setPixels(pixels, 0, DISPLAY_FRAME_WIDTH, 0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT)
                _screenLiveData.postValue(bitmap)
            }
        }


    }


    enum class State {
        UNINITIALIZED, CONNECTING, CONNECTED, NO_PUMP_FOUND
    }
}
