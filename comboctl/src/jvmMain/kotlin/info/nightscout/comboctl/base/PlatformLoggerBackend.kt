package info.nightscout.comboctl.base

// XXX: Currently, just output to stderr. In the future, perhaps
// a more sophisticated logger can be used for JVM targets.
actual class PlatformLoggerBackend actual constructor() : LoggerBackend {
    actual override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) {
        val timestamp = getElapsedTimeInMs()

        val stackInfo = Throwable().stackTrace[1]
        val className = stackInfo.className.substringAfterLast(".")
        val methodName = stackInfo.methodName
        val lineNumber = stackInfo.lineNumber

        val fullMessage = "[${timestamp.toStringWithDecimal(3).padStart(10, ' ')}] " +
            "[${level.str}] [$tag] [$className.$methodName():$lineNumber]" +
            (if (throwable != null) "  (${throwable::class.qualifiedName}: \"${throwable.message}\")" else "") +
            (if (message != null) "  $message" else "")

        System.err.println(fullMessage)
    }
}
