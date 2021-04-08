package info.nightscout.comboctl.comboandroid.ui.session

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.comboandroid.App
import info.nightscout.comboctl.comboandroid.utils.SingleLiveData
import info.nightscout.comboctl.main.Pump
import info.nightscout.comboctl.parser.ParsedScreenStream
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SessionViewModel : ViewModel() {
    private val _screenLiveData = MutableLiveData<Bitmap>()
    val screenLiveData: LiveData<Bitmap> = _screenLiveData

    private val _state = MutableLiveData(State.UNINITIALIZED)
    val state: LiveData<State> = _state

    private val _modeLiveData = MutableLiveData<Mode>(Mode.TERMINAL)
    val modeLiveData: LiveData<Mode> = _modeLiveData

    private val _historyDeltaLiveData = MutableLiveData<String>()
    val historyDeltaLiveData: LiveData<String> = _historyDeltaLiveData

    private val _parsedScreenLiveData = MutableLiveData<String>()
    val parsedScreenLiveData: LiveData<String> = _parsedScreenLiveData

    private val _timeLiveData = SingleLiveData<String>()
    val timeLiveData: LiveData<String> = _timeLiveData

    private val _progressLiveData = MutableLiveData(0)
    val progressLiveData: LiveData<Int> = _progressLiveData

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

    fun onCMBolusClicked(amount: String) {
        amount.toIntOrNull()?.let { amount ->
            viewModelScope.launch {
                pump?.deliverCMDStandardBolus(amount)
            }
        }
    }

    fun onToggleMode() {
        viewModelScope.launch {
            if (modeLiveData.value == Mode.TERMINAL) {
                pump?.switchMode(newMode = PumpIO.Mode.COMMAND)
            } else {
                pump?.switchMode(newMode = PumpIO.Mode.REMOTE_TERMINAL)
            }
        }
    }

    fun onHistoryDeltaReadClicked() {
        viewModelScope.launch {
            pump?.getCMDHistoryDelta()?.joinToString("\n")?.let { _historyDeltaLiveData.postValue(it) }
        }
    }

    fun onReadTimeClicked() {
        viewModelScope.launch {
            pump?.readCMDDateTime()?.let { _timeLiveData.postValue(it.toString()) }
        }
    }

    fun startLifeCycle() {
        if (_state.value != State.UNINITIALIZED) return

        viewModelScope.launch {
            _state.value = State.CONNECTING
            val pumpLocal = App.pumpManager.getPairedPumpAddresses().firstOrNull()?.let {
                App.pumpManager.acquirePump(it)
            }
            pump = pumpLocal
            if (pumpLocal == null) {
                _state.value = State.NO_PUMP_FOUND
                return@launch
            }
            try {
                pumpLocal.connectProgressFlow.onEach {
                    _progressLiveData.value = if (it.numSteps > 0) (it.stepNumber * 100 / it.numSteps).coerceIn(0..100) else 0
                }.launchIn(viewModelScope)
                pumpLocal.connect(viewModelScope).join()
            } catch (e: Exception) {
                _state.value = State.NO_PUMP_FOUND
            }

            _state.value = State.CONNECTED

            pumpLocal.displayFrameFlow.onEach {
                val bitmap = Bitmap.createBitmap(DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(NUM_DISPLAY_FRAME_PIXELS)
                for (i in 0 until NUM_DISPLAY_FRAME_PIXELS) {
                    pixels[i] = if (it[i]) Color.GREEN else Color.DKGRAY
                }
                bitmap.setPixels(pixels, 0, DISPLAY_FRAME_WIDTH, 0, 0, DISPLAY_FRAME_WIDTH, DISPLAY_FRAME_HEIGHT)
                _screenLiveData.postValue(bitmap)
            }.launchIn(viewModelScope)

            pumpLocal.currentModeFlow.onEach {
                when (it) {
                    PumpIO.Mode.REMOTE_TERMINAL -> Mode.TERMINAL
                    PumpIO.Mode.COMMAND -> Mode.COMMAND
                    null -> Mode.TERMINAL
                }.let { _modeLiveData.value = it }
            }.launchIn(viewModelScope)

            val parsedScreenStream = ParsedScreenStream(pumpLocal.displayFrameFlow)

            flow {
                while (true) {
                    emit(parsedScreenStream.getNextParsedScreen())
                }
            }.onEach {
                _parsedScreenLiveData.value = it.toString()
            }.launchIn(viewModelScope)
        }
    }

    enum class State {
        UNINITIALIZED, CONNECTING, CONNECTED, NO_PUMP_FOUND
    }

    enum class Mode {
        COMMAND, TERMINAL
    }
}
