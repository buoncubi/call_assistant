package digital.boline.callAssistant

import org.apache.logging.log4j.core.config.Configurator
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import org.slf4j.event.Level as SLF4JLevel
import org.apache.logging.log4j.Level


// TODO be sure to wait for logg to be flushed when app is closing

/**  The max lenght of the logger name that will be log before to troncate. */
private const val MAX_LOG_NAME_LENGTH = 15


/**
 * Right pads a string to a specified length, either with spaces or truncating with ellipsis.
 *
 * Behavior:
 * - If input is `null`, it bheavies like the input is `???`.
 * - If `maxLength` is less than or equal to 3 and string needs truncating, returns just dots.
 * - If string length is already >= `maxLength`, truncates with ellipsis (i.e., "...").
 * - If string length is < `maxLength`, pads with spaces on the right.
 *
 * @param maxLength The desired length of the resulting string, which must be non-negative.
 * By default it is [MAX_LOG_NAME_LENGTH].
 * @param padChar The character to use for padding (defaults to space). By default it is `' '`
 * @return The padded or truncated string of exactly maxLength characters.
 */
internal fun String?.rightPadOrTruncate(maxLength: Int = MAX_LOG_NAME_LENGTH, padChar: Char = ' '): String {

    // Handle null input by treating it as "???"
    val inputStr = this ?: "???"

    // If string fits within maxLength, pad with spaces
    if (inputStr.length <= maxLength) {
        return inputStr.padEnd(maxLength, padChar)
    }

    // Handle truncation cases
    return when {
        maxLength <= 3 -> ".".repeat(maxLength)
        else -> inputStr.take(maxLength - 3) + "..."
    }
}


/**
 * Returns the logger prefix to identify the class that produces a specific log. Such a prefix is formatted as
 * `"[${clazz.javaClass.simpleName.rightPadOrTruncate()}]:: "`.
 *
 * @param clazz The class that produces logs.
 * @return String The suffix to log for identify the log producer based on the class name.
 */
internal fun getLoggerTag(clazz: Class<*>) = "[${clazz.simpleName.rightPadOrTruncate()}]:: "



/**
 * The interface that provides logging facilities for all the classes of this project.
 *
 * If a class does not extend another class, then it should exploit [Loggable] instead of this interface. However,
 * since a class cannot extend two classes, if a class has to extend another class for any reason, then it can implement
 * the [LoggerInterface] to benefit of logging facilities.
 *
 * In particular any class that implements this interface should be defined as:
 * ```
 * class MyClass : LoggableInterface, AnotherClass() {
 *     protected val logger = CentralizedLogger(this.javaClass, loggerFactory())
 *
 *     // Use here functions like: `logTrace()`, `logDebug()`, `logInfo()`, `logWarn()`, `logError()`.
 * }
 * ```
 * This is enough to assure logging facilities, whcih are private only to the class implementing this interface.
 *
 * For example,`INFO` messages can be logged as shown below (and the same syntax is available for all logging levels):
 * ```
 *  this.logInfo("A message. Do not use string concatenation here!")
 *  this.logInfo("A message concatenate with data structures {} {} efficiently", anyObject1, anyObject2)
 *  this.logInfo("A message with data structures {} {} and a throwable", anyObject1, anyObject2, throwable)
 * ```
 * Where the arguments are:
 *  1. A message string with possible template parameters expressed as `"{}"`.
 *  2. A list of objects that have been parametrized in the message. This allows efficient string concatenation without
 *     creating copies string, and without the possibility to lose time generating a log that might never be printed due
 *     to a low logging level.
 *  3. Eventually, a throwable that is used to log the related error message, cause and stacktrace. The throwable must
 *     be the last element of the object list (introduced in the previous point), and the number of template
 *     parameter `"{}"` should not include the throwable itself.
 *
 * The current implementation uses [org.apache.logging.log4j.Logger] (bridged through [org.slf4j.LoggerFactory]), which
 * assures asynchronous logging behaviour, and it is the recommended implementation for AWS lambda functions.
 * This interface assumes that all classes and object defined in the same Kotlin package share the same logger
 * configuration (for more, see [Factory]). This assumption is reflected in the logger configuration file available at
 * `src/main/resources/log4j2.xml`. With this assumption, it is possible to set the logging level of all the
 * implementations belonging to the same Kotlin package in a centralized manner. This approach allows using a file
 * containig environmental variables that defines the logging level for each Kotlin package of this porject; such a
 * file is `src/main/resources/log_config.env` (see also `public.gradle.kts` for more information). Indeed, the latter
 * file constains an environmental variable for each package of this project.
 *
 * @see Loggable
 * @see LoggableInterface.Factory
 * @see Logger
 * @see LogManager
 *
 * @author Luca Buoncompagni © 2025
 */
