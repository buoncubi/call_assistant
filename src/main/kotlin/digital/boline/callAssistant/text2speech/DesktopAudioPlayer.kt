package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.CallbackManager
import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Service
import javazoom.jl.player.FactoryRegistry
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
import java.io.InputStream



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
object DesktopAudioPlayer : Text2SpeechPlayer<PlaybackEvent?>(){

    // See documentation above.
    override val onBeginPlayingCallbacks = CallbackManager<PlaybackEvent?, Unit>(logger)
    override val onEndPlayingCallbacks = CallbackManager<PlaybackEvent?, Unit>(logger)

    private var player: AdvancedPlayer? = null


    /**
     * Plays audio in a desktop computer from the given input stream in a separate coroutine. This method uses an
     * [AdvancedPlayer] that supports an MP3 input stream. It also invokes appropriate callbacks when playback starts
     * and finishes.
     *
     * Note that this method runs on a try-catch block based on [doThrow], which invokes callbacks stored in the
     * [onErrorCallbacks] property. Also, this method is executed by [computeAsync], which allow defining a timeout
     * policy. Also note that  [resetTimeout] is never called in this implementation. See [Service] for more.
     *
     * @param input The audio input stream containing the audio data to be played.
     */
    override suspend fun doComputeAsync(input: InputStream) { // runs on a separate thread
        player = AdvancedPlayer( // TODO check how much time it requires and, eventually change it to a ReusableService
            input,
            FactoryRegistry.systemRegistry().createAudioDevice()
        )

        player!!.playBackListener = object : PlaybackListener() {
            override fun playbackStarted(event: PlaybackEvent?) {
                logDebug("AWS Text-to-Speech playback started.")
                onBeginPlayingCallbacks.invoke(event)
            }

            override fun playbackFinished(event: PlaybackEvent?) {
                logDebug("AWS Text-to-Speech playback started.")
                onEndPlayingCallbacks.invoke(event)
            }
        }

        player!!.play()
        player?.close()
        player = null
    }


    /**
     * Stops the audio playback and closes the audio player before to actually stop the service. See [Service.stop] for
     * more.
     *
     * Note that this method runs on a try-catch block based on [doThrow], which invokes callbacks stored in the
     * [onErrorCallbacks] property.
     */
    override fun doStop() {
        player?.stop()
        player?.close()
        player = null
        super.doStop()
    }
}