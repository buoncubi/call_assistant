package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream
import kotlin.time.measureTime



/**
 * Defines the scope in which Coroutine for Text2Speech runs (i.e., class derived from [Text2Speech] and
 * [Text2SpeechPlayer]). This scope involves 4 possible jobs: one performs WEB based request, another plays audio, and
 * the remaining two waits for checking timeout.
 *
 * This scope runs on [Dispatchers.Default], which is optimized for CPU-intensive tasks. It also has a [CoroutineName]
 * that identifies the scope as `Text2SpeechScope`. It uses the [SupervisorJob], which ensures that any child coroutine
 * will be cancelled if the parent scope is cancelled.
 */
private val text2SpeechScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineName("Text2SpeechScope")
)



/**
 * The interface for playing the results of a text-to-speech process as implemented by [Text2Speech]. This interface
 * required to play the audio on a separate coroutine, see [ServiceInterface] for more.
 *
 * Furthermore, this class defines two [CallbackManager] that collects callbacks invoked when the audio begins and ends
 * playing. Such a callbacks takes as input a `C` object and return nothing.
 *
 * @param C The type of the callbacks input.
 *
 * @property onBeginPlayingCallbacks The callbacks to be invoked when the audio begins playing.
 * @property onEndPlayingCallbacks The callbacks to be invoked when the audio ends playing.
 *
 * @see Text2Speech
 * @see ReusableService
 * @see AwsPolly
 * @see DesktopAudioPlayer
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface Text2SpeechPlayerInterface<C>: ServiceInterface<InputStream> {
    // See documentation above.
    val onBeginPlayingCallbacks: CallbackManager<C, Unit>
    val onEndPlayingCallbacks: CallbackManager<C, Unit>
}



/**
 * A shorthand for using [Text2SpeechPlayerInterface] based on [Service] of `InputStream` and [text2SpeechScope].
 *
 * @param C The type of the callbacks input.
 *
 * @property onBeginPlayingCallbacks The callbacks to be invoked when the audio begins playing.
 * @property onEndPlayingCallbacks The callbacks to be invoked when the audio ends playing.
 *
 * @see Text2Speech
 * @see ReusableService
 * @see AwsPolly
 * @see DesktopAudioPlayer
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class Text2SpeechPlayer<C>: Service<InputStream>(text2SpeechScope), Text2SpeechPlayerInterface<C>



/**
 * The interface to perform text-to-speech. It performs computation asynchronously based on the [ReusableService].
 *
 * It requires to call the [activate] method to instance the service's resources, and to use [deactivate] to close them
 * when the service is no longer needed. When the service is activated, it performs text-to-speech through the
 * [computeAsync], and you can [wait] for the job to finish or [stop] it. Then, you can start new computations with
 * [computeAsync] without the need to deactivate and activate the service.
 *
 * This class extends [ReusableService] by implementing the [doComputeAsync] (which is invoked by [computeAsync]) by
 * introducing a two steps processing:
 *  1. [fetchAudio]: Is a method that given a `String` it uses a text-to-speech API to produce an audio `InputStream`.
 *  2. [player]: is an [Text2SpeechPlayer] instance that plays the audio produced at the previous step.
 *
 * Note that this class allow defining a timeout for [doComputeAsync], which is reset when the audio is fetched. Also,
 * [Text2SpeechPlayer] allow defining a callback when the audio begins and ends playing. Furthermore, it is possible to
 * define callbacks in case of error by the mean of [onErrorCallbacks].
 *
 * Also note that this class should only take care of logic computation, since [ReusableService] takes care of producing
 * logging, catching exceptions (see [doThrow] for more), and managing the service's states. See [ReusableService]
 * for more.
 *
 * @property player The object that will play the audio. It is required at construction time.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see Text2SpeechPlayer
 * @see ReusableServiceInterface
 * @see AwsPolly
 * @see DesktopAudioPlayer
 * @see CallbackManager
 * @see FrequentTimeout
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class Text2Speech(private val player: Text2SpeechPlayer<*>) : ReusableService<String>(text2SpeechScope) {

    /**
     * Fetches the audio from a text-to-speech API. This method is invoked by [doComputeAsync] and should be
     * implemented by the derived class. Note that this method runs in a try-catch block that will invoke [doThrow] with
     * [ServiceError.source] set to [ErrorSource.COMPUTING] in case of exception.
     *
     * @param text The text to be converted to audio.
     * @return The audio produced by the text-to-speech API that will be played.
     */
    protected abstract fun fetchAudio(text: String): InputStream?


    /**
     * This method is invoked by [computeAsync] and invokes [fetchAudio] before to play the audio based on [player].
     * Note that this method runs in a try-catch block that will invoke [doThrow] with [ServiceError.source]
     * set to [ErrorSource.COMPUTING] in case of exception.
     *
     * Note that this method is executed by [computeAsync], which allow to define a timeout policy. Also note that
     * [resetTimeout] is never called in this implementation. See [Service] and [ReusableService] for more.
     *
     * @param input The string to be converted to audio.
     */
    override suspend fun doComputeAsync(input: String) {
        // Fetch audio
        logger.trace("Service '{}' is fetching audio...", serviceName)
        val inputStream: InputStream?
        val fetchingTime = measureTime {
            inputStream = fetchAudio(input) // Eventually, you can define a callback here by the means of `CallbackManager`
        }
        logger.debug("Service '{}' fetched audio (took {})", serviceName, fetchingTime)

        // Play the audio and wait for it to finish
        if (inputStream != null) {
            resetTimeout()
            logger.trace("Service '{}' is playing audio...", serviceName)
            val playingTime = measureTime {
                player.computeAsync(inputStream) // Eventually, you can set a timeout here.
                player.wait()
            }
            logger.debug("Service '{}' played audio (took {})", serviceName, playingTime)
        } else
            logWarn("Service '{}' fetched a `null` audio.", serviceName)
    }


    /**
     * This method is invoked by [wait] and extends [ReusableService.doWait] by invoking [Text2SpeechPlayer.wait]
     * before. Note that this method runs in a try-catch block that will invoke [doThrow] with
     * [ServiceError.source] set to [ErrorSource.WAITING] in case of exception.
     *
     * See [ReusableService.wait] for more.
     */
    override suspend fun doWait() {
        player.wait()
        super.doWait()
    }


    /**
     * This method is invoked by [stop] and extends [ReusableService.doStop] by invoking [Text2SpeechPlayer.stop]
     * before. Note that this method runs in a try-catch block that will invoke [doThrow] with
     * [ServiceError.source] set to [ErrorSource.STOPPING] in case of exception.
     *
     * See [ReusableService.stop] for more.
     */
    override fun doStop() {
        player.stop()
        super.doStop()
    }

}