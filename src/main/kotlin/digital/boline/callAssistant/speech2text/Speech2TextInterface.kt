package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.LoggableInterface


/**
 * Interface representing a Speech-to-Text functionality. This interface allows adding and removing callbacks that
 * handle recognized speech data and provides methods to control the start and stop of the listening process, which
 * should occur asynchronously.
 * 
 * This implementation is designed to be used with a not concurrent single callbacks, which should be set when the
 * speech is not being listened.
 *
 * @param O: the type of output text, which represents the recognized speech, and it is provided to the callbacks.
 *
 * @see Speech2TextAsync
 *
 * @author Luca Buoncompagni © 2025
 */
interface Speech2TextInterface<O>: LoggableInterface {

    /**
     * Checks if the speech-to-text listening process is currently active.
     *
     * @return `true` if the instance is currently listening, `false` otherwise.
     */
    fun isListening(): Boolean // It is a `fun` instead of a `var` to assure that the flag is private.


    /**
     * Adds a `callback` to the set of callbacks.
     *
     * @param callback A function that will be invoked when an event occurs.
     * @return `True` if the callback was successfully added, `false` if it already exists in the set.
     */
    fun addCallback(callback: (O) -> Unit): Boolean


    /**
     * Removes a `callback` from the set of registered callbacks
     *
     * @param callback The `callback` to be removed.
     * @return `true` if the callback was successfully removed, `false` otherwise.
     */
    fun removeCallback(callback: (O) -> Unit): Boolean


    /**
     * Starts the speech-to-text listening process asynchronously.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    fun startListening(): Boolean


    /**
     * Stops the speech-to-text listening process and close relative resources.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    fun stopListening(): Boolean
}


/**
 * This is the base implementation of the [Speech2TextInterface] interface in an asynchronous manner.
 *
 * It provides a thread sage implementation of a [callbacks] set. Also, it provides the basic implementation of the
 * [serverListening] flag, but it should be further managed by the derived implementations of [startListening] and
 * [stopListening], which are required for the service to work. It also provides logging facilities through the
 * [Loggable] class.
 *
 * @param I: the type of input stream where the audio signal is provided.
 * @param O: the type of output text, which represents the recognized speech, and it is provided to the callbacks.
 *
 * @property inputStream The input stream where the audio signal is provided (this property is `protected`, and it
 * should be used only by derived classes).
 * @property serverListening A flag to identify if the service is currently listening or not (this property is `protected`,
 * and it should be used only by derived classes).
 * @property callbacks A set of callbacks functions that will be invoked when a recognised text is available (this
 * property is `protected`, and it should be used only by derived classes).
 *
 * @see Speech2TextInterface
 * @see AwsTranscribe
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class Speech2TextAsync<I, O>: Loggable(), Speech2TextInterface<O> {

    /**
     * Represents the input stream used for processing audio data.
     *
     * This InputStream is utilized as the source of audio input for the speech-to-text conversion process. It provides
     * the raw audio data necessary for recognition and transcription.
     */
    protected abstract val inputStream: I


    /**
     * Indicates whether the AWS Transcribe-based speech-to-text service is actively listening for audio input. This
     * property reflects the state of the listening process and is updated as the [startListening] and [stopListening]
     * methods are executed.
     *
     * If it is `true`, it signifies that the listening session is currently active and audio input is being processed,
     * while `false` signifies that the service is not listening.
     */
    protected var serverListening: Boolean = false


    /**
     * Checks whether the service is currently in a listening state.
     *
     * @return `true` if the service is actively listening, `false` otherwise.
     */
    override fun isListening(): Boolean {
        return serverListening
    }


    /**
     * A mutable set of `callbacks` that are invoked with a parameter of type [O]. These `callbacks` can be used to
     * handle events or data emitted by an implementing class.
     *
     * Each callback is a function that accepts a single argument of type [O] and has no return value. The set ensures
     * that a specific callback instance is not registered multiple times.
     */
    protected val callbacks: MutableSet<(O) -> Unit> = mutableSetOf()


    /**
     * Adds a `callback` to the set of [callbacks]. This function is thread safe, i.e., it exploits
     * `synchronized(callbacks)`.
     *
     * @param callback A function that will be invoked when an event occurs.
     * @return `True` if the callback was successfully added, `false` if it already exists in the set.
     */
    override fun addCallback(callback: (O) -> Unit): Boolean {
        if (isListening()) {
            logWarn("Cannot add a callback on a started service")
            return false
        }
        if (callbacks.contains(callback)) {
            logWarn("Cannot add an already existing callback")
            return false
        }

        synchronized(callbacks) {
            logInfo("Adding callback '{}'", callback)
            return callbacks.add(callback)
        }
    }


    /**
     * Removes a `callback` from the set of registered [callbacks]. This function is thread safe, i.e., it exploits
     * `synchronized(callbacks)`.
     *
     * @param callback The `callback` to be removed.
     *
     * @return `true` if the callback was successfully removed, `false` otherwise.
     */
    override fun removeCallback(callback: (O) -> Unit): Boolean {
        if (isListening()) {
            logWarn("Cannot removing a callback with a listening service")
            return false
        }
        if (!callbacks.contains(callback)) {
            logWarn("Cannot removing a not existing callback")
            return false
        }

        synchronized(callbacks) {
            logInfo("Removing callback '{}'.", callback)
            return callbacks.add(callback)
        }
    }


    /**
     * Invokes all registered callbacks in a separate thread, which concurrency is protected by
     * `synchronized(callbacks)`.
     *
     * @param textResults The result of type [O] to be passed to each callback function, i.e., the recognised voice
     * as a text.
     */
    protected fun onCallbackResults(textResults: O) {
        synchronized(callbacks) {
            callbacks.forEach { it(textResults) }
        }
    }


    /**
     * Starts the speech-to-text listening process asynchronously. It should not try start an instance that is already
     * listening.
     *
     * This implementation already check for [serverListening] flag and returns `false` if it is already listening. However,
     * be sure to further implement this method on the derived classes and to manage here the [serverListening] flag.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    override fun startListening(): Boolean{
        if (isListening()) {
            logWarn("Cannot start a client already started")
            return false
        }
        return true
    }


    /**
     * Stops the speech-to-text listening process and close relative resources. It should not try stop an instance that
     * is not listening.
     *
     * This implementation already check for [serverListening] flag and returns `false` if it is not listening. However,
     * be sure to further implement this method on the derived classes and to manage here the [serverListening] flag.
     *
     * @return `true` if the instance was stopped successfully, `false` otherwise.
     */
    override fun stopListening(): Boolean{
        if (!isListening()) {
            logWarn("Cannot stop a client already stopped.")
            return false
        }
        return true
    }
}