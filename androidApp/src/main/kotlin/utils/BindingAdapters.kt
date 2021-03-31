package info.nightscout.comboctl.comboandroid.utils

import android.graphics.Bitmap
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
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

    @BindingAdapter("comboDrawable")
    @JvmStatic
    fun AppCompatImageView.bindComboDrawable(bitmap: Bitmap?) {
        this.setImageBitmap(bitmap)
    }
}