interface LoggableInterface {

    /**
     * An inner class that makes logging private, even through the [LoggableInterface].
     *
     * This class implements the factory patter, and that `clazz` constructor parameter (which should be set to `this`
     * by the class that implements the [LoggableInterface] interface) is only used by [getNewLogger] to retrieve the
     * package name. Such a name will identify the [slf4jLogger] used to log messages, which is configured in
     * `src/main/resources/log4j2.xml`, and relative logging level is specified through the environmental variables
     * defined in the `src/main/resources/log_config.env` file. For more info see [LoggableInterface].
     *
     * @property slf4jLogger The object used to produce logs, which provides facilities as [Logger.trace], [Logger.debug],
     * [Logger.info], [Logger.warn] and [Logger.error].
     *
     * @constructor Uses [getNewLogger] to initialize the [slf4jLogger] property with a name equal to the java package
     * related to the `clazz` given as input. This constructor cannot be used since it is private, but you can access
     * to it thorugh [loggerFactory].
     *
     * @see LoggableInterface
     * @see Loggable
     * @see Logger
     * @see LogManager
     *
     * @author Luca Buoncompagni © 2025
     */
    private class Factory(clazz: Class<out LoggableInterface>) {

        // Documented in the Kotlin doc above.
        val slf4jLogger: Logger by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            getNewLogger(clazz)
        }

        companion object {

            /**
             * A [Logger] used by the [LoggableInterface.Factory] only, and not by the classes that implement such an
             * interface.
             *
             * It is based on [getNewLoggerByName], where the input parameter is `Loggable::class.java`, so
             * its name will always be the package name of [LoggableInterface], independently from the class that
             * implements the `LoggableInterface`.
             *
             * Note that the `internalLogger` is shared among oll the instances of the `LoggableInterface`.
             */
            @get:Synchronized
            private val internalLogger by lazy {
                val clazz = LoggableInterface::class.java
                val logger =  getNewLoggerByName(getLoggerName(clazz))
                logger.trace("{}Created a new internal logger with level '{}', name '{}', and type '{}'.",
                    getLoggerTag(clazz), getLevel(logger), logger.name, logger.javaClass)
                logger // return the logger
            }

            /**
             * Defines the convention to identify each [Logger] used in this project. Actually, each logger is
             * identified by the package name of the class that produces logs.
             *
             * @param clazz The class that produces logs.
             *
             * @return The package name where `clazz` belongs to.
             */
            @Synchronized
            private fun getLoggerName(clazz: Class<*>) = clazz.packageName

            /**
             * Uses uses [getNewLoggerByName], to initialize the [Logger] with a new instance, which name is given by
             * [getLoggerName] with `clazz` as input parameter. This function is used to initialize each [Logger] used
             * in this project.
             *
             * @param clazz Class<*> A class, which package name will define the [Logger] name.
             * @return A new `Logger` instance.
             */
            @Synchronized
            private fun getNewLogger(clazz: Class<*>): Logger {
                val logger = getNewLoggerByName(getLoggerName(clazz))
                internalLogger.trace("{}Creating a new logger with level '{}', name '{}', and type '{}'.",
                    getLoggerTag(clazz), getLevel(logger), logger.name, logger.javaClass)
                return logger
            }

            /**
             * Returns the current logging levele of the `logger`.
             *
             * @param logger The `logger` to get the logging level from.
             *
             * @return String the logging [Level], which can be `TRACE`, `DEBUG`, `INFO`, `WARN` or `ERROR`. It might
             * return `null` if the logs are disables.
             */
            @Synchronized
            fun getLevel(logger: Logger): Level? =
                when {
                    logger.isTraceEnabled -> Level.TRACE
                    logger.isDebugEnabled -> Level.DEBUG
                    logger.isInfoEnabled -> Level.INFO
                    logger.isWarnEnabled -> Level.WARN
                    logger.isErrorEnabled -> Level.ERROR
                    else -> null
                }

            /**
             * Uses the [LoggerFactory.getLogger] to returns the reference to a new logger with the specified name.
             *
             * Note that the type of returned `logger` is based on the dependences set into `public.gradle.kts`, which
             * is currently set to return an instance of [org.apache.logging.log4j.core.Logger].
             *
             * @param loggerName The name that will identify the new logger instance.
             *
             * @return A new [Logger] instance.
             */
            @Synchronized
            private fun getNewLoggerByName(loggerName: String): Logger = LoggerFactory.getLogger(loggerName)
        }
    }


    /**
     * It instanciate a new [Factory] and provides a reference to the [Logger] instance, which identifier is associated
     * with the package name of `this`. Note that this function can only be used by the classes implementing the
     * [LoggableInterface] interface,
     *
     * This function follows the assumptio that each package of this project whill share the same loggin configuration,
     * which are defined on the `src/main/resources/log4j2.xml` file. To change the logging levels, you can modify the
     * `src/main/resources/log_config.env` file, which contains an environmental variable associated with each package
     * of this project. For more information see [LoggableInterface] and [Factory].
     *
     * @receiver Loggable It is an extension function designed to make this function impossible to be used from classess
     * that do not implement the [LoggableInterface]
     *
     * @return A private logger instance to produce `TRACE`, `DEBUG`, `INFO`, `WARN`, and `ERROR`, logs.
     */
    fun LoggableInterface.loggerFactory(clazz: Class<out LoggableInterface>? = null) = Factory(clazz ?: this.javaClass).slf4jLogger

    companion object {
        /**  The constant character of a new line agnostic to the operative system (i.e., '\n' or '\r\n'). */
        public val NEW_LINE: String = System.lineSeparator()
    }
}



