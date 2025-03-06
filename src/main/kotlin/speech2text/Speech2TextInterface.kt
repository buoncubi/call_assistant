package speech2text

//TODO adjust documentation with links [] and @see

/**
 * Interface representing a Speech-to-Text functionality. This interface allows
 * adding and removing callbacks that handle recognized speech data and provides
 * methods to control the start and stop of the listening process, which should
 * occur asynchronously.
 * 
 * This implementation is designed to be used with a not concurrent single callbacks,
 * which should be set when the speech is not being listened.
 *
 *  @param `<I>`: the type of input stream where the audio signal is provided.
 *  @param `<O>`: the type of output text, which represents the recognized speach,
 *                and it is provided to the callbacks.
 *
 * @see `AwsTranscribe`.
 *
 * @author Luca Buoncompagni © 2025
 */
interface Speech2TextInterface<I, O> {
    // TODO make it an abstract class and make functions protected
    // TODO uniform across all interfaces (i.e., LlmStreamingInterface, Text2SpeechInterface, Speech2TextInterface callback paradigm: or lambda, or addCallback() + removeCallback()

    /**
     * A mutable set of `callbacks` that are invoked with a parameter of type `0`. These `callbacks`
     * can be used to handle events or data emitted by an implementing class.
     * 
     * Each callback is a function that accepts a single argument of type `0` and has no return value.
     * The set ensures that a specific callback instance is not registered multiple times.
     */
    val callbacks: MutableSet<((O) -> Unit)>  // TODO remove it since it is public

    /**
     * Represents the input stream used for processing audio data.
     * 
     * This InputStream is utilized as the source of audio input for the
     * speech-to-text conversion process. It provides the raw audio data
     * necessary for recognition and transcription.
     */
    val inputStream: I

    /**
     * Checks if the speech-to-text listening process is currently active.
     *
     * @return `true` if the instance is currently listening, `false` otherwise.
     */
    fun isListening(): Boolean // It is a `fun` instead of a `var` to assure that the flag is private.

    /**
     * Adds a `callback` to the set of `callbacks`.
     * This function is thread safe, and it exploits `synchronized(callbacks)`.
     *
     * @param callback A function that will be invoked when an event occurs.
     *
     * @return `True` if the callback was successfully added, `false` if it already exists in the set.
     */
    fun addCallback(callback: (O) -> Unit): Boolean {
        if (isListening()) {
            println("Warning: adding a callback to a listening speech2text instance.")
            return false
        }
        if (callbacks.contains(callback)) {
            println("Warning: adding an existing callback to speech2text instance.")
            return false
        }
        // TODO manage logging
        synchronized(callbacks) {
            return callbacks.add(callback)
        }
    }

    /**
     * Removes a `callback` from the set of registered `callbacks`.
     * This function is thread safe, and it exploits `synchronized(callbacks)`.
     *
     * @param callback The `callback` to be removed.
     *
     * @return `true` if the callback was successfully removed, `false` otherwise.
     */
    fun removeCallback(callback: (O) -> Unit): Boolean {
        if (isListening()) {
            println("Warning: removing a callback from a listening speech2text instance.")
            return false
        }
        if (!callbacks.contains(callback)) {
            println("Warning: removing a non-existing callback from speech2text instance.")
            return false
        }
        // TODO manage logging
        synchronized(callbacks) {
            return callbacks.add(callback)
        }
    }

    /**
     * Invokes all registered callbacks in a separate thread,
     * which concurrency is protected by `synchronized(callbacks)`.
     *
     * @param textResults The result of type `O` to be passed to each callback
     *                    function, i.e., the voice recognised into a text.
     */
    fun onCallbackResults(textResults: O) {
        synchronized(callbacks) {
            callbacks.forEach { it(textResults) }
        }
    }

    /**
     * Starts the speech-to-text listening process asynchronously.
     * It should not try start an instance that is already listening.
     * 
     * This implementation already check for `isListening` flag and returns
     * `false` if it is already listening. However, be sure to set the
     * `isListening` flag if this method successfully started listening.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    fun startListening(): Boolean{
        if (isListening()) {
            println("Warning: starting a listening speech2text instance already started.")
            return false
        }
        return true
    }

    /**
     * Stops the speech-to-text listening process and close relative resources.
     * It should not try stop an instance that is not listening.
     * 
     * This implementation already check for `isListening` flag and returns
     * `false` if it is not listening. However, be sure to reset the
     * `isListening` flag if this method successfully stopped listening.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    fun stopListening(): Boolean{
        if (!isListening()) {
            println("Warning: stopping a listening speech2text instance already stopped.")
            return false
        }
        return true
    }
}
