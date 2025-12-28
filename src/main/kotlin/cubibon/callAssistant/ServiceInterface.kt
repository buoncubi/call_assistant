package digital.boline.callAssistant

import kotlinx.coroutines.*
import software.amazon.awssdk.http.SdkCancellationException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.measureTime


/**
 * The base interfaces for all asynchronous services. Note that a service is not designed to have multiple parallel
 * computations at the same time.
 *
 * The service should be based on Kotlin coroutines and encompass [computeAsync], which starts the service, as well as
 * [stop] and [wait]. See [ReusableServiceInterface] for services that need to initialize and close resources only once
 * for all computations over time.
 *
 * This service is designed to be used with [CallbackManagerInterface] to provide a callback mechanisms (e.g., on error
 * or result), and with [FrequentTimeout] and [Timeout] to provide a timeout mechanisms. It also provides mechanism to
 * propagate to the callbacks and timeouts lambda-functions an identifier from the class that requested the asynchronous
 * service. Such identifier is the `sourceTag`, which is a String set to [UNKNOWN_SOURCE_TAG] (i.e., empty) by default.
 * Note that `sourceTag` is not used by this class (so it can have any value), but just propagated among callbacks and
 * functions.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isComputing The flag indicating if the service is currently computing or not, in asynchronous manner. It is
 * set by [computeAsync] and reset by [stop] and [wait].
 *
 * @see Service
 * @see ReusableServiceInterface
 * @see ReusableService
 * @see CallbackManagerInterface
 * @see FrequentTimeout
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface ServiceInterface<I> : LoggableInterface {

    // See documentation above.
    val isComputing: AtomicBoolean


    /**
     * Checks if the service is in a state that allows to run [computeAsync].
     * @return `true` if the service state allows for computation.
     */
    fun canCompute(): Boolean


    /**
     * Starts the service computation asynchronously with an optional timeout.
     *
     * @param input The input data required to compute the service.
     * @param timeoutSpec The specification of the timeout (see [FrequentTimeout] for more). If it is `null` (which is
     * the default value), not timeout policy will be applied.
     * @param sourceTag An identifier that will be propagated to lambda function associated with this method, e.g.,
     * on result callbacks, on error callbacks, and timeout callbacks. By default, it is empty.
     * @return `true` if the service computation was started successfully.
     */
    fun computeAsync(input: I, timeoutSpec: FrequentTimeout? = null, sourceTag: String = UNKNOWN_SOURCE_TAG): Boolean


    /**
     * Waits for the service computation started with [computeAsync] to complete. Optionally, a timeout with relative
     * callback can be specified.
     *
     * @param timeoutSpec The specification of the timeout (see [Timeout] for more). If it is `null` (which is the
     * default value), no timeout policy will be applied.
     * @param sourceTag An identifier that will be propagated to lambda function associated with this method, e.g.,
     * on error callbacks, and timeout callbacks. By default, it is empty.
     * @return `true` if the service computation was completed successfully.
     */
    suspend fun wait(timeoutSpec: Timeout? = null, sourceTag: String = UNKNOWN_SOURCE_TAG): Boolean


    /**
     * Immediately stops the current service computation.
     * @param sourceTag An identifier that will be propagated to lambda function associated with this method, e.g.,
     * on error callbacks, and timeout callbacks. By default, it is empty.
     * @return `true` if the service computation was stopped successfully.
     */
    fun stop(sourceTag: String = UNKNOWN_SOURCE_TAG): Boolean


    /**
     * Cancelling the coroutine scope. After this operation the service cannot be computed again, and a new service
     * instance is required.
     */
    fun cancelScope()

    companion object {
        /** The default value of the `sourceTag` parameter. By default, it is an empty string. */
        const val UNKNOWN_SOURCE_TAG = ""
    }
}



/**
 * Specification of the timeout policy implement by [ServiceInterface.wait].
 *
 * Note that this class does not implement the [equals] and [hashCode] methods.
 *
 * @property timeout The number of milliseconds after which the computation is stopped since timeout is considered
 * expired. Default value is [DEFAULT_TIMEOUT].
 * @property callback A lambda function that is called when the timeout expires. Such a function takes as input the
 * `sourceTag`, which is an identifier given as input to the method that generated this timeout and propagated to the
 * callback. Default value is [DEFAULT_CALLBACK], which is `null`, i.e., the callback is ignored.
 *
 * @see ServiceInterface
 * @see Service
 * @see FrequentTimeout
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
open class Timeout(
    val timeout: Long = DEFAULT_TIMEOUT,
    val callback: ((String) -> Unit)? = DEFAULT_CALLBACK)
{
    /**
     * Invokes the callback function associated with this timeout specification. If [callback] is `null`, then this
     * function does nothing.
     */
    fun invokeCallback(sourceTag: String) {
        callback?.let { it(sourceTag) }
    }

    /**
     * Returns a string representation of this timeout specification by logging all its properties.
     * @return A string representation of this timeout specification.
     */
    override fun toString() = "{${timeoutToString(timeout)}, ${callbackToString(callback)}}"

    companion object {

        /** The default timeout value, which is set to 20 seconds. */
        @JvmStatic
        protected val DEFAULT_TIMEOUT: Long = 20_000

        /** The default callback function, which is to `null`, i.e., it does nothing. */
        @JvmStatic
        protected val DEFAULT_CALLBACK = null

        /**
         * The string representation of [timeout], which is used in the [toString] method from this and derived classes.
         * @return The string representation of [timeout].
         */
        @JvmStatic
        protected fun timeoutToString(timeout: Long) = "timeout: $timeout ms"

        /**
         * The string representation of [callback], which is used in the [toString] method from this and derived
         * classes. It just states if the callback is set or not.
         * @return The string representation of [timeout].
         */
        @JvmStatic
        protected fun callbackToString(callback: ((String) -> Unit)?) =
            if (callback != null) "with callback" else "without callback"
    }
}



/**
 * Specification of the timeout policy implement by [ServiceInterface.computeAsync]. This implementation is used when
 * the starting instance to compute the timeout should be reset.
 *
 * Note that this class does not implement the [equals] and [hashCode] methods.
 *
 * @property timeout The number of milliseconds after which the computation is stopped since timeout is considered
 * expired. Default value is [Timeout.DEFAULT_TIMEOUT].
 * @property checkPeriod The delay in milliseconds between each checks of timeout expiration. Default value is
 * [DEFAULT_CHECK_PERIOD].
 * @property callback A lambda function that is called when the timeout expires. Such a function takes as input the
 * `sourceTag`, which is an identifier given as input to the method that generated this timeout and propagated to the
 * callback. Default value is [Timeout.DEFAULT_CALLBACK], which is `null`, i.e., the callback is ignored.
 *
 * @see ServiceInterface
 * @see Service
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class FrequentTimeout (
    timeout: Long = DEFAULT_TIMEOUT,
    val checkPeriod: Long = DEFAULT_CHECK_PERIOD,
    callback: ((String) -> Unit)? = DEFAULT_CALLBACK
) : Timeout(timeout, callback)
{

    /**
     * Returns a string representation of this timeout specification by logging all its properties.
     * @return A string representation of this timeout specification.
     */
    override fun toString() =
        "{${timeoutToString(timeout)}, checkingPeriod: $checkPeriod ms, ${callbackToString(callback)}}"

    companion object {
        /** The default check period value, which is set to 0.1 seconds. */
        private const val DEFAULT_CHECK_PERIOD: Long = 100
    }
}



