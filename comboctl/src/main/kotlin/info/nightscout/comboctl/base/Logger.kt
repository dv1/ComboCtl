package info.nightscout.comboctl.base

/**
 * Valid log categories.
 *
 * TODO: Perhaps find a way to extend this without violating the open/closed principle.
 */
enum class LogCategory(val str: String) {
    APP_LAYER("APP_LAYER"),
    CIPHER("CIPHER"),
    FRAME("FRAME"),
    PACKET("PACKET"),
    TP_LAYER("TP_LAYER"),
    BLUETOOTH("BLUETOOTH")
}

/**
 * Valid log levels.
 */
enum class LogLevel(val str: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR")
}

/**
 * Interface for backends that actually logs the given message.
 *
 * The logic before the backend takes care of filtering messages based on
 * category and log level, so the backend does not have to worry about
 * that. In fact, it is recommended to turn off any filtering in the
 * backend and let the frontend worry about that, since otherwise, logging
 * behavior is unintuitive in that the filtering behaves in a confusing
 * manner (for example, the frontend is configured to use a DEBUG log level
 * for category APP_LAYER, and yet, debug log levels don't show up).
 */
interface LoggerBackend {
    /**
     * Instructs the backend to log the given message.
     *
     * The category and log level are provided so the backend can highlight
     * these in its output in whatever way it wishes.
     *
     * In addition, a throwable can be logged in case the log line
     * was caused by one. The [LoggerFactory.Logger.log] call may have provided
     * only a message string, or a throwable, or both, which is why both of these
     * arguments are nullable.
     *
     * As said above, do not apply level or category based filtering here.
     * Just log the line.
     *
     * @param category Category the message belongs to.
     * @param level Log level of the given message.
     * @param message Optional string containing the message to log.
     * @param throwable Optional throwable that got passed to [LoggerFactory.Logger.log].
     */
    fun log(category: LogCategory, level: LogLevel, message: String?, throwable: Throwable?)
}

/**
 * Backend that does not actually log anything. Logical equivalent of Unix' /dev/null.
 */
class NullLoggerBackend : LoggerBackend {
    override fun log(category: LogCategory, level: LogLevel, message: String?, throwable: Throwable?) = Unit
}

/**
 * Backend that prints log lines to stderr.
 */
class StderrLoggerBackend : LoggerBackend {
    override fun log(category: LogCategory, level: LogLevel, message: String?, throwable: Throwable?) {
        var str = "[%s] [%s]".format(level.str, category.str)
        if (throwable != null)
            str += " (" + throwable.javaClass.name + ": \"" + throwable.message + "\")"
        if (message != null)
            str += " $message"
        System.err.println(str)
    }
}

/**
 * Main logger factory.
 *
 * This is the main entrypoint for logging. The way this works is that the
 * factory internally creates one logger for every logging category, and
 * passes every Logger instance the backend and default log level. The
 * Logger itself also gets a reference to the factory. This then makes it
 * possible to access other loggers from Logger itself.
 *
 * This is useful if for example in class A, the Logger for category X1 is
 * used, and inside A, an instance of class B is created, which shall get
 * a Logger for category X2. Example:
 *
 * ```
 * class A(logger: Logger) {
 *     // Code in here will log with the X1 category, since A is
 *     // instantiated that way below
 *
 *     fun foo() { 123 }
 *
 *     fun bar(): B {
 *         logger.log(LogLevel.DEBUG) { "Hello %d".format(foo()) }
 *         return B(logger.getLogger(LogCategory.X2))
 *     }
 * }
 *
 * class B(logger: Logger) {
 *     // Code in here will log with the X2 category
 *
 *     fun abc(): YetAnotherClass {
 *         return YetAnotherClass(logger.getLogger(LogCategory.AnotherCategory))
 *     }
 * }
 *
 * // Create factory with default log level "INFO" and stderr backend
 * val loggerFactory = LoggerFactory(StderrLoggerBackend())
 * val loggerX1 = loggerFactory.getLogger(LogCategory.X1)
 * val a = A(loggerX1)
 * // Produces no output, since DEBUG is below the current log level INFO
 * a.bar()
 * loggerX1.logLevelThreshold = LogLevel.DEBUG
 * // Produces log output after setting the logger's level to DEBUG
 * a.bar()
 * ```
 *
 * That way, using different categories for different components is easy.
 * This also makes sure that no new Logger instances are created. Also,
 * log levels are set per-category, and can be changed at any time.
 *
 * Furthermore, the log() call is actually passed a Lambda expression, and
 * not just a string. This makes it possible to evaluate more complex log
 * lines only on-demand. In the example above, foo() is called only in the
 * second a.bar() call, since in the first, the log level check fails, so
 * the Lambda is not executed. This helps performance, since potentially
 * complicated log lines are only evaluated if they'd actually be logged.
 *
 * [Logger.log] can also accept a Throwable. This is useful when an additional
 * toolchain is part of the build and verification & integration process,
 * and said toolchain can use the stacktrace of Throwables. Since it is
 * plausible that sometimes, only the throwable is of interest, the message
 * string is optional if a Throwable is also passed.
 */
class LoggerFactory(val backend: LoggerBackend, private val defaultLogLevelThreshold: LogLevel = LogLevel.INFO) {
    /**
     * Main logger API.
     *
     * The [LoggerFactory] typically is only called at the top level, where
     * components are initialized. Actual logging is done through this class.
     * It takes care of verifying the log level to see if the given log lambda
     * should actually be executed.
     *
     * There are two ways to get instances of this class: with LogFactory,
     * and with Logger itself. In both cases, [getLogger] is used to get a
     * logger. Logger itself also has a [getLogger] function to allow for
     * chaining to sub-components (see the example in the [LoggerFactory]
     * documentation for details how to use this).
     */
    class Logger(val factory: LoggerFactory, val category: LogCategory, var logLevelThreshold: LogLevel) {
        // NOTE: Using overloads instead of default arguments, since
        // otherwise, the inline modifier cannot be used. Also, the
        // Throwable is placed before the trailing lambda, lambda,
        // which can cause problems with some calls sometimes if a
        // nullable Throwable were used.

        fun log(level: LogLevel, throwable: Throwable) {
            if (level.ordinal >= logLevelThreshold.ordinal) {
                factory.backend.log(category, level, null, throwable)
            }
        }

        inline fun log(level: LogLevel, logLambda: () -> String) {
            if (level.ordinal >= logLevelThreshold.ordinal) {
                factory.backend.log(category, level, logLambda.invoke(), null)
            }
        }

        inline fun log(level: LogLevel, throwable: Throwable, logLambda: () -> String) {
            if (level.ordinal >= logLevelThreshold.ordinal) {
                factory.backend.log(category, level, logLambda.invoke(), throwable)
            }
        }

        fun getLogger(category: LogCategory) = factory.getLogger(category)
    }

    // Cache the values array.
    private val logCategoryValues = LogCategory.values()
    // Create one logger per category.
    private val loggers = Array<Logger>(logCategoryValues.size) {
        i -> Logger(this, logCategoryValues[i], defaultLogLevelThreshold)
    }

    /**
     * Gets a logger for the given category.
     */
    fun getLogger(category: LogCategory) = loggers[category.ordinal]
}

// Typealias to be able to just type "Logger" as if Logger were a top-level class.
typealias Logger = LoggerFactory.Logger
