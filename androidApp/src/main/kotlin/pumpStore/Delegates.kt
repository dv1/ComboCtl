package info.nightscout.comboctl.comboandroid.persist

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class PreferenceDelegateString(
    private val sharedPreferences: SharedPreferences,
    private val key: String,
    private val defaultValue: String = "",
    private val commit: Boolean = false
) : ReadWriteProperty<Any, String> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sharedPreferences.getString(key, defaultValue)!!

    override fun setValue(thisRef: Any, property: KProperty<*>, value: String) =
        sharedPreferences.edit(commit = commit) { putString(key, value) }
}

class PreferenceDelegateBoolean(
    private val sharedPreferences: SharedPreferences,
    private val key: String,
    private val defaultValue: Boolean = false,
    private val commit: Boolean = false
) : ReadWriteProperty<Any, Boolean> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sharedPreferences.getBoolean(key, defaultValue)

    override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) =
        sharedPreferences.edit(commit = commit) { putBoolean(key, value) }
}

class PreferenceDelegateInt(
    private val sharedPreferences: SharedPreferences,
    private val key: String,
    private val defaultValue: Int = 0,
    private val commit: Boolean = false
) : ReadWriteProperty<Any, Int> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sharedPreferences.getInt(key, defaultValue)

    override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) =
        sharedPreferences.edit(commit = commit) { putInt(key, value) }
}
