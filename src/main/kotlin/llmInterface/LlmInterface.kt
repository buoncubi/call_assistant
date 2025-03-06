package llmInterface

import java.util.concurrent.CompletableFuture

//TODO adjust documentation with links [] and @see

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
 * @see `AwsBedrock`
 *
 * @author Luca Buoncompagni © 2025
 */
interface LlmStreamingInterface<D, P, O> {

    /**
     * Checks if the LLM server is currently running.
     *
     * @return `true` if the server is running, `false` otherwise.
     */
    fun isRunning(): Boolean

    /**
     * Starts the LLM server. Ensures that the server is not already running before starting.
     * 
     * If the server is already running, an error message is printed, and the operation is aborted.
     * Be aware that this method should change the state of `isRunning`.
     *
     * @return `true` if the server was successfully started, `false` otherwise.
     */
    fun start(): Boolean {
        if (isRunning()) {
            println("Error: cannot start an LLM server twice.")
            return false
        }
        return true
    }

    /**
     * Stops the LLM server if it is currently running.
     * 
     * If the server is not running, an error message is printed, and the operation is aborted.
     * Be aware that this method should change the state of `isRunning`.
     *
     * @return `true` if the server was successfully stopped, `false` if the server was not running.
     */
    fun stop(): Boolean {
        if (!isRunning()) {
            println("Error: cannot stop a not running LLM server.")
            return false
        }
        return true
    }

    /**
     * Adds a callback function to be executed under certain conditions.
     * If the LLM server is already running, the callback cannot be added.
     *
     * @param callback The callback function to be added, which accepts a parameter of type `O` and returns `Unit`.
     * @return `true` if the callback was successfully added, `false` if the server is already running.
     */
    fun addCallback(callback: (O) -> Unit): Boolean {
        if (isRunning()) {
            println("Warning: adding a callback to a started LLM server.")
            return false
        }
        return true
    }

    /**
     * Removes a specified callback function from the list of registered callbacks.
     * If the LLM server is currently running, the removal operation is not allowed, and a warning is printed.
     *
     * @param callback The callback function to be removed, which accepts a parameter of type `O` and returns `Unit`.
     * @return `true` if the callback was successfully removed, `false` if the server is running or another issue occurs.
     */
    fun removeCallback(callback: (O) -> Unit): Boolean {
        if (isRunning()) {
            println("Warning: removing a callback from a started LLM server.")
            return false
        }
        return true
    }

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
     *         if the request could not be initiated.
     */
    fun makeRequest(dialogue: D, prompts: P): CompletableFuture<*>?
}