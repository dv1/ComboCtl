// Need to add this to make sure there is no class name collision between
// the class Kotlin generates out of this file and the class generated
// out of commonMain's Utility.kt.
@file:JvmName("UtilityAndroid")

package info.nightscout.comboctl.base

import android.os.SystemClock

// Returns the current time in milliseconds
internal actual fun getElapsedTimeInMs(): Long =
    SystemClock.elapsedRealtime()
