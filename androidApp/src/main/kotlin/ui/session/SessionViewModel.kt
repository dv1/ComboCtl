package info.nightscout.comboctl.comboandroid.ui.session

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS
import info.nightscout.comboctl.base.Pump
import info.nightscout.comboctl.comboandroid.App
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SessionViewModel : ViewModel() {

    private val _screenLiveData = MutableLiveData<BitmapDrawable>()
    val screenLiveData: LiveData<BitmapDrawable> = _screenLiveData

    private val _state = MutableLiveData(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private var pump: Pump? = null

    fun onMenuClicked() {}
    fun onCheckClicked() {}
    fun onUpClicked() {}
    fun onDownClicked() {}
    fun onBackClicked() {}
    fun onUpDownClicked() {}

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
            } catch (e :Exception) {
                _state.value = State.NO_PUMP_FOUND
            }

            _state.value = State.CONNECTED

            pumpLocal.displayFrameFlow.collect {
                val bitmap = Bitmap.createBitmap(DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(NUM_DISPLAY_FRAME_PIXELS * 4)
                for (i in 0 until NUM_DISPLAY_FRAME_PIXELS) {
                    val pixel = if (it[i]) 0x00 else 0xff
                    pixels[i * 4 + 0] = 0xff
                    pixels[i * 4 + 1] = pixel
                    pixels[i * 4 + 2] = pixel
                    pixels[i * 4 + 3] = pixel
                }
                bitmap.setPixels(pixels, 0, DISPLAY_FRAME_WIDTH, 0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT)
                _screenLiveData.postValue(BitmapDrawable(App.appContext.resources, bitmap))
            }
        }


    }


    enum class State {
        UNINITIALIZED, CONNECTING, CONNECTED, NO_PUMP_FOUND
    }
}
