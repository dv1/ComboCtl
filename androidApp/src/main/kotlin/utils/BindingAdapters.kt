package info.nightscout.comboctl.comboandroid.utils

import android.view.View
import androidx.databinding.BindingAdapter

object BindingAdapters {
    @BindingAdapter("visibility")
    @JvmStatic
    fun View.bindVisibility(visible: Boolean) {
        this.visibility = when (visible) {
            true -> View.VISIBLE
            false -> View.GONE
        }
    }
}