/**
 * The enumerator to track which function experienced error a try-state block that catches all Exception. This
 * enumeration is given to [Service.doThrow], and it encompasses the following cases.
 *
 * This enumerator defines the `errorLog` value, which is only used for logging purposes.
 *
 * @property ACTIVATING Used in case of errors in the [ReusableService.activate] and [ReusableService.doActivate] methods.
 * @property COMPUTING Used in case of errors in the [Service.computeAsync] and [ReusableService.doComputeAsync] methods,
 * when the exception occurs in the coroutine related to [Service.computingJob].
 * @property TIMEOUT Used in case of errors in the [Service.computeAsync] and [ReusableService.doComputeAsync] methods,
 * when the exception occurs in the coroutine related to [Service.timeoutJob].
 * @property WAITING Used in case of errors in the [Service.wait] and [Service.doWait] methods.
 * @property STOPPING Used in case of errors in the [Service.stop] and [Service.doStop] methods.
 * @property DEACTIVATING Used in case of errors in the [ReusableService.deactivate] and [ReusableService.doDeactivate]
 * methods.
 *
 * @see Service
 * @see ReusableService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
enum class ErrorSource(val errorLog: String){
    ACTIVATING("an activation error"),
    COMPUTING("a computation error"),
    TIMEOUT( "a computation timeout error"),
    WAITING("a waiting error"),
    STOPPING("a stopping error"),
    DEACTIVATING("a deactivation error");
}



/**
 * A data class that encompasses the `throwable` to handle, and the `experiencedError`, which identify the function
 * producing the error (see [ErrorSource] for more). This class is used as input parameter to [Service.doThrow],
 * which manages all try-catch block of [Service] and [ReusableService], and as input parameter to related callbacks as
 * set in [Service.onErrorCallbacks]. See [Service.doThrow] for more.
 *
 * @property throwable The exception to handle
 * @property source An enumerator that identifies the calling function and relative logs, see
 * [ErrorSource] for more.
 * @param sourceTag  an identifier given as input to the method that generated this error and propagated to the
 * callbacks available in the [Service.onErrorCallbacks] manager.
 *
 * @see Service
 * @see ReusableService
 * @see ErrorSource
 * @see CallbackManager
 * @see CallbackManagerInterface
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class ServiceError(
    val throwable: Throwable,
    val source: ErrorSource,
    override val sourceTag: String
) : CallbackInput {

    override fun copy(): ServiceError =
        ServiceError(this.throwable, this.source, this.sourceTag)
}


/**
 * An abstract class that implements [ServiceInterface] and provides a base implementation for asynchronous services.
 *
 * This class manages the [isComputing] flag by implementing the methods defined by the [ServiceInterface]. It also
 * manages logs and exception try-catch. In particular, it defines the abstract method [doComputeAsync], and it provides
 * further customizable methods [doWait], [doStop], and [doThrow]. Note that these methods should only implement the
 * actual logic since logging, exceptions and service states are handled by this class. This class also implements the
 * timeout policies and the coroutine-based methods for waiting or stopping asynchronous jobs.
 *
 * Note that the [ServiceInterface] allow defining callbacks involving: on results, on error and timeouts. The classes
 * tha calls the service's functionalities can specify a `sourceTag` that is propagated to the relative callbacks. Note
 * that `sourceTag` is not used by this class (thus, it can have any value), but it is just propagated among functions
 * and callbacks. Its purpose is to be used by the class using this service. See also [ServiceInterface] for more info.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isComputing The flag indicating if the service is currently computing or not, in asynchronous manner. It is
 * set by [computeAsync] and reset by [stop] and [wait].
 * @property scope A `protected` property that defines where the coroutines used by this service will run. It is
 * initialized during object construction.
 * @property computingJob A `private` property that represents the coroutine job for the computation, which is started
 * with [computeAsync]. It is `null` when the service is not computing (i.e., when [isComputing] is false).
 * @property timeoutJob A `private` property that represents the coroutine job for the timeout watcher associated with
 * [computingJob]. It is `null` when the service is not computing (i.e., when [isComputing] is false), or when no
 * timeout has been set.
 * @property timeoutStart A `private` property that represents the start time of the timeout policy. It is used to
 * calculate the elapsed time since the computation started, and it can be set to the current time in milliseconds
 * through [resetTimeout]. It is `null` when the service is not computing (i.e., when [isComputing] is false), or when
 * no timeout has been set.
 * @property onErrorCallbacks A set of callbacks that are called in case of exceptions. See [doThrow] for more.
 * @property isScopeCancelled A `private` property that is set to `true` when [cancelScope] is invoked. When the scope
 * is cancelled, this class cannot be used anymore to perform further computation.
 *
 * @constructor Requires the [scope].
 *
 * @see ServiceInterface
 * @see ReusableServiceInterface
 * @see ReusableService
 * @see FrequentTimeout
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class Service<I>(protected val scope: CoroutineScope): ServiceInterface<I>, Loggable() {

    // See documentation above.
    private var computingJob: Job? = null

    // See documentation above.
    private var timeoutJob: Job? = null

    // See documentation above.
    private var timeoutStart: AtomicLong? = null

    // See documentation above.
    protected var isScopeCancelled = false

    // See documentation above.
    val onErrorCallbacks = CallbackManager<ServiceError>(logger)

    // See documentation above.
    @Volatile
    final override var isComputing = AtomicBoolean(false)
        private set


    /**
     * Handles the [isComputing] flag for the [computeAsync], [stop], and [wait] methods.
     *
     * It is used to start the computation if the service is not already computing, to stop and to wait it the service
     * is currently computing. In other words, it returns `true` if [isComputing] is equal to the
     * `expectedComputingState` given as input. Otherwise, it returns `false` and logs a warning with a message given
     * as input through the `errorLog` parameter.
     *
     * Note that if [isScopeCancelled] is `true` this function will always return `false`.
     *
     * @param expectedComputingState The expected value of the [isComputing] flag.
     * @param warningLog A string used only for logging purposes when [isComputing] is not as expected.
     * @return `true` if [isComputing] is as expected, `false` otherwise.
     */
    protected open fun checkComputingState(expectedComputingState: Boolean, warningLog: String): Boolean {
        if (isScopeCancelled) {
            logWarn("The service has a cancelled scope and cannot be used anymore!", warningLog)
            return false
        }

        if (isComputing.get() != expectedComputingState) {
            logWarn("Service {}.", warningLog)
            return false
        }
        return true
    }


    /**
     * Check if the service can run [computeAsync].
     * @return `true` if [isComputing] and [isScopeCancelled] are both `false`. Otherwise, returns `false`.
     */
    override fun canCompute(): Boolean = !isComputing.get() && !isScopeCancelled


    /**
     * Runs the computation of this service asynchronously on a separate coroutine within the [scope]. Such a
     * computation is implemented by the [doComputeAsync] method, and the related coroutine is referenced by
     * [computingJob], as well as [doCheckTimeoutAsync] and [timeoutJob].
     *
     * The computation does not run if [checkComputingState], with `false` as input parameter, returns `false`. In
     * other words, it does not run if the service is already computing; in this case, the function returns `false`. If
     * the service computation is successfully started, then [isComputing] is set to `true`.
     *
     * If the given `timeoutSpec` is not `null`, then this method associates the [timeoutJob] to the [computingJob],
     * which is implemented by [doCheckTimeoutAsync].
     *
     * Note that [computingJob] and [timeoutJob] run inside a try-catch block, and the related exceptions are handled
     * by [doThrow] called with [ErrorSource.COMPUTING] or [ErrorSource.TIMEOUT] respectively. Note that [doThrow]
     * invokes the [onErrorCallbacks].
     *
     * @param input The input for the service computation, which is given to [doComputeAsync].
     * @param timeoutSpec The timeout specifications (that might include a callback), which is given to
     * [doCheckTimeoutAsync]. If it is `null`, then the [timeoutJob] will not run.
     * @param sourceTag An identifier that will be propagated to the function associated with this method, e.g.,
     * on result callbacks, on error callbacks, timeout callbacks, and [doComputeAsync]. By default, it is empty.
     * @return `true` if the service computation successfully started; `false` otherwise.
     */
    final override fun computeAsync(input: I, timeoutSpec: FrequentTimeout?, sourceTag: String): Boolean {
        // Check if the computation should start or not.
        if (!checkComputingState(false, "is already computing")) return false

        // If the service should run, then set `isComputing` to `true`.
        isComputing.set(true)

        if (logger.isInfoEnabled()) {
            val inputInfo = Utils.escapeCharacters(input)
            val timeoutInfo = if (timeoutSpec != null)
                ", and timeout specifications: '$timeoutSpec'"
            else
                " (without timeout)"
            logInfo("Service starts computing with input: '$inputInfo$timeoutInfo'.")
        }

        // Start the service computation on a coroutine, which cannot have multiple runs.
        computingJob = scope.launch {

            try {
                // Invoke the actual service computation.
                val computingTime = measureTime {
                    doComputeAsync(input, sourceTag, this)
                }
                logInfo("Service finished computing normally (it took {}).", computingTime)
            } catch (ex: Exception) {
                if (doThrow(ex, ErrorSource.COMPUTING, sourceTag, this) == true) throw ex
            }

            // Reset the computation flag and related job.
            isComputing.set(false)
            computingJob = null
        }

        // Manage timeout if it has been specified.
        if (timeoutSpec != null) {
            // Set the time instant to check for timeout.
            timeoutStart = AtomicLong(System.currentTimeMillis())

            // Start the timeout checker associated with the service computation.
            timeoutJob = scope.launch {

                try {
                    // Invoke the actual timeout checker.
                    doCheckTimeoutAsync(timeoutSpec, sourceTag)
                } catch (ex: Exception) {
                    if (doThrow(ex, ErrorSource.TIMEOUT, sourceTag, this) == true) throw ex
                }

                // Reset the timeout checker.
                timeoutStart = null
                timeoutJob = null
            }

            logDebug("Service activates timeout checker with specifications: '{}'.", timeoutSpec)

        }
        return true
    }


    /**
     * The implementation service's computation that runs on a separate coroutine within the [scope]. Such a coroutine
     * is referenced by [computingJob]. This method should be implemented by the derived class, and it should not be
     * used directly, but by the mean of [computeAsync]. This method is not invoked if [computeAsync] returns `false`.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.COMPUTING].
     *
     * This method should invoke some callback to notify the results, and it should pass the `sourceTag` to them.
     *
     * @param input The input required for the service's computation, as given to [computeAsync].
     * @param sourceTag An identifier that should be propagated to lambda function associated with this method, e.g.,
     * on result callbacks. By default, it is empty, as given to [computeAsync].
     */
    protected abstract suspend fun doComputeAsync(input: I, sourceTag: String, scope: CoroutineScope) //TODO to document


    /**
     * The implementation that checks for timeout and, in case, stops the service's computation.
     *
     * This method runs asynchronously within the coroutine referenced as [timeoutJob]. Such a coroutine is started by
     * [computeAsync]. This method is not invoked if [computeAsync] returns `false`.
     *
     * This method loops with a period specified by [FrequentTimeout.checkPeriod], and it stops lopping in two conditions:
     *  - when [computingJob] completes its computation, and
     *  - when the time interval between now and [timeoutStart] is greater than [FrequentTimeout.timeout].
     * Note that `timeoutStart` is set to the current time in milliseconds when [timeoutJob] starts, and it can be
     * reset through [resetTimeout].
     *
     * When this methods stop looping due to timeout, it invokes [stop] (which terminates the service's computation) and
     * [FrequentTimeout.callback] (if a callback is specified). Note that the callback will receive th `sourceTag`
     * provided to this function.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.TIMEOUT].
     *
     * @param timeoutSpec The timeout specifications, as given to [computeAsync].
     * @param sourceTag An identifier that will be propagated to the timeout-based lambda function associated with this
     * method. By default, it is empty, as given to [computeAsync].
     */
    private suspend fun doCheckTimeoutAsync(timeoutSpec: FrequentTimeout, sourceTag: String) {
        // Loop until the main job (i.e., `computingJob`) is computing.
        while (computingJob?.isCompleted == false) {

            // Get the computation time of the main job.
            val computationTime = System.currentTimeMillis() - timeoutStart!!.get()

            // Check if the computation time is timed-out.
            if (computationTime > timeoutSpec.timeout) {
                // Stop the main and this jobs.
                stop(sourceTag)
                // Invoke the callback if it exists.
                timeoutSpec.invokeCallback(sourceTag)

                logInfo("Service computation has been timed out after {} ms.", computationTime)

            } else {
                logTrace(
                    "Checking service timeout, remaining time: '{}'.",
                     timeoutSpec.timeout - computationTime
                )
            }

            // Wait before to check again if the main job timed-out.
            delay(timeoutSpec.checkPeriod)
        }
    }


    /**
     * If the service is computing with a timeout checker, then it resets the [timeoutStart] to the current time in
     * milliseconds. See [doCheckTimeoutAsync] for more.
     */
    protected fun resetTimeout() {
        if (timeoutStart != null) {
            // If timeout is set, which only occurs if the service is computing.
            timeoutStart!!.set(System.currentTimeMillis())
            logTrace("Service timeout has been reset.")
        } else
            logTrace("Service has not computation timeout to set.")
    }


    /**
     * Waits for the service computation (associated with the [computingJob]) to finish. This method does not wait if
     * the service is not computing (i.e., if [checkComputingState] returns `false` with `true` as input parameter).
     *
     * Optionally, a timeout with related callback can be specified. When a timeout occurs, the callback encoded in the
     * given `timeoutSpec` is invoked and [stop] is called.
     *
     * Note that the actual wait implementation is defined by [doWait], and you can override it to change its behaviour.
     * Also note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.WAITING].
     *
     * This method propagates the `sourceTag` to the callback associated with `timeoutSpec`, if provided. Also, since
     * it might invoke [doThrow] and [stop], ot propagates the `sourceTag` to them as well. Finally, it propagates it
     * also to [doWait].
     *
     * @param timeoutSpec The timeout specifications. If it is `null`, then the wait will not be timed-out. If it
     * defines a callback, then it will be invoked if the timeout occurs.
     * @param sourceTag An identifier that will be propagated to the timeout-based lambda function  associated with this
     * method, or to the [onErrorCallbacks] in case of an exception. By default, it is empty.
     * @return `true` if the asynchronous service computation launched with [computeAsync] successfully finished;
     * `false` otherwise.
     */
    final override suspend fun wait(timeoutSpec: Timeout?, sourceTag: String): Boolean {

        // TODO document
        fun catchException(ex: Exception, timeoutSpec: Timeout?, scope: CoroutineScope?) {
            if (ex is TimeoutCancellationException && timeoutSpec != null) {
                // If timeout occurred stop the service and invoke the callback.
                logWarn("Service has been waited for too long (timeout!).")
                stop(sourceTag)
                timeoutSpec.invokeCallback(sourceTag)
            } else {
                // Manage other exception, such as cancellation, etc.
                if (doThrow(ex, ErrorSource.WAITING, sourceTag, scope) == true) throw ex
            }
        }


        // Check if the wait should start or not, i.e., if the service is computing or not.
        if (!checkComputingState(true, "cannot wait since there is no computations")) {
            Thread.currentThread().stackTrace.forEach { println(it) }
            return false
        }

        val waitingTime = measureTime {

            if (timeoutSpec != null) {

                // Wait with timeout.
                logDebug("Service is waiting for computation to finish with timeout '{}'...", timeoutSpec)
                withTimeout(timeoutSpec.timeout) {
                    try {
                        doWait(sourceTag)
                    } catch (ex: Exception) { // In case of errors call `doThrow` with a `scope = this`, i.e., not blocking
                        catchException(ex, timeoutSpec, this)
                    }
                }

            } else {

                // Wait without timeout.
                logDebug("Service is waiting for computation to finish...")
                try {
                    doWait(sourceTag)
                } catch (ex: Exception) {
                    catchException(ex, null, null)
                }
            }
        }
        logInfo("Service finished waiting (it waited for {}).", waitingTime)
        return true
    }


    /**
     * The implementation of the [wait] logic, but this method is not invoked if [wait] returns `false`, and it can
     * be cancelled by [wait] in case of timeout.
     *
     * This method only waits for the service computation (associated with the [computingJob]) to finish. Note that
     * [timeoutJob] ends its computation implicitly when [computingJob] ends (see [doCheckTimeoutAsync] for more).
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.WAITING].
     *
     * @param sourceTag The source tag as given to the [wait] method by the class that wants to wait for the computation
     * to end.
     */
    protected open suspend fun doWait(sourceTag: String) = computingJob?.join()


    /**
     * Immediately stops the service computation (associated with the [computingJob]) if it is computing. This method
     * does nothing if the service is not running, i,e., if [checkComputingState], with `true` input parameter,
     * returns `false`.
     *
     * This method does not stop the timeout checker (associated with the [timeoutJob]), since it implicitly ends when
     * [computingJob] is completed (see [doCheckTimeoutAsync] for more).
     *
     * This method is in charge to reset [isComputing], and to set [computingJob], [timeoutJob] and [timeoutStart] to
     * `null`.
     *
     * Note that the actual stop implementation is defined by [doStop], and you can override it to change its behaviour.
     * This function propagates to the [doStop] method the provided `sourceTag`.
     *
     * Also, note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.STOPPING]. If a not ignored exception occurs, [doThrow] calls the
     * [onErrorCallbacks], which receives the given `sourceTag` as input parameter.
     *
     * @param sourceTag An identifier that will be propagated to [doStop], and to the [onErrorCallbacks] in case of an
     * exception. By default, it is empty.
     * @return `true` if the asynchronous service computation launched with [computeAsync] successfully stopped; `false`
     * otherwise.
     */
    final override fun stop(sourceTag: String): Boolean {
        // Check if there is something to stop.
        if (!checkComputingState(true, "has already stop computing")) return false

        logDebug("Service is stopping computation...")
        try {
            val stoppingTime = measureTime {
                // Call the actual stopping logic.
                doStop(sourceTag)

                // Reset all properties
                isComputing.set(false)
                computingJob = null
                timeoutJob = null
                timeoutStart = null
            }
            logInfo("Service computation has been stopped (it took {}).", stoppingTime)

        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.STOPPING, sourceTag, null) == true) throw ex
        }

        return true
    }


    /**
     * The implementation of the stopping logic, but this method is not invoked if [stop] returns `false`.
     *
     * This method only cancels the service computation (associated with the [computingJob]) to finish. Note that
     * [timeoutJob] ends its computation implicitly when [computingJob] ends (see [doCheckTimeoutAsync] for more).
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.STOPPING].
     *
     * @param sourceTag An identifier given to [stop] from the class that wants to stop the computation. By default, it
     * is an empty string.
     */
    protected open fun doStop(sourceTag: String) = computingJob?.cancel()


    /**
     * A shorthand to call `doThrow(ServiceError(throwable, experiencedError))`, see [doThrow] for more.
     *
     * @param throwable The exception to handle
     * @param errorSource An enumerator that identifies the calling function and relative logs, see
     * [ErrorSource] for more.
     * @param sourceTag An identifier given by the class that uses this service. It is not used by this class, but just
     * propagated to the lambda function while invoking the [onErrorCallbacks].
     * @return It returns `true` if the exception should be propagated with `throw`, `false` if it should only be
     * logged, and `null` if the exception should be ignored.
     */
    protected fun doThrow(throwable: Throwable, errorSource: ErrorSource, sourceTag: String, scope: CoroutineScope?) =
        doThrow(ServiceError(throwable, errorSource, sourceTag), scope) // TODO document and set deafult scope to null


    /**
     * React to exceptions that occur during the computation of this service. This function is called by [computeAsync],
     * [wait] and [stop], as well as [ReusableServiceInterface.activate] and [ReusableServiceInterface.deactivate]; if
     * the derived class implements such an interface.
     *
     * The implementation of this function is such to
     *  - ignore cancellation exception,
     *  - log other exceptions, and
     *  - do not propagate any exceptions with `throw`.
     * If an exception is not ignored, then this class invokes the [onErrorCallbacks] by passing to them the
     * `serviceError` input parameter. In addition, the `serviceError` parameter will encode the `sourceTag` as provided
     * to this class. The `sourceTag` is not used by this class, but just propagated to the lambda function while
     * invoking the [onErrorCallbacks] (and to other callbacks in general, including on result callbacks, and timeout).
     *
     * If you are explicitly catching exceptions, remember to throw again such exception for make the error callbacks
     * aware of it.
     *
     * @param serviceError A data container class that encompass the `throwable` to handle, and the [ErrorSource],
     * which identify the function producing the error.
     * @return It returns `true` if the exception should be propagated with `throw`, `false` if it should only be
     * logged, and `null` if the exception should be ignored.
     */
    protected open fun doThrow(serviceError: ServiceError, scope: CoroutineScope?): Boolean? {
        val throwable = serviceError.throwable
        val experiencedErrorLog = serviceError.source.errorLog

        // Do not react to cancellation exceptions
        if (throwable is CancellationException || throwable.cause is CancellationException || throwable is SdkCancellationException) {
            logTrace("Service cancelled with source '{}'", serviceError.source)
            return null
        }

        // Invoke callbacks
        onErrorCallbacks.invoke(serviceError, scope) // TODO document and set default scope to null

        // Log the type of error
        logError("Service experienced '{}'.", experiencedErrorLog, throwable)
        return false

        // Never returns `true`, i.e., never propagate the error with `throw`.
    }


    /**
     * Cancelling the coroutine [scope]. After this operation the service cannot be computed again, and a new service
     * instance is required.
     */
    override fun cancelScope() {
        if (isComputing.get()) {
            logWarn("Cannot cancel the Service's scope since it is still computing.")
            return
        }

        val message = "Cancelling scope $scope."
        scope.cancel(message)
        logInfo(message)
        isScopeCancelled = true
    }
}



