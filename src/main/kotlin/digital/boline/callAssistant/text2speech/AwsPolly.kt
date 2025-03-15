package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
import javazoom.jl.decoder.JavaLayerException
import javazoom.jl.player.FactoryRegistry
import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackEvent
import javazoom.jl.player.advanced.PlaybackListener
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.DescribeVoicesRequest
import software.amazon.awssdk.services.polly.model.OutputFormat
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest
import software.amazon.awssdk.services.polly.model.Voice
import java.io.InputStream


/**
 * An abstract extension of [Text2SpeechAsync] designed to interact with AWS Polly for synthesizing speech from text.
 * This class provides functionality to initialize and manage an AWS Polly client, configure voice settings, and fetch
 * audio streams for given text input using AWS Polly's text-to-speech capabilities. Also, it implements support for
 * starting and stopping the text-to-speech service and ensures proper initialization and resource cleanup of the AWS
 * Polly client and voice. However, it does not provide a mechanism for directly playing the audio stream.
 * 
 * Be sure to invoke [start] after constructor for starting th AWS Polly service and being able to perform
 * text-to-speech processing.
 *
 * This class requires (i.e., default values are not given) the following virtual environment variables:
 *  - `AWS_POLLY_VOICE_NAME`: see [VOICE_NAME],
 *  - `AWS_POLLY_VOICE_TYPE`: see [VOICE_TYPE],
 *  - `AWS_REGION`: see [AWS_VENV_REGION],
 *  - `AWS_ACCESS_KEY_ID`:  see `AWS_ACCESS_KEY_ID`, and [DefaultCredentialsProvider],
 *  - `AWS_SECRET_ACCESS_KEY`: see `AWS_SECRET_ACCESS_KEY`, and [DefaultCredentialsProvider].
 * 
 * See `AwsPollyRunner.kt` in the test src folder, for an example of how to use this class.
 *
 * @see Text2SpeechInterface
 * @see Text2SpeechAsync
 * @see AwsPolly
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class AwsPollyMute : Text2SpeechAsync<InputStream>() {

    /** Companion object for the AwsPollyMute class. It defines requires environmental variables. */
    companion object {

        /**
         * Specifies the default name of the Amazon Polly voice to be used (e.g., `Bianca`). This value is required
         * thorough the compulsory environmental variable `AWS_POLLY_VOICE_NAME`.
         */
        private val VOICE_NAME = System.getenv("AWS_POLLY_VOICE_NAME") // ?: "Bianca"

        /**
         * Determines the type of voice engine to be utilized. The possible types are `standard`, `neural`,
         * `long-form`, or `generative`. This value is required thorough the compulsory environmental variable
         * `AWS_POLLY_VOICE_TYPE`.
         */
        private val VOICE_TYPE = System.getenv("AWS_POLLY_VOICE_TYPE") // ?: "standard"

        /**
         * Defines the maximum allowable length for input text in characters. For standard voices, the
         * limit is 3000 characters when SSML formatting is not used.
         */
        private const val MAX_TEXT_LENGTH = 3000
    }


    /**
     * Represents a private and nullable instance of [PollyClient], used to interact with AWS Polly services. This field
     * is lazily initialized through the [start] method and serves as the primary client for text-to-speech synthesis
     * operations within the class.
     * 
     * The initialization process involves configuring the AWS region and credentials provider. If the client fails to
     * initialize correctly, this field will remain `null`.
     */
    private var client: PollyClient? = null


    /**
     * Represents the [Voice] instance utilized for AWS Polly functionality. This variable is initialized using the
     * private [start] function, which attempts to configure and retrieve a specified voice from AWS Polly's available
     * voice options. If the initialization fails, this variable remains `null`.
     * 
     * The [voice] variable is private to the [AwsPollyMute] class and is critical for text-to-speech operations,
     * serving as the voice configuration for the [PollyClient]. If the client or the voice fail to initialize correctly,
     * this field will remain null.
     */
    private var voice: Voice? = null


    /**
     * Initializes and configures the AWS Polly `client` for use with text-to-speech operations. This function is
     * invoked in the [start] method.
     * 
     * This method attempts to create a [PollyClient] using the specified AWS [REGION] and default credentials. If an
     * exception occurs during initialization, the method will print the error message and return `null`.
     *
     * @return A properly initialized [PollyClient] instance if successful; `null` in case of an error.
     */
    private fun initializeClient(): PollyClient? {
        try {
            return PollyClient.builder()
                .region(Region.of(AWS_VENV_REGION))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()
        } catch (ex: Exception) {
            logError("Cannot initialize AWS Polly client.", ex)
            return null
        }
    }


    /**
     * Initializes and retrieves the selected Amazon Polly [Voice]. This function is invoked in the [start] method.
     *
     * This method attempts to describe and retrieve voice configuration from Amazon Polly based on the predefined `
     * [VOICE_NAME] and [VOICE_TYPE]. If the specified voice cannot be found or an exception occurs during
     * initialization, and `null` is returned.
     *
     * @return The initialized [Voice] object if successful; `null` otherwise.
     */
    private fun initializeVoice(): Voice? {
        try {

            val voiceRequest = DescribeVoicesRequest.builder()
                .engine(VOICE_TYPE).build()

            val voiceAttempt = client?.describeVoices(voiceRequest)?.voices()
                ?.firstOrNull { it.name() == VOICE_NAME }

            if (voiceAttempt == null) {
                logError("AWS Polly's voice '$VOICE_NAME' not found.")
                return null
            } else {
                logInfo("Setting AWS Polly with voice '{}'.", VOICE_NAME)
            }
            return voiceAttempt

        } catch (ex: Exception) {
            logError("Cannot initialize AWS Polly voice.", ex)
            return null
        }

    }


    /**
     * Determines if the [AwsPollyMute] object was successfully initialized. The initialization is considered successful
     * if both the [client] and [voice] are non-null.
     *
     * @return `true` if the [client] and [voice] are successfully initialized; otherwise, `false`.
     */
    private fun successfullyInitialized(): Boolean {
        return (client != null) and (voice != null)  // The returning value should always be equal to `isRunning`.
    }


    /**
     * Starts the [AwsPollyMute] instance by initializing necessary components such as the Polly client and voice. If
     * already started or initialization fails, and it will not proceed further. Only with a started service it is
     * possible to perform [fetchAudio], [playAudio], [speak], [fetchAudioAsync], [playAudioAsync], and [speakAsync].
     *
     * @return `true` if the instance starts successfully, or `false` otherwise.
     */
    override fun start(): Boolean {
        if (!super.start()) {
            // Do not start if it is already started.
            return false
        }
        // Initialize, which make it automatically start
        client = initializeClient()
        voice = initializeVoice()
        // Return false if there were issues during initialization
        if (!successfullyInitialized()) {
            logWarn("Cannot start AWS Polly instance because it was not successfully initialized.")
            return false
        }
        // Polly is up and running.
        serverRunning = true
        logInfo("AWS Polly instance started successfully.")
        return true
    }


    /**
     * Stops the [AwsPollyMute] instance, halting any ongoing text-to-speech operations and releasing resources. The
     * method ensures that the instance cannot be stopped unless it is running and successfully initialized. With a
     * started service it is not possible to perform [fetchAudio], [playAudio], [speak], [fetchAudioAsync],
     * [playAudioAsync] and [speakAsync].
     *
     * @return `true` if the instance was successfully stopped, `false` otherwise.
     */
    override fun stop(): Boolean {
        if (!super.stop()) {
            // Do not stop if it is already started.
            return false
        }
        if (!successfullyInitialized()) {
            logWarn("Cannot stop AWS Polly instance because it was not successfully initialized.")
            return false
        }

        try {
            // Release Polly's resources
            client!!.close()
            serverRunning = false
            logInfo("AWS Polly instance stopped successfully.")
            return true
        } catch (ex: Exception) {
            logError("Cannot close AWS Polly's resources.", ex)
            return false
        }
    }


    /**
     * Fetches synthesized speech audio for the given text input using Amazon Polly. This method checks if the instance
     * is successfully initialized and ensures that the text length is within Polly's character limit before processing
     * the request. In case of any errors or exceptions, the method returns `null`.
     * 
     * This is the function used to implement the [speak], [speakAsync], and [fetchAudioAsync]. Its returning value is
     * designed to be given to the [playAudio] and [playAudioAsync] functions.
     *
     * @param text The input text to synthesize into speech. Must not exceed the maximum character limit defined by
     * Amazon Polly for the selected voice type.
     *
     * @return An InputStream representing the audio data in MP3 format if the synthesis is successful; otherwise,
     * `null`.
     */
    override fun fetchAudio(text: String): InputStream? {
        if (!successfullyInitialized()) {
            logWarn("Cannot get audio because Aws Polly instance was not successfully initialized.")
            return null
        }
        if (text.length > MAX_TEXT_LENGTH) {
            logError("Text exceeds AWS Polly's $MAX_TEXT_LENGTH character limit for $VOICE_TYPE voices.")
            // TODO do something if this happens
            return null
        }

        try {
            // Send request to Polly
            val synthReq = SynthesizeSpeechRequest.builder()
                .text(text)
                .voiceId(voice!!.id())
                .outputFormat(OutputFormat.MP3)
                .build()
            val audioStream = client!!.synthesizeSpeech(synthReq)

            logInfo("Audio synthesized successfully.")
            return audioStream

        } catch (ex: Exception) {
            logError("Cannot get audio from Polly", ex)
            return null
        }
    }

}

