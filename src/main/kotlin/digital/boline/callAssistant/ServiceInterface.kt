package digital.boline.callAssistant

import digital.boline.callAssistant.Timeout.Companion.toString
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
 * @see digital.boline.callAssistant.llm.LlmInteract
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface ServiceInterface<I> : LoggableInterface {

    // See documentation above.
    val isComputing: AtomicBoolean


    /**
     * Starts the service computation asynchronously with an optional timeout.
     *
     * @param input The input data required to compute the service.
     * @param timeoutSpec The specification of the timeout (see [FrequentTimeout] for more). If it is `null` (which is
     * the default value), not timeout policy will be applied.
     * @return `true` if the service computation was started successfully.
     */
    fun computeAsync(input: I, timeoutSpec: FrequentTimeout? = null): Boolean


    /**
     * Waits for the service computation started with [computeAsync] to complete. Optionally, a timeout with relative
     * callback can be specified.
     *
     * @param timeoutSpec The specification of the timeout (see [Timeout] for more). If it is `null` (which is the
     * default value), no timeout policy will be applied.
     * @return `true` if the service computation was completed successfully.
     */
    suspend fun wait(timeoutSpec: Timeout? = null): Boolean


    /**
     * Immediately stops the current service computation.
     * @return `true` if the service computation was stopped successfully.
     */
    fun stop(): Boolean
}



/**
 * Specification of the timeout policy implement by [ServiceInterface.wait].
 *
 * Note that this class does not implement the [equals] and [hashCode] methods.
 *
 * @property timeout The number of milliseconds after which the computation is stopped since timeout is considered
 * expired. Default value is [DEFAULT_TIMEOUT].
 * @property callback A lambda function that is called when the timeout expires. Such a function does not have input
 * parameters and do return nothing. Default value is [DEFAULT_CALLBACK]. If it is set to `null`, the callback is
 * ignored.
 *
 * @see ServiceInterface
 * @see Service
 * @see FrequentTimeout
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
open class Timeout(
    // See property documentation above.
    val timeout: Long = DEFAULT_TIMEOUT,
    val callback: (() -> Unit)? = DEFAULT_CALLBACK)
{
    /**
     * Invokes the callback function associated with this timeout specification. If [callback] is `null`, then this
     * function does nothing.
     */
    fun invokeCallback() {
        callback?.let { it() }
    }

    /**
     * Returns a string representation of this timeout specification by logging all its properties.
     * @return A string representation of this timeout specification.
     */
    override fun toString() = "{${timeoutToString(timeout)}, ${callbackToString(callback)}}"

    companion object {

        /** The default timeout value, which is set to 20 seconds. */
        @JvmStatic
        protected val DEFAULT_TIMEOUT: Long = 20_000 // TODO set it as environmental variable and make an object with all environmental-based configuration.

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
        protected fun callbackToString(callback: (() -> Unit)?) =
            if (callback != null) "with callback" else "without callback"
    }
}



