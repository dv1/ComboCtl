package info.nightscout.comboctl.base

// XXX: Currently, just output to stderr. In the future, perhaps
// a more sophisticated logger can be used for JVM targets.
actual class PlatformLoggerBackend actual constructor() : LoggerBackend {
    actual override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
        var str = "[${level.str}] [$tag]"

        if (throwable != null)
            str += " (" + throwable::class.qualifiedName + ": \"" + throwable.message + "\")"

        if (message != null)
            str += " $message"

        System.err.println(str)
    }
}