/**
 * A class that extends [AwsPollyMute] and provides functionality to [playAudio] audio streams produced by AWS Polly's
 * text-to-speech synthesis.
 * 
 * This class overrides the abstract [playAudio] function to handle audio playback for streams using a third-party
 * library for MP3 decoding and playback. The [playAudio] method ensures that audio files are played synchronously and
 * logs the playback status. Also, callbacks can be added for being notified when a recording starts and ends.
 * 
 * This implementation of the [Text2SpeechInterface] invokes [start] at constructor. Thus, you should do not explicitly
 * invoke [start] the first time. However, but you can still [stop] and [start] again the AWS Polly service if required.
 * 
 * See `AwsPollyRunner.kt` in the test src folder, for an example of how to use this class.
 *
 * @see Text2SpeechInterface
 * @see Text2SpeechAsync
 * @see AwsPollyMute
 *
 * @author Luca Buoncompagni © 2025
 */
class AwsPolly : AwsPollyMute() {
    // Mutex used to manage callbacks
    private val startPlayingCallbacks: MutableSet<((event: PlaybackEvent?) -> Unit)> = mutableSetOf()
    private val endPlayingCallbacks: MutableSet<((event: PlaybackEvent?) -> Unit)> = mutableSetOf()


    init {
        start()
    }


