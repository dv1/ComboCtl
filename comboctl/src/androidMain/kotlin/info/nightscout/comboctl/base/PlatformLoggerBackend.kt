package info.nightscout.comboctl.base

import android.util.Log

actual class PlatformLoggerBackend actual constructor() : LoggerBackend {
    actual override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }
    }
}
