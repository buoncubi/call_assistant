package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.CallbackManager
import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.ReusableService
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream


/**
 * Defines the scope in which Coroutine for SpeechToText (i.e., class derived from [Speech2Text]). This scope involves
 * 3 possible jobs: one makes WEB based requests, the others waits to check timeout. Note that the audio input stream is
 * managed through thread to assure real-time computations.
 *
 * This scope runs on [Dispatchers.Default], which is optimized for CPU-intensive tasks. It also has a [CoroutineName]
 * that identifies the scope as `Speech2TextScope`. It uses the [SupervisorJob], which ensures that any child coroutine
 * will be cancelled if the parent scope is cancelled.
 */
private val speech2TextScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineName("Speech2TextScope")
)



/**
 * The interface to build `InputStream`, as required by the [Speech2Text] and derived classes.
 *
 * @see Speech2Text
 * @see DesktopMicrophone
 * @see AwsTranscribe
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface Speech2TextStreamBuilder {

    /**
     * Instantiates a new input stream for getting audio to be converted into text.
     * @return Reruns an `InputStream` or `null` in case of error.
     */
    fun build(): InputStream?
}


/**
 * An abstract class that implements the speech-to-text interface based on [ReusableService], which does take nothing
 * as input parameter for make its computation.
 *
 * A [ReusableService] need to be [activate] before to perform computation based on [computeAsync], which allow defining
 * a timeout with relative callback. Then, you can decide to [wait] for the computation to be done, with an optional
 * timeout (and related callback), or you can manually [stop] the computation. Finally, you can perform [computeAsync]
 * again and, when the service is no longer need, you should use [deactivate]. To allow such a behaviour, this class
 * implements the [doActivate], [doComputeAsync], [doWait], [doStop] and [doDeactivate] specifically for speech-to-text
 * processing. Note that all these operations already occur into try-catch blocks managed by the [doThrow] method, which
 * invokes [onErrorCallbacks]. For more see [ReusableService].
 *
 * In this class, [computeAsync] is in charge to take an audio input stream given by [streamBuilder], and provides the
 * text transcription of it through the [onResultCallbacks].
 *
 * @param R The type of the text transcription results given through [onResultCallbacks].
 *
 * @property streamBuilder The object providing the input audio stream to process.
 * @property onResultCallbacks The callback manager for the text transcription results.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 * @see AwsTranscribe
 * @see ReusableService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class Speech2Text<R>(protected val streamBuilder:Speech2TextStreamBuilder) : ReusableService<Unit>(speech2TextScope) {
    val onResultCallbacks = CallbackManager<R, Unit>(logger)


    /**
     * A shorthand for invoking [ReusableService.computeAsync] with no input parameter for computing the server, and
     * with optional timeout.
     *
     * @param timeoutSpec The timeout specification, which also encompass a lambda function.
     * @return `true` if the text-to-speech computation has been started, `false` otherwise.
     */
    fun computeAsync(timeoutSpec: FrequentTimeout? = null) = super.computeAsync(Unit, timeoutSpec)
}