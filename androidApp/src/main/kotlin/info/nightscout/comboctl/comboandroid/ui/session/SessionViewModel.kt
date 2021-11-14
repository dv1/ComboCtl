package info.nightscout.comboctl.comboandroid.ui.session

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame
import info.nightscout.comboctl.base.NUM_DISPLAY_FRAME_PIXELS
import info.nightscout.comboctl.base.PumpIO
import info.nightscout.comboctl.comboandroid.App
import info.nightscout.comboctl.comboandroid.utils.SingleLiveData
import info.nightscout.comboctl.main.NUM_BASAL_PROFILE_FACTORS
import info.nightscout.comboctl.main.Pump
import info.nightscout.comboctl.main.PumpCommandDispatcher
import info.nightscout.comboctl.parser.parsedScreenFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.lang.Math.random
import kotlin.math.roundToInt
import kotlin.random.Random

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

    private val _progressLiveData = MutableLiveData(0)
    val progressLiveData: LiveData<Int> = _progressLiveData

    private val _frameLiveData = MutableLiveData<DisplayFrame>()
    val frameLiveData: LiveData<DisplayFrame> = _frameLiveData

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
                pump?.let(::PumpCommandDispatcher)?.deliverBolus(amount)
                exitCommandMode()
            }
        }
    }

    private suspend fun exitCommandMode() {
        pump?.switchMode(newMode = PumpIO.Mode.REMOTE_TERMINAL)
    }

    fun onHistoryDeltaReadClicked() {
        viewModelScope.launch {
            pump
                ?.let(::PumpCommandDispatcher)
                ?.fetchHistory(setOf(PumpCommandDispatcher.HistoryPart.HISTORY_DELTA))?.historyDeltaEvents?.joinToString("\n")
                ?.let { _historyDeltaLiveData.postValue(it) }
            exitCommandMode()
        }
    }

    fun onReadBasalProfileClicked() {
        viewModelScope.launch {
            pump
                ?.let(::PumpCommandDispatcher)
                ?.getBasalProfile()?.mapIndexed{index, basal -> "$index: $basal"}?.joinToString(", ")
                ?.let { _historyDeltaLiveData.postValue(it) }
            exitCommandMode()
        }
    }

    fun onSetRandomBasalProfileClicked() {
        viewModelScope.launch {

            var current = Random.nextInt(2500, 3000)
            val randomBasalProfile = List(NUM_BASAL_PROFILE_FACTORS) {
                current += Random.nextInt(-100, 200)
                when (current) {
                    in 50..1000 -> ((current + 5) / 10) * 10 // round to the next integer 0.01 IU multiple
                    else -> ((current + 25) / 50) * 50 // round to the next integer 0.05 IU multiple
                }
            }
            pump
                ?.let(::PumpCommandDispatcher)
                ?.setBasalProfile(randomBasalProfile)
            exitCommandMode()
        }
    }

    fun onReadTimeClicked() {
        viewModelScope.launch {
            pump?.let(::PumpCommandDispatcher)
                ?.getDateTime()
                ?.let { _timeLiveData.postValue(it.toString()) }
            exitCommandMode()
        }
    }

    fun onReadQuickInfoClicked() {
        viewModelScope.launch {
            pump?.let(::PumpCommandDispatcher)
                ?.readQuickinfo()
                ?.let { _historyDeltaLiveData.postValue(it.toString()) }
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
                    _progressLiveData.value = (it.overallProgress * 100).roundToInt()
                }.launchIn(viewModelScope)
                pumpLocal.connect(viewModelScope).join()
            } catch (e: Exception) {
                _state.value = State.NO_PUMP_FOUND
            }

            _state.value = State.CONNECTED

            pumpLocal.displayFrameFlow.onEach {
                _frameLiveData.postValue(it)
            }.launchIn(viewModelScope)

            pumpLocal.currentModeFlow.onEach {
                when (it) {
                    PumpIO.Mode.REMOTE_TERMINAL -> Mode.TERMINAL
                    PumpIO.Mode.COMMAND -> Mode.COMMAND
                    null -> Mode.TERMINAL
                }.let { _modeLiveData.value = it }
            }.launchIn(viewModelScope)

            parsedScreenFlow(pumpLocal.displayFrameFlow)
                .onEach { _parsedScreenLiveData.value = it.toString() }
                .launchIn(viewModelScope)
        }
    }

    fun setTbrClicked() {
        viewModelScope.launch {
            pump?.let(::PumpCommandDispatcher)?.setTemporaryBasalRate(180, 15)
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