/**
 * Specification of the timeout policy implement by [ServiceInterface.computeAsync]. This implementation is used when
 * the starting instance to compute the timeout can be reset.
 *
 * Note that this class does not implement the [equals] and [hashCode] methods.
 *
 * @property timeout The number of milliseconds after which the computation is stopped since timeout is considered
 * expired. Default value is [Timeout.DEFAULT_TIMEOUT].
 * @property checkPeriod The delay in milliseconds between each checks of timeout expiration. Default value is
 * [DEFAULT_CHECK_PERIOD].
 * @property callback A lambda function that is called when the timeout expires. Such a function does not have input
 * parameters and do return nothing. Default value is [Timeout.DEFAULT_CALLBACK]. If it is set to `null`, the callback
 * ignored.
 *
 * @see ServiceInterface
 * @see Service
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class FrequentTimeout (
    timeout: Long = DEFAULT_TIMEOUT,
    val checkPeriod: Long = DEFAULT_CHECK_PERIOD,
    callback: (() -> Unit)? = DEFAULT_CALLBACK) : Timeout(timeout, callback)
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
 * which manages all try-catch block of [Service] and [ReusableService]. See [Service.doThrow] for
 * more.
 *
 * @property throwable The exception to handle
 * @property source An enumerator that identifies the calling function and relative logs, see
 * [ErrorSource] for more.
 *
 * @see Service
 * @see ReusableService
 * @see ErrorSource
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class ServiceError(val throwable: Throwable, val source: ErrorSource)



/**
 * An abstract class that implements [ServiceInterface] and provides a base implementation for asynchronous services.
 *
 * This class manages the [isComputing] flag by implementing the methods defined by the [ServiceInterface]. It also
 * manages logs and exception try-catch. In particular, it defines the abstract method [doComputeAsync], and it provides
 * further customizable methods [doWait], [doStop], and [doThrow]. Note that these methods should only implement the
 * actual logic since logging, exceptions and service states are handled by this class. This class also implements the
 * timeout policies and the coroutine-based methods for waiting or stopping asynchronous jobs.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isComputing The flag indicating if the service is currently computing or not, in asynchronous manner. It is
 * set by [computeAsync] and reset by [stop] and [wait].
 * @property serviceName A `protected` property that represents the name of this service, which is used for logging
 * purposes.
 * @property scope A `private` property that defines where the coroutines used by this service will run. It is
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
abstract class Service<I>(private val scope: CoroutineScope): ServiceInterface<I>, Loggable() {

    // See documentation above.
    protected val serviceName: String = this.javaClass.simpleName

    // See documentation above.
    private var computingJob: Job? = null

    // See documentation above.
    private var timeoutJob: Job? = null

    // See documentation above.
    private var timeoutStart: AtomicLong? = null

    // See documentation above.
    val onErrorCallbacks = CallbackManager<ServiceError, Unit>(logger)

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
     * @param expectedComputingState The expected value of the [isComputing] flag.
     * @param warningLog A string used only for logging purposes when [isComputing] is not as expected.
     * @return `true` if [isComputing] is as expected, `false` otherwise.
     */
    protected open fun checkComputingState(expectedComputingState: Boolean, warningLog: String): Boolean {
        if (isComputing.get() != expectedComputingState) {
            logWarn("Service '{}' {}.", serviceName, warningLog)
            return false
        }
        return true
    }


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
     * by [doThrow] called with [ErrorSource.COMPUTING] or [ErrorSource.TIMEOUT] respectively.
     *
     * @param input The input for the service computation, which is given to [doComputeAsync].
     * @param timeoutSpec The timeout specifications, which is given to [doCheckTimeoutAsync]. If it is `null`, then
     * the [timeoutJob] will not run.
     *
     * @return `true` if the service computation successfully started; `false` otherwise.
     */
    final override fun computeAsync(input: I, timeoutSpec: FrequentTimeout?): Boolean {
        // Check if the computation should start or not.
        if (!checkComputingState(false, "is already computing")) return false

        // If the service should run, then set `isComputing` to `true`.
        isComputing.set(true)

        if (timeoutSpec != null)
            logInfo("Service '{}' start computing with input: '{}', and timeout specifications: '{}'.",
                serviceName, input, timeoutSpec)
        else
            logInfo("Service '{}' start computing with input: '{}' (without timeout).", serviceName, input)

        // Start the service computation on a coroutine, which cannot have multiple runs.
        computingJob = scope.launch {

            try {
                // Invoke the actual service computation.
                val computingTime = measureTime {
                    doComputeAsync(input)
                }
                logInfo("Service '{}' finished computing normally (it took {}).", serviceName, computingTime)
            } catch (ex: Exception) {
                if (doThrow(ex, ErrorSource.COMPUTING) == true) throw ex
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
                    doCheckTimeoutAsync(timeoutSpec)
                } catch (ex: Exception) {
                    if (doThrow(ex, ErrorSource.TIMEOUT) == true) throw ex
                }

                // Reset the timeout checker.
                timeoutStart = null
                timeoutJob = null
            }

            logDebug("Service '{}' activates timeout checker with specifications: '{}'.",
                serviceName, timeoutSpec)

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
     * @param input The input required for the service's computation, as given to [computeAsync].
     */
    protected abstract suspend fun doComputeAsync(input: I)


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
     * [FrequentTimeout.callback] (if a callback is specified).
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.TIMEOUT].
     *
     * @param timeoutSpec The timeout specifications, as given to [computeAsync].
     */
    private suspend fun doCheckTimeoutAsync(timeoutSpec: FrequentTimeout) {
        // Loop until the main job (i.e., `computingJob`) is computing.
        while (computingJob?.isCompleted == false) {

            // Get the computation time of the main job.
            val computationTime = System.currentTimeMillis() - timeoutStart!!.get()

            // Check if the computation time is timed-out.
            if (computationTime > timeoutSpec.timeout) {
                // Stop the main and this jobs.
                stop()
                // Invoke the callback if it exists.
                timeoutSpec.invokeCallback()

                logInfo(
                    "Computation for service {} has been timed out after {} ms.",
                    serviceName, computationTime
                )

            } else {
                logTrace(
                    "Checking timeout for service. Remaining time: '{}'.",
                    serviceName, timeoutSpec.timeout - computationTime
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
            logTrace("Service '{}' timeout has been reset.", serviceName)
        } else
            logWarn("Service '{}' has not computation timeout to set.", serviceName)
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
     * @param timeoutSpec The timeout specifications. If it is `null`, then the wait will not be timed-out. If it
     * defines a callback, then it will be invoked if the timeout occurs.
     * @return `true` if the asynchronous service computation launched with [computeAsync] successfully finished;
     * `false` otherwise.
     */
    final override suspend fun wait(timeoutSpec: Timeout?): Boolean {

        // Check if the wait should start or not, i.e., if the service is computing or not.
        if (!checkComputingState(true, "cannot wait since there is not computation")) return false

        try {
            val waitingTime = measureTime {

                if (timeoutSpec != null) {
                    // Wait with timeout.
                    logInfo("Service '{}' is waiting for computation to finish with timeout '{}'...", serviceName, timeoutSpec)
                    withTimeout(timeoutSpec.timeout) {
                        doWait()
                    }
                } else {
                    // Wait without timeout.
                    logInfo("Service '{}' is waiting for computation to finish...", serviceName)
                    doWait()
                }
            }
            logInfo("Service '{}' finish waiting (it waited for {}).",
                serviceName, waitingTime)

        } catch (ex: Exception) {
            if (ex is TimeoutCancellationException) {
                // If timeout occurred stop the service and invoke the callback.
                logWarn("Service '{}' has been waited for too long (timeout!).", serviceName)
                stop()
                timeoutSpec?.invokeCallback()
            } else {
                // Manage other exception, such as cancellation, etc.
                if (doThrow(ex, ErrorSource.WAITING) == true) throw ex
            }
        }

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
     */
    protected open suspend fun doWait() = computingJob?.join()


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
     * Also, note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.STOPPING].
     *
     * @return `true` if the asynchronous service computation launched with [computeAsync] successfully stopped; `false`
     * otherwise.
     */
    final override fun stop(): Boolean {
        // Check if there is something to stop.
        if (!checkComputingState(true, "has already stop computing")) return false

        logDebug("Service '{}' is stopping computation...", serviceName)
        try {
            val stoppingTime = measureTime {
                // Call the actual stopping logic.
                doStop()

                // Reset all properties
                isComputing.set(false)
                computingJob = null
                timeoutJob = null
                timeoutStart = null
            }
            logInfo("Service '{}' computation has been stopped (it took {}).", serviceName, stoppingTime)

        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.STOPPING) == true) throw ex
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
     */
    protected open fun doStop() = computingJob?.cancel()


    /**
     * A shorthand to call `doThrow(ServiceError(throwable, experiencedError))`, see [doThrow] for more.
     *
     * @param throwable The exception to handle
     * @param errorSource An enumerator that identifies the calling function and relative logs, see
     * [ErrorSource] for more.
     * @return It returns `true` if the exception should be propagated with `throw`, `false` if it should only be
     * logged, and `null` if the exception should be ignored.
     */
    protected fun doThrow(throwable: Throwable, errorSource: ErrorSource) =
        doThrow(ServiceError(throwable, errorSource))


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
     * `serviceError` input parameter.
     *
     * If you are explicitly catching exceptions, remember to throw again such exception for make the error callbacks
     * aware of it.
     *
     * @param serviceError A data container class that encompass the `throwable` to handle, and the [ErrorSource],
     * which identify the function producing the error.
     * @return It returns `true` if the exception should be propagated with `throw`, `false` if it should only be
     * logged, and `null` if the exception should be ignored.
     */
    protected open fun doThrow(serviceError: ServiceError): Boolean? {
        val throwable = serviceError.throwable
        val experiencedErrorLog = serviceError.source.errorLog

        // Do not react to cancellation exceptions
        if (throwable is CancellationException || throwable.cause is CancellationException || throwable is SdkCancellationException) {
            logTrace("Service '{}' cancelled with source '{}'", serviceName, serviceError.source)
            return null
        }

        // Invoke callbacks
        onErrorCallbacks.invoke(serviceError)

        // Log the type of error
        logError("Service '{}' experienced '{}'.", serviceName, experiencedErrorLog, throwable)
        return false

        // Never returns `true`, i.e., never propagate the error with `throw`.
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
 * @see digital.boline.callAssistant.llm.LlmInteract
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface ReusableServiceInterface<I> : ServiceInterface<I> {

    // See documentation above.
    val isActive: AtomicBoolean


    /**
     * Initialise service's resources and set the [isActive] flag.
     * @return `true` if the service's resources were initialised successfully.
     */
    fun activate(): Boolean


    /**
     * Close service's resources and reset the [isActive] flag.
     * @return `true` if the service's resources were closed successfully.
     */
    fun deactivate(): Boolean
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
 * Note that step 2 cannot be performed if the service is not active, step 3 cannot be performed if the service is not
 * computing (step 2), and step 5 cannot be performed if the service is still computing.
 *
 * To implement your custom service you should extend this class and override the [doActivate], [doDeactivate], and
 * [doComputeAsync]. Such a method should only contain custom logic, since service states, its possible exceptions, and
 * logging are managed by this class. Eventually you can further customize the behaviour of this class by extending the
 * [doWait], [doStop], and [doThrow] methods, but always remember to call `super` to avoid unexpected behaviour.
 *
 * @param I The data type required to compute the service (i.e., required by [computeAsync]).
 *
 * @property isActive The flag indicating if the service has initialised its resources. Thus, it can perform
 * computations. It is set by [activate] and reset by [deactivate]. If the service is not active, then [isComputing]
 * will be false, and [computingJob], [timeoutJob], and [timeoutStart] will be `null`.
 * @property isComputing The flag indicating if the service is currently computing or not. See [Service.isComputing]
 * for more.
 * @property serviceName A `protected` property that represents the name of this service for logging purposes, as
 * defined by [Service.serviceName].
 * @property scope A `private` property that defines where the coroutines will run. See [Service.scope] for more.
 * @property computingJob A `private` property that represents the coroutine job for the computation. See
 * [Service.computingJob] for more.
 * @property timeoutJob A `private` property that represents the coroutine job for the timeout watcher associated with
 * [computingJob]. See [Service.timeoutJob] for more.
 * @property timeoutStart A `private` property that represents the start time of the timeout policy. See
 * [Service.timeoutStart] for more.
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
 * @see digital.boline.callAssistant.llm.LlmInteract
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
            logWarn("Service '{}' cannot computeAsync since it is not active.", serviceName)
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
                logWarn("Service '{}' cannot be deactivated since it is still computing.", serviceName)
                return false
            }
        }

        // Deny if it is active and wants to re-activate, or if it is not active and wants to re-deactivate.
        if (isActive.get() != expectedActiveState) {
            logWarn("Service '{}' {}.", serviceName, warningLog)
            return false
        }

        // Allow activation or deactivation.
        return true
    }


    /**
     * Initialise service's resources and set the [isActive] flag. This method does nothing if the service has already
     * been activated. This method only manages service state, possible exception and logging, but the current
     * activation is implemented in the [doActivate] method, which is called by this method, and it should be
     * implemented by derived classes.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.ACTIVATING].
     *
     * @return `true` if the service has been successfully activated, `false` otherwise.
     */
    final override fun activate(): Boolean {
        // Deny activation if it already activated.
        if (!checkActiveState(false, "has already been activated")) return false

        // Activate the service and, if the activation is successfully, manage the `isActive` state.
        try {
            logDebug("Service '{}' is activating...", serviceName)
            val activationTime = measureTime {
                doActivate()
                isActive.set(true)
            }
            logInfo("Service '{}' has been activated (took: {}).", serviceName, activationTime)
            return true
        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.ACTIVATING) == true) throw ex
        }

        logWarn("Service '{}' did not activated!", serviceName)
        return false
    }


    /**
     * This method implements the instructions for initialising service's resources. It is called by [activate], which
     * is in charge to manage service state, possible exception and logging. Thus, this function only cares about the
     * service initialization.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.ACTIVATING].
     */
    protected abstract fun doActivate()


    /**
     * Close service's resources and reset the [isActive] flag. This method does nothing if the service has already
     * been deactivated. This method only manages service state, possible exception and logging, but the current
     * deactivation is implemented in the [doDeactivate] method, which is called by this method, and it should be
     * implemented by derived classes.
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.DEACTIVATING].
     *
     * @return `true` if the service has been successfully deactivated, `false` otherwise.
     */
    final override fun deactivate(): Boolean {

        // Deny deactivation if it is already deactivate or if it is currently computing.
        if (!checkActiveState(true, "has already been deactivated")) return false

        // Deactivate the service and, if the deactivation is successfully, manage the `isActive` state.
        try{
            logDebug("Service '{}' is deactivating...", serviceName)
            val deactivationTime = measureTime {
                doDeactivate()
                isActive.set(false)
            }
            logInfo("Service '{}' has been deactivated (took: {}).", serviceName, deactivationTime)
            return true
        } catch (ex: Exception) {
            if (doThrow(ex, ErrorSource.DEACTIVATING) == true) throw ex
        }

        logWarn("Service '{}' did not activated!", serviceName)
        return false
    }


    /**
     * This method implements the instructions for closing service's resources. It is called by [deactivate], which
     * is in charge to manage service state, possible exception and logging. Thus, this function only cares about the
     * releasing the sources that the service initialized with [doActivate].
     *
     * Note that this functions runs in a try-catch block that catches and reacts to all exception based on the
     * [doThrow] method called with [ErrorSource.DEACTIVATING].
     *
     * @return `true` if the service has been successfully activated, `false` otherwise.
     */
    protected abstract fun doDeactivate()

}


