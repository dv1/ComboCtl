// Need to add this to make sure there is no class name collision between
// the class Kotlin generates out of this file and the class generated
// out of commonMain's Utility.kt.
@file:JvmName("UtilityJVM")

package info.nightscout.comboctl.base

import java.lang.System

// Returns the current time in milliseconds
internal actual fun getElapsedTimeInMs(): Long =
    System.nanoTime() / 1000000L