    /**
     * Adds a callback that will be invoked when audio playback starts.
     *
     * @param callback A lambda function to handle the playback start event. The event parameter `event` may be `null`.
     *
     * @return A boolean indicating whether the callback was successfully added. Returns `true` if the callback was
     * added, `false` otherwise.
     */
    fun addOnStartPlayingCallback(callback: ((event: PlaybackEvent?) -> Unit)): Boolean {
        synchronized(startPlayingCallbacks) {
            logInfo("Adding callback for AWS Polly play starting event.")
            return startPlayingCallbacks.add(callback)
        }
    }


    /**
     * Adds a callback that will be invoked when audio playback ends.
     *
     * @param callback A lambda function to handle the playback end event. The parameter `event` may be `null`.
     *
     * @return A boolean indicating whether the callback was successfully added. Returns `true` if the callback was
     * added, `false` otherwise.
     */
    fun addOnEndPlayingCallback(callback: ((event: PlaybackEvent?) -> Unit)): Boolean {
        synchronized(endPlayingCallbacks) {
            logInfo("Adding callback for AWS Polly play ending event.")
            return endPlayingCallbacks.add(callback)
        }
    }


    /**
     * Adds both start and end playback callbacks for audio playback events.
     *
     * @param startCallback A lambda function to handle the playback start event. The event parameter `event` may be
     * `null`.
     *
     * @param endCallback A lambda function to handle the playback end event. The parameter `event` may be `null`.
     *
     * @return A boolean indicating whether both callbacks were successfully added. Returns `true` if both start and
     * end callbacks were added, `false` otherwise.
     */
    fun addOnPlayCallbacks(startCallback: ((event: PlaybackEvent?) -> Unit),
                           endCallback: ((event: PlaybackEvent?) -> Unit)): Boolean {
        val outcome1 = addOnStartPlayingCallback(startCallback)
        val outcome2 = addOnEndPlayingCallback(endCallback)
        return outcome1 and outcome2
    }


    /**
     * Plays audio from the given input stream.
     * 
     * This method uses an advanced audio player to play the audio data provided as an MP3 input stream. It also invokes
     * appropriate callbacks when playback starts and finishes.
     *
     * @param audio The input stream containing the audio data to be played.
     *
     * @return A boolean indicating whether the audio playback was successfully initiated. Returns `true` if playback
     * started successfully, `false` otherwise.
     */
    override fun playAudio(audio: InputStream): Boolean {
        try {
            val player = AdvancedPlayer(
                audio,
                FactoryRegistry.systemRegistry().createAudioDevice()
            )

            player.playBackListener = object : PlaybackListener() {
                override fun playbackStarted(event: PlaybackEvent?) {
                    logInfo("AWS Polly playback started.")
                    synchronized(startPlayingCallbacks) {
                        startPlayingCallbacks.forEach { it.invoke(event) }
                    }
                }

                override fun playbackFinished(event: PlaybackEvent?) {
                    logInfo("AWS Polly playback finished.")
                    synchronized(endPlayingCallbacks) {
                        endPlayingCallbacks.forEach { it.invoke(event) }
                    }
                }
            }

            player.play()
            return true

        } catch (ex: Exception){
            when (ex){
                is JavaLayerException ->
                    logError("Error while playing AWS Polly's audio.", ex)
                else ->
                    logError("Generic error while playing audio.", ex)
            }
            return false
        }
    }
}