/**
 * An helper class design to give the possibility to pass the [Loggable.logger] among different classes. It should
 * be used only within a class implementing the [LoggableInterface], but it can be use for giving logging
 * facilities to lambda functions and anonymous classes.
 *
 * This class provides logging facilities as [logTrace], [logDebug], [logInfo], [logWarn] and [logError], which
 * introduce a logging prefix with the name of th class that produces the log (see [getLoggerTag]). Such a
 * functionalities wraps the relative functionalities of [slf4jLogger].
 *
 * @property slf4jLogger The actual logger. It should not be used, if needed, use [Loggable.slf4jLogger] instead.
 * @property loggerTag The name of the class that produces the log, which will be used as message prefix.
 *
 * @constructor it stores the given [slf4jLogger] in the related property, and initialise the [loggerTag].
 *
 * @see Loggable
 *
 * @author Luca Buoncompagni © 2025
 */
class CentralizedLogger(clazz: Class<out LoggableInterface>, private val slf4jLogger: Logger) {

    /**
     * The logger tag used to identify the class that produces logs. It is used to prefix all the logs.
     */
    private val loggerTag = getLoggerTag(clazz)

    /**
     * Sets the logging level of this logger. This function should only be used for testing purposes, use environmental
     * variable on production instead.
     * @param level The new logging level.
     */
    fun setLevel(level: SLF4JLevel) {
        // Convert SLF4J level to Log4j2 level
        val log4jLevel = when (level) {
            SLF4JLevel.ERROR -> Level.ERROR
            SLF4JLevel.WARN -> Level.WARN
            SLF4JLevel.INFO -> Level.INFO
            SLF4JLevel.DEBUG -> Level.DEBUG
            SLF4JLevel.TRACE -> Level.TRACE
        }
        // Set the level using Log4j2 Configurator
        Configurator.setLevel(slf4jLogger.name, log4jLevel)
        trace("Logging level set to '{}'.", log4jLevel)
    }

