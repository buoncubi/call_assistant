package text2speech

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

//TODO adjust documentation with links [] and @see
// TODO add logging

/**
 * An abstract extension of `Text2SpeechAsync` designed to interact with AWS Polly
 * for synthesizing speech from text. This class provides functionality to initialize
 * and manage an AWS Polly client, configure voice settings, and fetch audio streams
 * for given text input using AWS Polly's text-to-speech capabilities.
 * 
 * The class implements support for starting and stopping the text-to-speech service
 * and ensures proper initialization and resource cleanup of the AWS Polly client and voice.
 * 
 * Be sure to invoke `start()` after constructor for starting th AWS Polly service
 * and being able to perform text-to-speech processing.
 * 
 * See `AwsPollyRunner.kt` for an example of how to use this class.
 *
 * @see `Text2SpeechInterface`, `Text2SpeechAsync`, `AwsPolly`.
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class AwsPollyMute : Text2SpeechAsync<InputStream>() {

    /**
     * Companion object for the AwsPollyMute class.
     *
     * This companion object serves as a centralized location for constants used throughout
     * the class related to Amazon Polly's configuration and constraints.
     *
     * Constants defined in this object:
     * - VOICE_NAME: Specifies the default name of the Amazon Polly voice to be used (e.g., Joanna).
     * - VOICE_TYPE: Determines the type of voice engine to be utilized. The possible types are
     *               "standard", "neural", "long-form", or "generative".
     * - REGION: Represents the AWS region where Amazon Polly services are configured to operate
     *           (e.g., eu-central-1).
     * - MAX_TEXT_LENGTH: Defines the maximum allowable length for input text in characters. For
     *                    standard voices, the limit is 3000 characters; for neural voices, the
     *                    limit is 1500 characters.
     */
    companion object {
        private const val VOICE_NAME = "Joanna"
        private const val VOICE_TYPE = "standard" // "standard", "neural", "long-form" or "generative"
        private const val REGION = "eu-central-1"
        private const val MAX_TEXT_LENGTH = 3000  // max 3000 characters for standard and 1500 for neural voices
    }

    // It is set as a private var such that it is not mutable from outside this class (see `isRunning()`).
    private var isRunning: Boolean = false

    /**
     * Represents the running state of the `AwsPollyMute` process, which is implemented by the `Text2SpeechInterface`.
     * 
     * This variable indicates if the operations within the class are currently active.
     * A value of `true` implies that the service started, whereas a value of `false` implies that it stooped.
     * If it is `false`, it is not possible to `fetchAudio`, `playAudio` (and `speak`) even asynchronously.
     *
     * @return `true` if the instance is running; otherwise, `false`.
     */
    override fun isRunning(): Boolean {
        return isRunning
    }

    /**
     * Represents a private and nullable instance of `PollyClient`, used to interact with AWS Polly services.
     * This field is lazily initialized through the `start` method and serves as the primary client
     * for text-to-speech synthesis operations within the class.
     * 
     * The initialization process involves configuring the AWS region and credentials provider. If the client
     * fails to initialize correctly, this field will remain `null`.
     */
    private var client: PollyClient? = null

    /**
     * Represents the `Voice` instance utilized for AWS Polly functionality.
     * This variable is initialized using the private `start` function,
     * which attempts to configure and retrieve a specified voice from AWS Polly's available voice options.
     * If the initialization fails, this variable remains `null`.
     * 
     * The `voice` variable is private to the `AwsPollyMute` class and is critical for
     * text-to-speech operations, serving as the voice configuration for the PollyClient. If the client or
     * the voice fail to initialize correctly, this field will remain null.
     */
    private var voice: Voice? = null

    /**
     * Initializes and configures the AWS Polly `client` for use with text-to-speech operations.
     * This function is invoked in the `start` method.
     * 
     * This method attempts to create a `PollyClient` using the specified AWS `REGION`
     * and default credentials. If an exception occurs during initialization, the method
     * will print the error message and return `null`.
     *
     * @return A properly initialized `PollyClient` instance if successful; `null` in case of an error.
     */
    private fun initializeClient(): PollyClient? {
        try {
            return PollyClient.builder()
                .region(Region.of(REGION))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()
        } catch (ex: Exception) {
            println("Error initializing AWS Polly client: ${ex.message}")
            ex.printStackTrace()
            return null
        }
    }

    /**
     * Initializes and retrieves the selected Amazon Polly `voice`.
     * This function is invoked in the `start` method
     * <o>
     * This method attempts to describe and retrieve voice configuration from Amazon Polly
     * based on the predefined `VOICE_NAME` and `VOICE_TYPE`. If the specified voice cannot be found
     * or an exception occurs during initialization, and `null` is returned.
     *
     * @return The initialized `Voice` object if successful; `null` otherwise.
     */
    private fun initializeVoice(): Voice? {
        try {

            val voiceRequest = DescribeVoicesRequest.builder()
                .engine(VOICE_TYPE).build()

            val voiceAttempt = client?.describeVoices(voiceRequest)?.voices()
                ?.firstOrNull { it.name() == VOICE_NAME }

            if (voiceAttempt == null) {
                println("Error: Voice '$VOICE_NAME' not found.")
                return null
            }
            return voiceAttempt

        } catch (ex: Exception) {
            println("Error initializing AWS Polly voice: ${ex.message}")
            ex.printStackTrace()
            return null
        }

    }

    /**
     * Determines if the `AwsPollyMute` object was successfully initialized.
     * The initialization is considered successful if both the `client` and `voice` are non-null.
     *
     * @return `true` if the `client` and `voice` are successfully initialized; otherwise, `false`.
     */
    private fun successfullyInitialized(): Boolean {
        return (client != null) and (voice != null)  // TODO is it requires? Can we rely on `isRunning()` instead?
    }

    /**
     * Starts the `AwsPollyMute` instance by initializing necessary components such as the Polly client
     * and voice. If already started or initialization fails, and it will not proceed further.
     * Only with a started service it is possible to perform `fetchAudio`, `playAudio`, `speak`,
     * `fetchAudioAsync`, `playAudioAsync` and `speakAsync`.
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
            println("Warning: not starting `awsExamples.AwsPolly` instance because it was not successfully initialized.")
            return false
        }
        // Polly is up and running.
        isRunning = true
        return true
    }

    /**
     * Stops the `AwsPollyMute` instance, halting any ongoing text-to-speech operations and releasing resources.
     * The method ensures that the instance cannot be stopped unless it is running and successfully initialized.
     * With a started service it is not possible to perform `fetchAudio`, `playAudio`, `speak`, `fetchAudioAsync`,
     * `playAudioAsync` and `speakAsync`.
     *
     * @return `true` if the instance was successfully stopped, `false` otherwise.
     */
    override fun stop(): Boolean {
        if (!super.stop()) {
            // Do not stop if it is already started.
            return false
        }
        if (!successfullyInitialized()) {
            println("Warning: not stopping `awsExamples.AwsPolly` instance because it was not successfully initialized.")
            return false
        }

        try {
            // Release Polly's resources
            client!!.close()
            isRunning = false
            return true
        } catch (ex: Exception) {
            println("Error while closing Polly's resources: ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }

    /**
     * Fetches synthesized speech audio for the given text input using Amazon Polly.
     * This method checks if the instance is successfully initialized and ensures
     * that the text length is within Polly's character limit before processing the
     * request. In case of any errors or exceptions, the method returns null.
     * 
     * This is the function used to implement the `speak`, `speakAsynch`, and `fetchAudioAsync`.
     * Its returning value is designed to be given to the `playAudio` and `playAudioAsync` functions.
     *
     * @param text The input text to synthesize into speech. Must not exceed the maximum character limit
     *             defined by Amazon Polly for the selected voice type.
     *
     * @return An InputStream representing the audio data in MP3 format if the synthesis is successful;
     *         otherwise, null.
     */
    override fun fetchAudio(text: String): InputStream? {
        if (!successfullyInitialized()) {
            println("Warning: not getting audio because `awsExamples.AwsPolly` instance was not successfully initialized.")
            return null
        }
        if (text.length > MAX_TEXT_LENGTH) {
            println("Error: text exceeds Polly's $MAX_TEXT_LENGTH character limit for $VOICE_TYPE voices")
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
            println("Audio synthesized successfully")
            return audioStream

        } catch (ex: Exception) {
            println("Error while getting audio from Polly: ${ex.message}")
            ex.printStackTrace()
            return null
        }
    }

}

/**
 * A class that extends `AwsPollyMute` and provides functionality to playAudio
 * audio streams produced by AWS Polly's text-to-speech synthesis.
 * 
 * This class overrides the abstract `playAudio` function to handle audio playback
 * for streams using a third-party library for MP3 decoding and playback. The
 * `playAudio` method ensures that audio files are played synchronously and logs
 * the playback status. Also, callbacks can be added for being notified when a
 * recording starts and ends.
 * 
 * This implementation of the `Text2SpeechInterface` invokes `start` at constructor.
 * Thus, you should do not explicitly invoke `start` the first time. However,
 * but you can still `stop` and `start` again the AWS Polly service if required.
 * 
 * See `AwsPollyRunner.kt` for an example of how to use this class.
 *
 * @see `Text2SpeechInterface`, `Text2SpeechAsync`, `AwsPollyMute`. `AwsPollyRunner.kt`.
 *
 * @author Luca Buoncompagni © 2025
 */
class AwsPolly : AwsPollyMute() {
    // Mutex used to manage callbacks
    private val startPlayingCallbacks: MutableSet<((event: PlaybackEvent?) -> Unit)> = mutableSetOf()
    private val endPlayingCallbacks: MutableSet<((event: PlaybackEvent?) -> Unit)> = mutableSetOf()

    /**
     * Construct this object by calling the `start` function.
     */
    init {
        start()
    }

    /**
     * Adds a callback that will be invoked when audio playback starts.
     *
     * @param callback A lambda function to handle the playback start event.
     *                 The event parameter `event` may be `null`.
     *
     * @return A boolean indicating whether the callback was successfully added.
     *         Returns `true` if the callback was added, `false` otherwise.
     */
    fun addOnStartPlayingCallback(callback: ((event: PlaybackEvent?) -> Unit)): Boolean {
        synchronized(startPlayingCallbacks) {
            return startPlayingCallbacks.add(callback)
        }
    }

    /**
     * Adds a callback that will be invoked when audio playback ends.
     *
     * @param callback A lambda function to handle the playback end event.
     *                 The parameter `event` may be `null`.
     *
     * @return A boolean indicating whether the callback was successfully added.
     *         Returns `true` if the callback was added, `false` otherwise.
     */
    fun addOnEndPlayingCallback(callback: ((event: PlaybackEvent?) -> Unit)): Boolean {
        synchronized(endPlayingCallbacks) {
            return endPlayingCallbacks.add(callback)
        }
    }

    /**
     * Adds both start and end playback callbacks for audio playback events.
     *
     * @param startCallback A lambda function to handle the playback start event.
     *                      The event parameter `event` may be `null`.
     * @param endCallback A lambda function to handle the playback end event.
     *                    The parameter `event` may be `null`.
     *
     * @return A boolean indicating whether both callbacks were successfully added.
     *         Returns `true` if both start and end callbacks were added, `false` otherwise.
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
     * This method uses an advanced audio player to play the audio data provided
     * as an MP3 input stream. It also invokes appropriate callbacks when playback
     * starts and finishes.
     *
     * @param audio The input stream containing the audio data to be played.
     *
     * @return A boolean indicating whether the audio playback was successfully initiated.
     *         Returns true if playback started successfully, false otherwise.
     */
    override fun playAudio(audio: InputStream): Boolean {
        try {
            val player = AdvancedPlayer(
                audio,
                FactoryRegistry.systemRegistry().createAudioDevice()
            )

            player.playBackListener = object : PlaybackListener() {
                override fun playbackStarted(event: PlaybackEvent?) {
                    println("Playback started")
                    synchronized(startPlayingCallbacks) {
                        startPlayingCallbacks.forEach { it.invoke(event) }
                    }
                }

                override fun playbackFinished(event: PlaybackEvent?) {
                    println("Playback finished")
                    synchronized(endPlayingCallbacks) {
                        endPlayingCallbacks.forEach { it.invoke(event) }
                    }
                }
            }

            println("Audio playback started")
            player.play()

            return true
        } catch (ex: Exception){
            when (ex){
                is JavaLayerException -> println("Error while playing audio: ${ex.message}")
                else -> println("Generic error while playing audio: ${ex.message}")
            }
            ex.printStackTrace()
            return false
        }
    }
}