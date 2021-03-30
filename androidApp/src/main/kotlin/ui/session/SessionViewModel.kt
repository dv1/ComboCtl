package info.nightscout.comboctl.comboandroid.ui.session

import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SessionViewModel : ViewModel() {

    private val _screenLiveData = MutableLiveData<BitmapDrawable>()
    val screenLiveData: LiveData<BitmapDrawable> = _screenLiveData

    private val _state = MutableLiveData(State.CONNECTING)
    val state : LiveData<State> = _state

    fun onMenuClicked() {}
    fun onCheckClicked() {}
    fun onUpClicked() {}
    fun onDownClicked() {}
    fun onBackClicked() {}
    fun onUpDownClicked() {}


    enum class State {
        CONNECTING, CONNECTED
    }
}
