package info.nightscout.comboctl.comboandroid.ui.session

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.ApplicationLayer
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.base.Tbr
import info.nightscout.comboctl.comboandroid.App
import info.nightscout.comboctl.comboandroid.utils.SingleLiveData
import info.nightscout.comboctl.main.*
import kotlin.random.Random
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class SessionViewModel : ViewModel() {

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

    private val _progressLiveData = MutableLiveData(0f)
    val progressLiveData: LiveData<Float> = _progressLiveData

    private val _frameLiveData = MutableLiveData<DisplayFrame>()
    val frameLiveData: LiveData<DisplayFrame> = _frameLiveData

    private var pump: Pump? = null

    fun onMenuClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(ApplicationLayer.RTButton.MENU)
        }
    }

    fun onCheckClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(ApplicationLayer.RTButton.CHECK)
        }
    }

    fun onUpClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(ApplicationLayer.RTButton.UP)
        }
    }

    fun onDownClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(ApplicationLayer.RTButton.DOWN)
        }
    }

    fun onBackClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(listOf(ApplicationLayer.RTButton.UP, ApplicationLayer.RTButton.MENU))
        }
    }

    fun onUpDownClicked() {
        viewModelScope.launch {
            pump?.sendShortRTButtonPress(listOf(ApplicationLayer.RTButton.UP, ApplicationLayer.RTButton.DOWN))
        }
    }

    fun onCMBolusClicked(amount: String) {
        amount.toIntOrNull()?.let { amount ->
            viewModelScope.launch {
                pump?.deliverBolus(amount, Pump.StandardBolusReason.NORMAL)
                exitCommandMode()
            }
        }
    }

    private suspend fun exitCommandMode() {
        pump?.switchMode(PumpIO.Mode.REMOTE_TERMINAL)
    }

    fun onSetRandomBasalProfileClicked() {
        viewModelScope.launch {

            var current = Random.nextInt(2500, 3000)
            val randomBasalProfile = List(NUM_COMBO_BASAL_PROFILE_FACTORS) {
                current += Random.nextInt(-100, 200)
                when (current) {
                    in 50..1000 -> ((current + 5) / 10) * 10 // round to the next integer 0.01 IU multiple
                    else -> ((current + 25) / 50) * 50 // round to the next integer 0.05 IU multiple
                }
            }
            pump?.setBasalProfile(BasalProfile(randomBasalProfile))
            exitCommandMode()
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
                    _progressLiveData.value = it.overallProgress.toFloat()
                }.launchIn(viewModelScope)
                pumpLocal.connect()
            } catch (e: Exception) {
                _state.value = State.NO_PUMP_FOUND
            }

            _state.value = State.CONNECTED

            pumpLocal.parsedDisplayFrameFlow.onEach { parsedDisplayFrame ->
                parsedDisplayFrame?.let {
                    _frameLiveData.postValue(it.displayFrame)
                    _parsedScreenLiveData.value = it.parsedScreen.toString()
                }
            }.launchIn(viewModelScope)

            pumpLocal.currentModeFlow.onEach {
                when (it) {
                    PumpIO.Mode.REMOTE_TERMINAL -> Mode.TERMINAL
                    PumpIO.Mode.COMMAND -> Mode.COMMAND
                    null -> Mode.TERMINAL
                }.let { _modeLiveData.value = it }
            }.launchIn(viewModelScope)
        }
    }

    fun setTbrClicked() {
        viewModelScope.launch {
            pump?.setTbr(120, 45, type = Tbr.Type.NORMAL, force100Percent = true)
            exitCommandMode()
        }
    }

    enum class State {
        UNINITIALIZED, CONNECTING, CONNECTED, NO_PUMP_FOUND
    }

    enum class Mode {
        COMMAND, TERMINAL
    }
}