/**
 * The base definition of a callbacks manager, which collects lambda functions in a mao and allow invoking them. This
 * class should store lambda functions on map with a string-based identifier for each callback. Such a manager is
 * usually used by an implementation or extension of the [ServiceInterface].
 *
 * @param I The callbacks input.
 * @param O The callbacks output.
 *
 * @see CallbackManager
 * @see digital.boline.callAssistant.text2speech.Text2SpeechPlayer
 * @see digital.boline.callAssistant.text2speech.Text2Speech
 * @see digital.boline.callAssistant.speech2text.Speech2Text
 * @see digital.boline.callAssistant.llm.LlmInteract
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface CallbackManagerInterface<I, O>  {
    // It does not extend LoggableInterface, since it logs error with the logger of delegate classes.

    /**
     * Adds a new callback.
     * @param callback The callback implementation to be added.
     * @return The string-based identifier of the callback that has been added.
     */
    fun add(callback: ((I) -> O)): String

    /**
     * Removes a callback.
     * @param callback The callback implementation to be removed.
     */
    fun remove(callback: ((I) -> O))

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
     * @return The results of the callbacks.
     */
    fun invoke(callbackInput: I): Map<String, O>

    /**
     * Returns the callbacks associated with the given identifier.
     * @param callbackIdentifier The string-based identifier of the callbacks.
     * @return The callbacks associated with the given identifier, or `null` if it does not exist.
     */
    fun getCallbacks(callbackIdentifier: String): ((I) -> O)?
}


