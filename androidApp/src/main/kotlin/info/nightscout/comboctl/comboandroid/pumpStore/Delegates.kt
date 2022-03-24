package info.nightscout.comboctl.comboandroid.persist

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SPDelegateBoolean(
    private val sp: SharedPreferences,
    private val key: String,
    private val defaultValue: Boolean = false,
    private val commit: Boolean = false
) : ReadWriteProperty<Any, Boolean> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sp.getBoolean(key, defaultValue)

    override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) =
        sp.edit(commit = commit) { putBoolean(key, value) }
}

class SPDelegateLong(
    private val sp: SharedPreferences,
    private val key: String,
    private val defaultValue: Long = 0,
    private val commit: Boolean = false
) : ReadWriteProperty<Any, Long> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sp.getLong(key, defaultValue)

    override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) =
        sp.edit(commit = commit) { putLong(key, value) }
}

class SPDelegateInt(
    private val sp: SharedPreferences,
    private val key: String,
    private val defaultValue: Int = 0,
    private val commit: Boolean = false
) : ReadWriteProperty<Any, Int> {
    override fun getValue(thisRef: Any, property: KProperty<*>) =
        sp.getInt(key, defaultValue)

    override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) =
        sp.edit(commit = commit) { putInt(key, value) }
}

class SPDelegateString(
    private val sp: SharedPreferences,
    private val key: String,
    private val defaultValue: String = "",
    private val commit: Boolean = false
) : ReadWriteProperty<Any, String> {
    override fun getValue(thisRef: Any, property: KProperty<*>): String =
        sp.getString(key, defaultValue)!!

    override fun setValue(thisRef: Any, property: KProperty<*>, value: String) =
        sp.edit(commit = commit) { putString(key, value) }
}
