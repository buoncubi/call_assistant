package digital.boline.callAssistant.llm

import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.LoggableInterface
import java.util.concurrent.CompletableFuture


/**
 * Represents a generic interface for handling a streaming-based Large Language Model (LLM) server.
 * 
 * This interface provides methods to manage the lifecycle of the LLM server, including starting
 * and stopping the server, adding and removing callbacks for response handling, and making requests
 * to the server. Generics are used to define the data types specific to the dialogue, prompts, and
 * output handled by the implementation.
 *
 * @param D The type representing the dialogue format.
 * @param P The type representing the prompt format.
 * @param O The type representing the output from the LLM server.
 *
 * @see AwsBedrock
 *
 * @author Luca Buoncompagni © 2025
 */
interface LlmStreamingInterface<D, P, O>: LoggableInterface {


    /**
     * Checks if the LLM server is currently running.
     *
     * @return `true` if the server is running, `false` otherwise.
     */
    fun isRunning(): Boolean // It is a `fun` instead of a `var` to assure that the flag is private.


    /**
     * Starts the LLM server.
     *
     * @return `true` if the server was successfully started, `false` otherwise.
     */
    fun start(): Boolean


    /**
     * Stops the LLM server.
     *
     * @return `true` if the server was successfully stopped, `false` if the server was not running.
     */
    fun stop(): Boolean


    /**
     * Adds a callback function to be executed under certain conditions.
     *
     * @param callback The callback function to be added, which accepts a parameter of type [O] and returns nothing.
     * @return `true` if the callback was successfully added, `false` if the server is already running.
     */
    fun addCallback(callback: (O) -> Unit): Boolean


    /**
     * Removes a specified callback function from the list of registered callbacks.
     *
     * @param callback The callback function to be removed, which accepts a parameter of type [O] and returns nothing.
     * @return `true` if the callback was successfully removed, `false` if the server is running or another issue occurs.
     */
    fun removeCallback(callback: (O) -> Unit): Boolean


    /**
     * Sends a request to the LLM server with the provided dialogue and prompts.
     * 
     * This method should make an asynchronous request and notify the `callbacks` when the results are ready.
     * Also, it should avoid making requests when the LLM service is not running.
     *
     * @param dialogue The dialogue context or data that consists of previous exchanges or conversation history.
     * @param prompts The prompts or input data that the LLM server will process to generate a response.
     *
     * @return A CompletableFuture representing the asynchronous computation of the server's response, or `null`
     * if the request could not be initiated.
     */
    fun makeRequest(dialogue: D, prompts: P): CompletableFuture<*>?
}


// extend start and stop and manage isRunning
abstract class LlvmAsync<D, P, O>: Loggable(), LlmStreamingInterface<D, P, O> {


    /**
     * A flag indicating whether the service is currently running or not.
     *
     * This variable is used to track the server's operational state and is updated when the [start] or [stop] methods
     * are invoked. Ensures that the server is not started or stopped multiple times consecutively, maintaining proper
     * life-cycle management.
     */
    protected var serverRunning = false


    /**
     * A thread-safe collection of callbacks to handle responses from the assistant. Each callback receives an instance
     * of [AssistantResponse], which encapsulates the details of the assistant's response message, metadata, and token
     * usage.
     *
     * This set is synchronized during addition, removal, and invocation of callbacks to ensure thread safety. Callbacks
     * are typically invoked during the completion of asynchronous operations such as processing requests in
     * [makeRequest].
     */
    protected val callbacks = mutableSetOf<(O) -> Unit>()


    /**
     * Checks whether the server or system is currently running.
     *
     * @return `true` if the server is running, `false` otherwise.
     */
    override fun isRunning(): Boolean = serverRunning


    /**
     * Starts the LLM server. Ensures that the server is not already running before starting.
     *
     * If the server is already running, an error message is printed, and the operation is aborted. Be aware that this
     * method be further implemented by derived classes, and it should change the state of [serverRunning].
     *
     * @return `true` if the server was successfully started, `false` otherwise.
     */
    override fun start(): Boolean {
        if (isRunning()) {
            logWarn("LLM client already started.")
            return false
        }
        return true
    }


    /**
     * Stops the LLM server if it is currently running.
     *
     * If the server is not running, an error message is printed, and the operation is aborted. Be aware that this
     * method be further implemented by derived classes, and it should change the state of [serverRunning].
     *
     * @return `true` if the server was successfully stopped, `false` if the server was not running.
     */
    override fun stop(): Boolean {
        if (!isRunning()) {
            logWarn("LLM client already stopped.")
            return false
        }
        return true
    }


    /**
     * Adds a not previously registered callback from the assistant response handler. This method ensures thread-safe
     * addition of the callback from the internal list of registered callbacks (i.e., it exploits
     * `synchronized(callback)`). Note that if the LLM server is currently running, the removal operation is not
     * allowed, and a warning is printed.
     *
     * @param callback The function to be added, which was not previously registered, to handle [O]. The callback should
     * define processing logic for the assistant's response.
     *
     * @return `true` if the callback was successfully removed, `false` otherwise. Returns `false` if the callback was
     * not registered or if the operation fails.
     */
    override fun addCallback(callback: (O) -> Unit): Boolean {
        if (isRunning()) {
            logWarn("Adding a callback to ab LLM client when it is already started.")
            return false
        }

        synchronized(callback) {
            return callbacks.add(callback)
        }
    }


    /**
     * Removes a previously registered callback from the assistant response handler. This method ensures thread-safe
     * removal of the callback from the internal list of registered callbacks (i.e., it exploits
     * `synchronized(callback)`). Note that if the LLM server is currently running, the removal operation is not
     * allowed, and a warning is printed.
     *
     * @param callback The function to be removed, which was previously registered, to handle [O]. The callback should
     * define processing logic for the assistant's response.
     *
     * @return `true` if the callback was successfully removed, `false` otherwise. Returns `false` if the callback was
     * not registered or if the operation fails.
     */
    override fun removeCallback(callback: (O) -> Unit): Boolean {
        if (isRunning()) {
            logWarn("Removing a callback from an LLM client already started.")
            return false
        }

        synchronized(callback) {
            return callbacks.remove(callback)
        }
    }
}