    /**
     * Logs a message with the `TRACE` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    fun trace(message: String, vararg args: Any?) = slf4jLogger.info("${loggerTag}$message", *args)


    /**
     * Logs a message with the `DEBUG` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    fun debug(message: String, vararg args: Any?) = slf4jLogger.debug("${loggerTag}$message", *args)


    /**
     * Logs a message with the `INFO` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    fun info(message: String, vararg args: Any?) = slf4jLogger.info("${loggerTag}$message", *args)


    /**
     * Logs a message with the `WARNING` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    fun warn(message: String, vararg args: Any?) = slf4jLogger.warn("${loggerTag}$message", *args)


    /**
     * Logs a message with the `ERROR` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    fun error(message: String, vararg args: Any?) = slf4jLogger.error("${loggerTag}$message", *args)
}



/**
 * A basic implementation of the [LoggableInterface] interface. It provides logging facilities to classes that extend
 * this class.
 *
 * This class uses the [LoggableInterface.loggerFactory] to provides private logging facilities through the
 * [slf4jLogger] property. It also implements a centralised entry points for all the logs through the functions
 * [logTrace], [logDebug], [logInfo], [logWarn] and [logError], which are recommended since they introduce the name of
 * the logging class as prefix to the message.
 *
 * The typical usage example, where you want to give to a class logging facilities is
 * ```
 * class MyClass : Logger() {
 *     // Use here functions like: `logTrace()`, `logDebug()`, `logInfo()`, `logWarn()`, `logError()`.
 * }
 * ```
 *
 * @constructor It uses [loggerFactory] to initialize the [slf4jLogger] property, which is used to initialize the
 * [logger] property and an instance of [CentralizedLogger]. This is done for giving the possibility to pass the
 * [logger] among different classes if required. The constructor takes the `clazz` input parameter, which is used to
 * manage the name of the class creating the log, which is  used to prefix the log messages. If `clazz` is `null`, the
 * name of the class is the name of the class that implements the [LoggableInterface] interface (i.e., `this`). For
 * instance, if you want to generate logs from a companion object you can define
 * ```
 * interface A: LoggableInterface {
 *
 *     companion object: Loggable(A::class.java) {  // Tho use `this` package name, `A::class.java` can be obmitted.
 *         fun logSomething() {
 *             this.logInfo("Something happened!")
 *         }
*      }
 * }
 * ```
 *
 * @property slf4jLogger The [Logger] instance associated with this class to produce general logs. This is the actual
 * logger, while [logger] is a wrapper of it. It is recommended to use [logger] instead of this property.
 * @property logger The  [CentralizedLogger] instance associated with this class to produce logs in a centralized
 * manner. It is recommended to use this property instead of the [slf4jLogger] property. Indeed this property is used to
 * implement [logTrace], [logDebug], [logInfo], [logWarn] and [logError] functions.
 *
 * @see LoggableInterface
 * @see LoggableInterface.Factory
 * @see LoggableInterface.Factory.getNewLogger
 *
 * @author Luca Buoncompagni © 2025
 */
open class Loggable(clazz: Class<out LoggableInterface>? = null) : LoggableInterface {

    // Documented above.
    protected val slf4jLogger by lazy { loggerFactory(clazz) }

    protected val logger: CentralizedLogger

    init {
        if (clazz == null)
            logger = CentralizedLogger(this.javaClass, slf4jLogger)
        else
            logger = CentralizedLogger(clazz, slf4jLogger)
    }


    /**
     * Logs a message with the `TRACE` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    protected fun logTrace(message: String, vararg args: Any?) = logger.trace(message, *args)


    /**
     * Logs a message with the `DEBUG` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    protected fun logDebug(message: String, vararg args: Any?) = logger.debug(message, *args)


    /**
     * Logs a message with the `INFO` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    protected fun logInfo(message: String, vararg args: Any?) = logger.info(message, *args)


    /**
     * Logs a message with the `WARNING` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    protected fun logWarn(message: String, vararg args: Any?) = logger.warn(message, *args)


    /**
     * Logs a message with the `ERROR` level.
     * @param message the message to be logged with the name of this class as prefix.
     * @param args data structures to be logged, see [LoggableInterface] for mroe.
     */
    protected fun logError(message: String, vararg args: Any?) = logger.error(message, *args)

    /**
     * Sets the logging level of this logger. This function should only be used for testing purposes, use environmental
     * variable on production instead.
     * @param level The new logging level.
     */
    fun setLoggingLevel(level: SLF4JLevel) {
        logger.setLevel(level)
    }


    companion object {
        /**  The constant character of a new line agnostic to the operative system (i.e., '\n' or '\r\n'). */
        public val NEW_LINE: String = LoggableInterface.Companion.NEW_LINE
    }
}