/**
 * An extension of [ServiceInterface] that allows initialising and closing resources only once, and not at each
 * asynchronous service's computation. Therefore, it can be reused to perform several computations.
 *
 * This interface defines the [activate] and [deactivate] functions that initialise and close resources respectively.
 * Note that if a service is not active, then it cannon perform computations with [computeAsync] (as well as [stop] or
 * [wait]); see [ServiceInterface] for more.
 *
 * This service is designed to be used with [CallbackManagerInterface] to provide a callback mechanisms (e.g., on error
 * or result), and with [FrequentTimeout] and [Timeout] to provide a timeout mechanisms. It also provides mechanism to
 * propagate to the callbacks and timeouts lambda-functions an identifier from the class that requested the asynchronous
 * service. Such identifier is the `sourceTag`, which is a String set to empty by default. Note that `sourceTag` is not
 * used by this class (so it can have any value), but just propagated among callbacks and functions.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isActive The flag indicating if the service has initialised its resources. Thus, it can perform
 * computations. It is set by [activate] and reset by [deactivate].
 * @property isComputing The flag indicating if the service is currently computing or not, in asynchronous manner. It is
 * set by [computeAsync] and reset by [stop] and [wait]. This property is inherited from [ServiceInterface].
 *
 * @see Service
 * @see ReusableServiceInterface
 * @see ReusableService
 * @see CallbackManagerInterface
 * @see FrequentTimeout
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface ReusableServiceInterface<I> : ServiceInterface<I> {

    // See documentation above.
    val isActive: AtomicBoolean

    /**
     * Checks if the service is in a state that allows to run [activate].
     * @return `true` if the service state allows for activation; `false` otherwise.
     */
    fun canActivate(): Boolean


    /**
     * Initialise service's resources and set the [isActive] flag.
     * Note that if you invoke [cancelScope] then the service cannot be activated again.
     *
     * @param sourceTag An identifier that will be propagated among functions, but it is not used by this class. By
     * default, it is empty.
     * @return `true` if the service's resources were initialised successfully.
     */
    fun activate(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG): Boolean


    /**
     * Checks if the service is in a state that allows to run [deactivate].
     * @return `true` if the service state allows for deactivation; `false` otherwise.
     */
    fun canDeactivate(): Boolean


    /**
     * Close service's resources and reset the [isActive] flag.
     * Note that if you invoke [cancelScope] then the service cannot be activated again.
     *
     * @param sourceTag An identifier that will be propagated among functions, but it is not used by this class. By
     * default, it is empty.
     * @return `true` if the service's resources were closed successfully.
     */
    fun deactivate(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG): Boolean
}



