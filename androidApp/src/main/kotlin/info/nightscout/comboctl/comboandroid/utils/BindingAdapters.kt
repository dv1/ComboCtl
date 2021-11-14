package info.nightscout.comboctl.comboandroid.utils

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.databinding.BindingAdapter
import info.nightscout.comboctl.comboandroid.App

object BindingAdapters {
    @BindingAdapter("visibility")
    @JvmStatic
    fun View.bindVisibility(visible: Boolean) {
        this.visibility = when (visible) {
            true -> View.VISIBLE
            false -> View.GONE
        }
    }

    @BindingAdapter("visibility_hide")
    @JvmStatic
    fun View.bindVisibilityHide(visible: Boolean) {
        this.visibility = when (visible) {
            true -> View.VISIBLE
            false -> View.INVISIBLE
        }
    }

    @BindingAdapter("comboDrawable")
    @JvmStatic
    fun AppCompatImageView.bindComboDrawable(bitmap: Bitmap?) {
        val bitmapDrawable = BitmapDrawable(App.appContext.resources, bitmap)
        bitmapDrawable.paint.isFilterBitmap = false
        this.setImageDrawable(bitmapDrawable)
    }
}
