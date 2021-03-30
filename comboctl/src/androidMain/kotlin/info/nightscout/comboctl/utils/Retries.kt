package info.nightscout.comboctl.utils

import info.nightscout.comboctl.base.LogLevel
import info.nightscout.comboctl.base.SingleTagLogger

 fun <T> retryBlocking(
     numberOfRetries: Int,
     delayBetweenRetries: Long = 100,
     logger: SingleTagLogger? = null,
     block: () -> T
 ): T {
    repeat(numberOfRetries) {
        try {
            return block()
        } catch (exception: Exception) {
            logger?.invoke(LogLevel.VERBOSE, exception) { "retrying" }
        }
        Thread.sleep(delayBetweenRetries)
    }
    return block() // last attempt
}