/**
 * An extension of [Service] based on [ReusableServiceInterface] that allows initialising and closing resources only
 * once, and not at each asynchronous service's computation. Therefore, it can be reused to perform several computations.
 *
 * To use this class you should:
 *  1. Use the [activate] method to initialise service's resources.
 *  2. Use the [computeAsync] method to perform computations (you can set a timeout with relative callback for such a
 *     computation).
 *  3. Optionally, Use the [stop] method to cancel the computation, or the [wait] method, which also allows defining a
 *     timeout with relative callback.
 *  4. Eventually, start a new computation with [computeAsync] and exploit the [stop] or [wait] method.
 *  5. Finally, use the [deactivate] method to close service's resources
 *  6. Eventually, use [activate] again the service and go through all previous steps.
 *  7. Use [cancelScope], after this the service cannot be used anymore. If you want to use it again you will require a
 *     new instance.
 * Note that step 2 cannot be performed if the service is not active, step 3 cannot be performed if the service is not
 * computing (step 2), and step 5 cannot be performed if the service is still computing.
 *
 * To implement your custom service you should extend this class and override the [doActivate], [doDeactivate], and
 * [doComputeAsync]. Such a method should only contain custom logic, since service states, its possible exceptions, and
 * logging are managed by this class. Eventually you can further customize the behaviour of this class by extending the
 * [doWait], [doStop], and [doThrow] methods, but always remember to call `super` to avoid unexpected behaviour.
 *
 * Note that the [ServiceInterface] allow defining callbacks involving: on results, on error and timeouts. The classes
 * tha calls the service's functionalities can specify a `sourceTag` that is propagated to the relative callbacks. See
 * also [Service] for more info.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isActive The flag indicating if the service has initialised its resources. Thus, it can perform
 * computations. It is set by [activate] and reset by [deactivate]. If the service is not active, then [isComputing]
 * will be false, and [computingJob], [timeoutJob], and [timeoutStart] will be `null`.
 * @property isComputing The flag indicating if the service is currently computing or not. See [Service.isComputing]
 * for more.
 * @property scope A `private` property that defines where the coroutines will run. See [Service.scope] for more.
 * @property computingJob A `private` property that represents the coroutine job for the computation. See
 * [Service.computingJob] for more.
 * @property timeoutJob A `private` property that represents the coroutine job for the timeout watcher associated with
 * [computingJob]. See [Service.timeoutJob] for more.
 * @property timeoutStart A `private` property that represents the start time of the timeout policy. See
 * [Service.timeoutStart] for more.
 * @property onErrorCallbacks A set of callbacks that are called in case of exceptions. See [Service.onErrorCallbacks]
 * for more.
 * @property isScopeCancelled A `private` property that is set to `true` when [cancelScope] is invoked. When the scope
 * is cancelled, this class cannot be used anymore to perform further computation.
 *
 * @constructor Requires the [scope], as defined by [Service].
 *
 * @see Service
 * @see ReusableServiceInterface
 * @see ReusableService
 * @see CallbackManagerInterface
 * @see FrequentTimeout
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class ReusableService<I>(scope: CoroutineScope) : ReusableServiceInterface<I>, Service<I>(scope){

    // See documentation above.
    @Volatile
    final override var isActive = AtomicBoolean(false)
        private set


    /**
     * This method extends [checkComputingState] to also check if  the service is active. See [checkComputingState]
     * for more.
     *
     * @param expectedComputingState The expected value of the [isComputing] flag.
     * @param warningLog A string used only for logging purposes when [isComputing] is not as expected.
     * @return `true` if [isComputing] is as expected, `false` otherwise. If [isActive] is `false`, it will always
     * return `false`.
     */
    final override fun checkComputingState(expectedComputingState: Boolean, warningLog: String): Boolean {
        if (!isActive.get()) {
            logWarn("Service cannot computeAsync since it is not active.")
            return false
        }
        return super.checkComputingState(expectedComputingState, warningLog)
    }


    /**
     * Handles the [isActive] flag for the [activate], and [deactivate] methods.
     *
     * It is used to allow starting, waiting, and stopping the computation only when the service has been activated,
     * and to deactivate it only when it is not computing. In other words, it returns `true` if [isActive] is equal to
     * the `expectedActiveState` given as input. Otherwise, it returns `false` and logs a warning with a message given
     * as input through the `errorLog` parameter.
     *
     * @param expectedActiveState The expected value of the [isActive] flag.
     * @param warningLog A string used only for logging purposes when [isActive] is not as expected.
     * @return `true` if [isActive] is as expected, `false` otherwise.
     */
    private fun checkActiveState(expectedActiveState: Boolean,  warningLog: String): Boolean {
        // If it is called from `deactivate` check if it is computing.
        if (isActive.get()) {
            if (isComputing.get()) {
                logWarn("Service cannot be deactivated since it is still computing.")
                return false
            }
        }

        // Deny if it is active and wants to re-activate, or if it is not active and wants to re-deactivate.
        if (isActive.get() != expectedActiveState) {
            logWarn("Service {}.", warningLog)
            return false
        }

        // Allow activation or deactivation.
        return true
    }


    /**
     * Check if the service can run [activate].
     * @return `true` if [isActive] and [isScopeCancelled] are both `false`. Otherwise, returns `false`.
     */
    override fun canActivate(): Boolean = !isActive.get() && !isScopeCancelled


    /**
     * Initialise service's resources and set the [isActive] flag. This method does nothing if the service has already
     * been activated. This method only manages service state, possible exception and logging, but the current
     * activation is implemented in the [doActivate] method, which is called by this method, and it should be
     * implemented by derived classes. Note that [doActivate] will receive the `resourceTag` as given to this method.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.ACTIVATING]. In case of an exception not ignored by [doThrow], the
     * [onErrorCallbacks] will be invoked, and the `sourceTag` will be provided to them.
     *
     * Note that if you invoke [cancelScope] then the service cannot be activated again.
     *
     * @param sourceTag An identifier that will be propagated to [doActivate], and to the [onErrorCallbacks] in case of
     * an exception. By default, it is empty.
     * @return `true` if the service has been successfully activated, `false` otherwise.
     */
    final override fun activate(sourceTag: String): Boolean {

        // Check if the scope has been cancelled.
        if (isScopeCancelled) {
            logWarn("Service cannot be activated since the scope has been cancelled.")
            return false
        }

        // Deny activation if it already activated.
        if (!checkActiveState(false, "has already been activated")) return false

        // Activate the service and, if the activation is successfully, manage the `isActive` state.
        try {
            logDebug("Service is activating...")
            val activationTime = measureTime {
                doActivate(sourceTag)
                isActive.set(true)
            }
            logInfo("Service has been activated (took: {}).", activationTime)
            return true
        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.ACTIVATING, sourceTag, null) == true) throw ex
        }

        logWarn("Service did not activated!")
        return false
    }


    /**
     * This method implements the instructions for initialising service's resources. It is called by [activate], which
     * is in charge to manage service state, possible exception and logging. Thus, this function only cares about the
     * service initialization.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.ACTIVATING]. In case of an exception not ignored by [doThrow], the
     * [onErrorCallbacks] will be invoked, and the `sourceTag` will be provided to them.
     *
     * @param sourceTag An identifier propagated from [activate], as given by the class that wants to activate this
     * service.
     */
    protected abstract fun doActivate(sourceTag: String)


    /**
     * Check if the service can run [deactivate].
     * @return `true` if [isActive] is `true` and [isComputing] is `false`. Otherwise, returns `false`.
     */
    override fun canDeactivate(): Boolean = isActive.get() && !isComputing.get()


    /**
     * Close service's resources and reset the [isActive] flag. This method does nothing if the service has already
     * been deactivated. This method only manages service state, possible exception and logging, but the current
     * deactivation is implemented in the [doDeactivate] method, which is called by this method, and it should be
     * implemented by derived classes. Note that [doDeactivate] will receive the `resourceTag` as given to this method.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.DEACTIVATING]. In case of an exception not ignored by [doThrow], the
     * [onErrorCallbacks] will be invoked, and the `sourceTag` will be provided to them.
     *
     * @param sourceTag An identifier that will be propagated to [doDeactivate], and to the [onErrorCallbacks] in case
     * of an exception. By default, it is empty.
     * @return `true` if the service has been successfully deactivated, `false` otherwise.
     */
    final override fun deactivate(sourceTag: String): Boolean {

        // Deny deactivation if it is already deactivate or if it is currently computing.
        if (!checkActiveState(true, "has already been deactivated")) return false

        // Deactivate the service and, if the deactivation is successfully, manage the `isActive` state.
        try{
            logDebug("Service is deactivating...")
            val deactivationTime = measureTime {
                doDeactivate(sourceTag)
                isActive.set(false)
            }
            logInfo("Service has been deactivated (took: {}).", deactivationTime)
            return true
        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.DEACTIVATING, sourceTag, null) == true) throw ex
        }

        logWarn("Service did not deactivated!")
        return false
    }


    /**
     * This method implements the instructions for closing service's resources. It is called by [deactivate], which
     * is in charge to manage service state, possible exception and logging. Thus, this function only cares about the
     * releasing the sources that the service initialized with [doActivate].
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.DEACTIVATING]. In case of an exception not ignored by [doThrow], the
     * [onErrorCallbacks] will be invoked, and the `sourceTag` will be provided to them.
     *
     * @param sourceTag An identifier propagated from [deactivate], as given by the class that wants to deactivate this
     * service.
     * @return `true` if the service has been successfully activated, `false` otherwise.
     */
    protected abstract fun doDeactivate(sourceTag: String)

}



