package info.nightscout.comboctl.base

/**
 * Valid log levels.
 */
enum class LogLevel(val str: String) {
    VERBOSE("VERBOSE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR")
}

/**
 * Interface for backends that actually logs the given message.
 */
interface LoggerBackend {
    /**
     * Instructs the backend to log the given message.
     *
     * The tag and log level are provided so the backend can highlight
     * these in its output in whatever way it wishes.
     *
     * In addition, a throwable can be logged in case the log line
     * was caused by one. The [SingleTagLogger.invoke] call may have provided
     * only a message string, or a throwable, or both, which is why both of these
     * arguments are nullable.
     *
     * @param tag Tag for this message. Typically, this is the name of the
     *        class the log operation was performed in.
     * @param level Log level of the given message.
     * @param message Optional string containing the message to log.
     * @param throwable Optional throwable.
     */
    fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?)
}

/**
 * Backend that does not actually log anything. Logical equivalent of Unix' /dev/null.
 */
class NullLoggerBackend : LoggerBackend {
    override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?) = Unit
}

/**
 * Backend that prints log lines in a platform specific manner.
 */
expect class PlatformLoggerBackend() : LoggerBackend {
    override fun log(tag: String, level: LogLevel, throwable: Throwable?, message: String?)
}

class SingleTagLogger(val tag: String) {
    inline operator fun invoke(logLevel: LogLevel, logLambda: () -> String) =
        Logger.backend.log(tag, logLevel, null, logLambda.invoke())

    operator fun invoke(logLevel: LogLevel, throwable: Throwable) =
        Logger.backend.log(tag, logLevel, throwable, null)

    inline operator fun invoke(logLevel: LogLevel, throwable: Throwable, logLambda: () -> String) =
        Logger.backend.log(tag, logLevel, throwable, logLambda.invoke())
}

/**
 * Main logging interface.
 *
 * Applications can set a custom logger backend simply by setting
 * the [Logger.backend] variable to a new value. By default, the
 * [PlatformLoggerBackend] is used.
 *
 * The logger is used by adding a line like this at the top of:
 * a source file:
 *
 *     private val logger = Logger.get("TagName")
 *
 * Then, in the source, logging can be done like this:
 *
 *     logger(LogLevel.DEBUG) { "Logging example" }
 *
 * This logs the "Logging example line" with the DEBUG log level
 * and the "TagName" tag (see [LoggerBackend.log] for details).
 */
object Logger {
    var backend: LoggerBackend = PlatformLoggerBackend()
    fun get(tag: String) = SingleTagLogger(tag)
}