/**
 * An implementation of the [CallbackManagerInterface] that collects lambda functions in a map where keys identifies
 * callback based on where they are implemented, i.e., based on [getCallbackIdentifier]. This class is usually used by
 * an implementation or extension of the [ServiceInterface].
 *
 * @param I The callbacks input.
 * @param O The callbacks output.
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
 * @see digital.boline.callAssistant.llm.LlmInteract
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class CallbackManager<I, O>(private val logger: CentralizedLogger, serviceName: String? = null) : CallbackManagerInterface<I, O> {
    // It does not extend Loggable, since it logs error with the logger of delegate classes.

    // See documentation above.
    private val serviceName = serviceName ?: this.javaClass.simpleName

    // See documentation above.
    private val callbacks: MutableMap<String, ((I) -> O)> = mutableMapOf()


    /**
     * Returns a callback stored in the [callbacks] map given its identifier, which is based on [getCallbackIdentifier].
     * If such a callback does not exist, it returns `null`.
     *
     * @param callbackIdentifier The callback identifier
     * @return The callback associated with the given identifier within the [callbackIdentifier] map, or `null` if it
     * does not exist.
     */
    override fun getCallbacks(callbackIdentifier: String): ((I) -> O)? {
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
    override fun add(callback: ((I) -> O)): String{
        synchronized(callbacks) {
            val callbackId = getCallbackIdentifier(callback)

            if(callbacks.put(callbackId, callback) != null)
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
    override fun remove(callback: ((I) -> O)) {
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
     * @return The results of the callbacks.
     */
    override fun invoke(callbackInput: I): Map<String, O> {

        /**
         * Invokes the given callback and measures the computing time.
         * @param callbackId The callback identifier as stored in the [callbacks] map.
         * @param callback The callback to be invoked, which has the relative [callbackId] in the [callbacks] map.
         * @param input The input for the callback to be invoked.
         * @param timeMap A list that will be populated with computation time. If the [callbackId] already exists in
         * this map, then the new computation time will be overridden.
         * @return The callback output.
         */
        fun invokeWithTimeMeasure(callbackId: String, callback: (I) -> O, input: I, timeMap: MutableMap<String, Duration>): O {
            val output: O
            val computationTime = measureTime {
                output = callback(input)
            }
            timeMap[callbackId] = computationTime
            return output
        }

        synchronized(callbacks) {
            if(callbacks.isEmpty()) {
                logger.warn("Service '{}' does not have any callbacks to be invoked.", serviceName)
                return mapOf()
            }

            // Invoke the function and measure computation time.
            logger.debug("Service '{}' is invoking callbacks...", serviceName)
            val computationTime = mutableMapOf<String, Duration>()
            val results: Map<String, O>
            val totalComputationTime = measureTime {
                results = callbacks.map {
                    it.key to invokeWithTimeMeasure(it.key, it.value, callbackInput, computationTime)
                }.toMap()
            }
            logger.info("Service '{}' is invoked {} callback(s) (computation times: {}ms, total computation time: {}ms ).",
                serviceName, callbacks.size, computationTime,  totalComputationTime)

            return results
        }
    }


    companion object {

        /**
         * Returns the string-based identifier of the given callback. Such an identifier it is based on the location
         * where the callback is implemented within the code, and the name of the class that uses it.
         * @param callback The callback implementation.
         * @return The string-based identifier of the given callback.
         */
        fun <I, O> getCallbackIdentifier(callback: ((I) -> O)): String {
            //val location = callback.javaClass.protectionDomain?.codeSource?.location?.toString() ?: "???"
            val name = callback.javaClass.name //+ callback.javaClass.hashCode().toString()
            return name // "$name@$location"
        }
    }
}