/**
 * The base definition of a callbacks manager, which collects lambda functions in a map, and allow invoking them. This
 * class should store lambda functions on map with a string-based identifier for each callback. Such a manager is
 * usually used by an implementation or extension of the [ServiceInterface].
 *
 * The input type to the callback must be an implementation of [CallbackInput], which defines a `sourceTag` that is used
 * for propagating data given while invoking the features of a [ServiceInterface] to the callbacks. Note that the
 * `sourceTag` is not processed neither by this class nor by [ServiceInterface]. The `sourceTag` it designed in such a
 * way that the classes that exploit the functionalities of a [ServiceInterface] (thus, might generate callbacks), can
 * pass data to the callbacks. If you want to use an empty input, you can rely on the [SimpleCallbackInput].
 *
 * @param I The callbacks input.
 *
 * @see CallbackManager
 * @see CallbackManagerInterface
 * @see SimpleCallbackInput
 * @see ServiceInterface
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface CallbackManagerInterface<I: CallbackInput>  {
    // It does not extend LoggableInterface, since it logs error with the logger of delegate classes.

    /**
     * Adds a new callback.
     * @param callback The callback implementation to be added.
     * @return The string-based identifier of the callback that has been added.
     */
    fun add(callback: suspend ((I) -> Unit)): String


    /**
     * Removes a callback.
     * @param callback The callback implementation to be removed.
     */
    fun remove(callback: suspend ((I) -> Unit))


    /**
     * Removes a callback given its identifier.
     * @param callbackId The callback identifier to be removed.
     */
    fun remove(callbackId: String)


    /**  Removes all the callbacks. */
    fun clear()


    /**
     * Calls all the added callbacks with the given input parameters and returns associated results.
     * @param callbackInput The input data for the callbacks.
     */
    fun invoke(callbackInput: I, scope: CoroutineScope?) // TODO document scope and set deafult to null


    /**
     * Returns the callbacks associated with the given identifier.
     * @param callbackIdentifier The string-based identifier of the callbacks.
     * @return The callbacks associated with the given identifier, or `null` if it does not exist.
     */
    fun getCallbacks(callbackIdentifier: String): (suspend ((I) -> Unit))?
}



