package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.CallbackInput
import digital.boline.callAssistant.CallbackManager
import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Service
import javazoom.jl.player.FactoryRegistry
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
import java.io.InputStream
import kotlin.time.measureTime


/**
 * A data class that stores the playback event and the source tag of the audio stream. It is the class provided to the
 * [DesktopAudioPlayer.onBeginPlayingCallbacks] and [DesktopAudioPlayer.onEndPlayingCallbacks] properties.
 *
 * @property event The playback event associated with the audio stream.
 * @property sourceTag The source tag of the audio stream, which is set by the class that invoked the [Service]
 * functionality that invoked the callback.
 *
 * @see DesktopAudioPlayer
 * @see PlaybackEvent
 * @see CallbackManager
 * @see Service
 * @see Text2SpeechPlayer
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class PlaybackData(val event: PlaybackEvent?, override val sourceTag: String): CallbackInput


/**
 * A class that plays an audio streams. This class is mainly used for playing the text-to-speech synthesis on a desktop
 * computer, i.e., it is used for debugging purposes. This class should be given to an instance of the [Text2Speech]
 * abstract class.
 *
 * The audio can be played on a separate coroutine with [doComputeAsync], then the [stop] or [wait] function might
 * be used to interact with such a coroutine. Note that this class allows defining callbacks and timeout.
 *
 * @property onBeginPlayingCallbacks A set of callbacks that will be invoked when audio playback starts.
 * @property onEndPlayingCallbacks A set of callbacks that will be invoked when audio playback finishes.
 * @property player The audio player object able to play the audio on a desktop computer. However, it is a `private`
 * property. It is `null` when the audio is not playing.
 *
 * @see Service
 * @see Text2SpeechPlayer
 * @see Text2Speech
 * @see CallbackManager
 * @see FrequentTimeout
 * @see AwsPolly
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object DesktopAudioPlayer : Text2SpeechPlayer<PlaybackData>(){

    // See documentation above.
    override val onBeginPlayingCallbacks = CallbackManager<PlaybackData, Unit>(logger)
    override val onEndPlayingCallbacks = CallbackManager<PlaybackData, Unit>(logger)

    private var player: AdvancedPlayer? = null


    /**
     * Plays audio in a desktop computer from the given input stream in a separate coroutine. This method uses an
     * [AdvancedPlayer] that supports an MP3 input stream. It also invokes appropriate callbacks when playback starts
     * and finishes, i.e., the callbacks stored in [onBeginPlayingCallbacks] and [onErrorCallbacks].
     *
     * Note that this method runs on a try-catch block based on [doThrow], which invokes callbacks stored in the
     * [onErrorCallbacks] property. Also, this method is executed by [computeAsync], which allow defining a timeout
     * policy and related callback. Also note that  [resetTimeout] is never called in this implementation. See [Service]
     * for more.
     *
     * @param input The audio input stream containing the audio data to be played.
     * @param sourceTag An identifier that will be propagated to the callbacks associated with this class, i.e., on
     * begin playing, on end playing, on error, and on timeout.
     */
    override suspend fun doComputeAsync(input: InputStream, sourceTag: String) { // runs on a separate thread

        val initTime = measureTime {
            // This operation might be done only once if a `ReusableService` is used instead of a `Service`.
            player = AdvancedPlayer(
                input,
                FactoryRegistry.systemRegistry().createAudioDevice()
            )
        }
        logInfo("Creating a new Desktop Player (took: {})", initTime)

        player!!.playBackListener = object : PlaybackListener() {
            override fun playbackStarted(event: PlaybackEvent?) {
                logDebug("AWS Text-to-Speech playback started.")
                onBeginPlayingCallbacks.invoke(PlaybackData(event, sourceTag))
            }

            override fun playbackFinished(event: PlaybackEvent?) {
                logDebug("AWS Text-to-Speech playback started.")
                onEndPlayingCallbacks.invoke(PlaybackData(event, sourceTag))
            }
        }

        player!!.play() // Blocking call
        player?.close()
        player = null
    }


    /**
     * Stops the audio playback and closes the audio player before to actually stop the service. See [Service.stop] for
     * more.
     *
     * Note that this method runs on a try-catch block based on [doThrow], which invokes callbacks stored in the
     * [onErrorCallbacks] property.
     *
     * @param sourceTag An identifier that will be propagated to the [onErrorCallbacks] in case of exceptions.
     */
    override fun doStop(sourceTag: String) {
        //player?.stop()
        player?.close()
        player = null
        super.doStop(sourceTag)
    }
}