/**
 * The base definition of the input to the callback managed by [CallbackManager]. It only defines a property that is
 * neither processed by the [CallbackManager] nor by [ServiceInterface], but it is propagated in such a way that the
 * classes that exploit the functionalities of a [ServiceInterface] (thus, might generate callbacks), can pass data to
 * the callbacks.
 *
 * @property sourceTag An identifier that will be propagated from the functionalities of [ServiceInterface] to the
 * callbacks. By default, it is empty.
 *
 * @see CallbackManager
 * @see SimpleCallbackInput
 * @see ServiceInterface
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface CallbackInput {
    val sourceTag: String

    // TODO document
    fun copy(): CallbackInput
}



/**
 * The simplest implementation of the [CallbackInput] interface. It can be used when the callbacks defined through the
 * [CallbackManager] do not require data.
 *
 * It only implements a property that is neither processed by the [CallbackManager] nor by [ServiceInterface], but it is
 * propagated in such a way that the classes that exploit the functionalities of a [ServiceInterface] (thus, might
 * generate callbacks), can pass data to the callbacks.
 *
 * @property sourceTag An identifier that will be propagated from the functionalities of [ServiceInterface] to the
 * callbacks. By default, it is empty.
 *
 * @see CallbackManager
 * @see SimpleCallbackInput
 * @see ServiceInterface
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
open class SimpleCallbackInput(override val sourceTag: String) : CallbackInput {

    override fun copy(): CallbackInput = SimpleCallbackInput(this.sourceTag)
}



/**
 * An implementation of the [CallbackManagerInterface] that collects lambda functions in a map where keys identifies
 * callback based on where they are implemented, i.e., based on [getCallbackIdentifier]. This class is usually used by
 * an implementation or extension of the [ServiceInterface].
 *
 * The input type to the callback must be an implementation of [CallbackInput], which defines a `sourceTag` that is used
 * for propagating data given while invoking the features of a [ServiceInterface] to the callbacks. Note that the
 * `sourceTag` is not processed neither by this class nor by [ServiceInterface]. The `sourceTag` it designed in such a
 * way that the classes that exploit the functionalities of a [ServiceInterface] (thus, might generate callbacks), can
 * pass data to the callbacks. If you want to use an empty input, you can rely on the [SimpleCallbackInput].
 *
 * @param I The callbacks input.
 *
 * @property logger The logger used to log errors, which should be given by the [Loggable] class using this class.
 * @property serviceName The name of the service that uses this class. If it is set to `null`, then
 * `javaClass.simpleName` will be used. Default value is `null`.
 * @property callbacks The map that stores the callbacks with relative identifier. However, this property is `private`.
 *
 * @see CallbackManager
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class CallbackManager<I: CallbackInput>(private val logger: CentralizedLogger) : CallbackManagerInterface<I> {
    // It does not extend Loggable, since it logs error with the logger of delegate classes.

    // See documentation above.
    private val serviceName = logger.logsFor

    // See documentation above.
    private val callbacks: MutableMap<String, suspend (I) -> Unit> = mutableMapOf() // TODO change it to concurrent hashmap


    /**
     * Returns a callback stored in the [callbacks] map given its identifier, which is based on [getCallbackIdentifier].
     * If such a callback does not exist, it returns `null`.
     *
     * @param callbackIdentifier The callback identifier
     * @return The callback associated with the given identifier within the [callbackIdentifier] map, or `null` if it
     * does not exist.
     */
    override fun getCallbacks(callbackIdentifier: String): (suspend ((I) -> Unit))? {
        synchronized(callbacks) {
            return callbacks[callbackIdentifier]
        }
    }


    /**
     * Add a callback to the [callbacks] map. If a callback with the same identifier already exists, it is replaced by
     * the new one. The callback identifier is based on [getCallbackIdentifier].
     *
     * @param callback The callback to add.
     * @return The identifier of the callback that has been added.
     */
    override fun add(callback: suspend ((I) -> Unit)): String{
        synchronized(callbacks) {
            val callbackId = getCallbackIdentifier(callback)

            if(callbacks.put(callbackId, callback) != null)
                // TODO add log name for discriminating the type of callback (e.g., onResult, onError, etc.)
                logger.warn("Replacing callback (with ID: '{}') for service '{}'.", callbackId, serviceName)
            else
                logger.debug("Adding callback (with ID: '{}') for service '{}'.", callbackId, serviceName)

            return callbackId
        }
    }


    /**
     * Removes a callback from the [callbacks] map, which identifier is based on [getCallbackIdentifier]. If the
     * callback does not exist, it logs a warning.
     *
     * @param callback The callback to remove.
     */
    override fun remove(callback: suspend ((I) -> Unit)) {
        val callbackId = getCallbackIdentifier(callback)
        remove(callbackId)
    }


    /**
     * Removes a callback from the [callbacks] map given its identifier. If the callback does not exist, it logs a
     * warning.
     *
     * @param callbackId The identifier of the callback to remove.
     */
    override fun remove(callbackId: String) {
        synchronized(callbacks) {
            if (callbacks.remove(callbackId) == null) {
                logger.warn("Callback (with ID: '{}') does not exist, and cannot be removed, from service '{}'.",
                    callbackId, serviceName)
            } else {
                logger.debug("Callback (with ID: '{}') for service '{}'.", callbackId, serviceName)
            }
        }
    }

    /**  Removes all the callbacks from the [callbacks] map. */
    override fun clear() {
        callbacks.clear()
    }



    /**
     * Calls all the callbacks added in the [callbacks] map, with the given input parameters and returns associated
     * results. It measures computing time for each callback, and produce relative logs.
     *
     * @param callbackInput The input data for the callbacks.
     */
    override fun invoke(callbackInput: I, scope: CoroutineScope?) { // TODO document scope also elsewhere

        val callbackInput: I = callbackInput.copy() as I

        // Todo document
        suspend fun invokeCallback(callbackId: String, callback: suspend ((I) -> Unit), input: I, computationTimeMap: MutableMap<String, Duration>) {
            val computationTime = measureTime {
                callback(input) // Actually Invoke the callback.
            }
            computationTimeMap[callbackId] = computationTime
        }



        //synchronized(callbacks) { TODO use mutex
            if(callbacks.isEmpty()) {
                logger.debug("Service '{}' does not have any callbacks to be invoked.", serviceName)
                return
            }

            // Invoke the function and measure computation time.
            logger.debug("Service '{}' is invoking callbacks...", serviceName)
            val computationTimeMap = mutableMapOf<String, Duration>()

            //val jobs = mutableSetOf<Job>()
            /*
            if (scope == null) {
                val totalComputationTime = measureTime {
                    callbacks.forEach {
                        it.key to { // TODO document
                            // If the scope is `null`, run on the current thread while blocking it.
                            runBlocking {
                                invokeCallback(it.key, it.value, callbackInput, computationTimeMap)
                            }
                        }
                    }
                }
                if (logger.isInfoEnabled()) {
                    val inputStr = Utils.escapeCharacters(callbackInput)
                    val logMsg = "Service $serviceName invokes ${callbacks.size} callback(s) with input: '$inputStr' " +
                            "(computation times: $computationTimeMap, total computation time: $totalComputationTime )."
                    logger.info(logMsg)
                    // TODO computationTimeMap is empty if we do not wait for the launched job within the `scope` to finish.
                }
            } else {
                scope.launch {
                    val jobs = mutableSetOf<Job>()
                    val totalComputationTime = measureTime {
                        callbacks.forEach {
                            it.key to { // TODO document
                                // If the scope is `null`, run on the current thread while blocking it.
                                val job = launch {
                                    invokeCallback(it.key, it.value, callbackInput, computationTimeMap)
                                }
                                jobs.add(job)
                            }
                        }
                        jobs.forEach{it.join()}
                    }
                    if (logger.isInfoEnabled()) {
                        val inputStr = Utils.escapeCharacters(callbackInput)
                        val logMsg = "Service $serviceName invokes ${callbacks.size} callback(s) with input: '$inputStr' " +
                                "(computation times: $computationTimeMap, total computation time: $totalComputationTime )."
                        logger.info(logMsg)
                        // TODO computationTimeMap is empty if we do not wait for the launched job within the `scope` to finish.
                    }
                }
            }
            */


            val totalComputationTime = measureTime {
                callbacks.forEach {
                    // TODO document
                    // If the scope is not `null`, run on a child thread without blocking the current thread.
                    // If the parent thread is cancelled, then this child thread is cancelled as well.
                    scope?.launch {
                        try {
                            invokeCallback(it.key, it.value, callbackInput, computationTimeMap)
                        } catch (ex: Exception) {
                            println("EXCEPTION IN CALLBACK: $ex")
                            // TODO connect to doThrow
                        }

                    }
                        // If the scope is `null`, run on the current thread while blocking it.
                        // This runs on doThrow` on main thread, e.g., activation, deactivation, stop, etc.
                        ?: runBlocking {
                            invokeCallback(it.key, it.value, callbackInput, computationTimeMap)
                        }

                }
            }

            if (logger.isInfoEnabled()) {
                val inputStr = Utils.escapeCharacters(callbackInput)

                // Required to avoid tempting to concatenate string when the map is only empty, i.e., '{}'.
                val computationTimeLog = if (computationTimeMap.isEmpty())
                    "{--empty--}" else computationTimeMap.toString()

                val logMsg = "Service $serviceName invoked ${callbacks.size} callback(s) with input: '$inputStr' " +
                        "(computation times: $computationTimeLog, total computation time: $totalComputationTime )."
                logger.info(logMsg)
                // TODO computationTimeMap is empty if we do not wait for the launched job within the `scope` to finish.
            }
        //}
    }


    /*
    override fun invoke(callbackInput: I, scope: CoroutineScope?) {
        // TODO remove scope
        invoke(callbackInput) { callback, input ->
            runBlocking {
                measureTime {
                    callback(input) // Actually Invoke the callback.
                }
            }
        }
    }

    override suspend fun invokeSuspended(callbackInput: I) {
        invoke(callbackInput) { callback, input ->
            measureTime {
                callback(input) // Actually Invoke the callback.
            }
        }
    }

    private fun invoke(callbackInput: I, operator: (suspend ((I)->Unit), I) -> Duration) {
        //synchronized(callbacks) { TODO use mutex
        if(callbacks.isEmpty()) {
            logger.warn("Service '{}' does not have any callbacks to be invoked.", serviceName)
            return
        }

        // Invoke the function and measure computation time.
        logger.debug("Service '{}' is invoking callbacks...", serviceName)
        val computationTimeMap = mutableMapOf<String, Duration>()

        val totalComputationTime = measureTime {
            callbacks.forEach {
                it.key to { // TODO document
                    val duration = operator(it.value, callbackInput) // Actually Invoke the callback.
                    computationTimeMap[it.key] = duration
                }
            }
        }

        if (logger.isInfoEnabled()) {
            val inputStr = Utils.escapeCharacters(callbackInput)
            val logMsg = "Service $serviceName invokes ${callbacks.size} callback(s) with input: '$inputStr' " +
                    "(computation times: $computationTimeMap, total computation time: $totalComputationTime )."
            logger.info(logMsg)
            // TODO computationTimeMap is empty if we do not wait for the launched job within the `scope` to finish.
        }
    }
     */


    companion object {

        /**
         * Returns the string-based identifier of the given callback. Such an identifier it is based on the location
         * where the callback is implemented within the code, and the name of the class that uses it.
         * @param callback The callback implementation.
         * @return The string-based identifier of the given callback.
         */
        fun <I> getCallbackIdentifier(callback: suspend (((I) -> Unit))): String {
            //val location = callback.javaClass.protectionDomain?.codeSource?.location?.toString() ?: "???"
            val name = callback.javaClass.name //+ callback.javaClass.hashCode().toString()
            return name // "$name@$location"
        }
    }
}
