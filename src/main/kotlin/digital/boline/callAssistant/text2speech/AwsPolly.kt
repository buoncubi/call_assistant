package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
import digital.boline.callAssistant.ErrorSource // Only used for documentation
import digital.boline.callAssistant.ReusableService
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.polly.PollyClient
import software.amazon.awssdk.services.polly.model.DescribeVoicesRequest
import software.amazon.awssdk.services.polly.model.OutputFormat
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest
import software.amazon.awssdk.services.polly.model.Voice
import java.io.InputStream



/**
 * The implementation of [Text2Speech] based on AWS Polly.
 *
 * It implements a [ReusableService] for fetching the audio, and it requires a [Text2SpeechPlayer] for plying it. All
 * computation is made asynchronously by the means of coroutines. Note that this class allows defining timeout and
 * callbacks as shown in the example below.
 *
 * A minimal example for using this class is:
 * ```
 *      // Initialize the audio player.
 *      val player = DesktopAudioPlayer
 *      player.onEndPlayingCallbacks.add { event: PlaybackEvent? ->
 *          logger.info("End audio player callback: $event")
 *      }
 *
 *      // Initialize the AWS Polly.
 *      val polly = AwsPolly(player)
 *      polly.onErrorCallbacks.add {se: ServiceError ->
 *          logger.error("Error callback $se")
 *      }
 *
 *      // Initialize Polly's resources.
 *      polly.activate()
 *
 *      // Use AWS Polly and manually stop it.
 *      polly.computeAsync("Hello")
 *      Thread.sleep(200)
 *      polly.stop()
 *
 *      // Use AWS Polly with optional timeout.
 *      val computingTimeout = FrequentTimeout(timeout = 20_000, checkPeriod = 200){
 *          println("Polly computing timeout!")
 *      }
 *      val waitingTimeout = Timeout(timeout = 10_000) {
 *          println("Polly waiting timeout!")
 *      }
 *      polly.computeAsync("World.", computingTimeout)
 *      polly.wait(waitingTimeout)
 *
 *      // Close Polly's resources
 *      polly.deactivate()
 *
 *      // You might want to activate Polly again and perform some computation ...
 * ```
 * See `AwsPollyRunner.kt` in the test src folder, for an example of how to use this class.
 *
 *
 * This class requires (since default values are not given) the following virtual environment variables:
 *  - `AWS_POLLY_VOICE_NAME`: see [VOICE_NAME],
 *  - `AWS_POLLY_VOICE_TYPE`: see [VOICE_TYPE],
 *  - `AWS_REGION`: see [AWS_VENV_REGION],
 *  - `AWS_ACCESS_KEY_ID`,
 *  - `AWS_SECRET_ACCESS_KEY`.
 * 
 *
 * @param C The type of the callbacks input required by the [Text2SpeechPlayer] instance.
 *
 * @property client The AWS client for interacting with AWS Polly services. It is set by [activate] and closed by
 * [deactivate]. When this service is not active, or if the activation failed, then `client` will be `null`. However,
 * note that this property is `private`.
 * @property voice The AWS Polly voice to be used for text-to-speech synthesis. It is set by [activate] and closed by
 * [deactivate]. When this service is not active, or if the activation failed, then `voice` will be `null`. However,
 * note that this property is `private`.
 * @property player The object that will play the audio. It is required at construction time. However, this is a
 * `private` property.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see Text2Speech
 * @see Text2SpeechPlayer
 * @see DesktopAudioPlayer
 * @see ReusableService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class AwsPolly<C>(player: Text2SpeechPlayer<C>) : Text2Speech(player) {

    // See documentation above.
    private var client: PollyClient? = null
    private var voice: Voice? = null


    /**
     * Initializes and configures the AWS Polly `client` for text-to-speech processing. This function is invoked in the
     * [activate] method.
     * 
     * This method attempts to create a [PollyClient] using the specified AWS [AWS_VENV_REGION] and AWS credentials
     * given through environmental variables, i.e., `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and
     * `AWS_SESSION_TOKEN`.
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     *
     * @return The initialized [PollyClient] object.
     */
    private fun initializeClient(): PollyClient =
        PollyClient.builder()
            .region(Region.of(AWS_VENV_REGION))
            .credentialsProvider(DefaultCredentialsProvider.create()) // TODO adjust credential provider
            /*.httpClient( // TODO to use?
                // It is faster with respect to Netty (default) httpClient but less stable
                // It requires `implementation("software.amazon.awssdk:aws-crt-client")` as gradle dependence
                AwsCrtHttpClient.builder()
                //.connectionTimeout(Duration.ofSeconds(1))
                .build()
            )*/
            .build()


    /**
     * Initializes and retrieves the selected Amazon Polly [Voice]. This function is invoked in the [activate] method.
     *
     * This method attempts to describe and retrieve voice configuration from Amazon Polly based on the predefined
     * [VOICE_NAME] and [VOICE_TYPE].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     *
     * @return The initialized [Voice] object.
     * @throws NullPointerException when [voice] cannot be successfully initialized. Note that it will be caught by
     * [doThrow].
     */
    private fun initializeVoice(): Voice {
       val voiceRequest = DescribeVoicesRequest.builder()
            .engine(VOICE_TYPE).build()

        val voiceAttempt = client?.describeVoices(voiceRequest)?.voices()
            ?.firstOrNull { it.name() == VOICE_NAME }

        if (voiceAttempt == null) {
            logError("Service '{}' did not initialize AWS Polly voice successfully.", serviceName)
            throw NullPointerException("AWS Polly's voice '$VOICE_NAME' not found.")
        }

        return voiceAttempt
    }


    /**
     * Starts the AWS Polly service by initializing necessary components such as the Polly [client] (by the mean of
     * [initializeClient]) and [voice] (through [initializeVoice]). This method is invoked by [activate].
     *
     * If the service already started or initialization fails, then this method does nothing (See [ReusableService] for
     * more).
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     */
    override fun doActivate() {
        // Initialize, which make it automatically activate
        client = initializeClient()
        voice = initializeVoice()
        // If the function above throw and exception, it will be caught by `doThrow`, and `activate` returns false.
    }


    /**
     * Close tha AWS Polly service, in particular, it closes and sets to `null` [client] and [voice].
     *
     * This method is invoked by [deactivate], and it does nothing if the service is computing, or if it is not
     * activated (see [ReusableService] for more).
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.DEACTIVATING].
     */
    override fun doDeactivate() {
        // Release Polly's resources
        client!!.close()
        client = null
        voice = null
    }


    /**
     * Fetches synthesized speech audio for the given text input using Amazon Polly. This method is invoked by
     * [computeAsync] when the service has been successfully activated, and if it is not already computing.
     * checks if the instance
     *
     * In case of any errors or exceptions, the method returns `null`. Note that this method runs in a try-catch block
     * managed by [doThrow], which invokes callbacks set through  [onErrorCallbacks] with `errorSource` set to
     * [ErrorSource.COMPUTING].
     *
     * @param text The input text to synthesize into speech. Must not exceed the maximum character limit defined by
     * Amazon Polly [MAX_TEXT_LENGTH], which might depend on the selected [voice] type.
     * @return An InputStream representing the audio data in MP3 format if the synthesis is successful; otherwise,
     * `null`.
     */
    override fun fetchAudio(text: String): InputStream? {
        if (text.length > MAX_TEXT_LENGTH) {
            logError("Text exceeds AWS Polly's $MAX_TEXT_LENGTH character limit for $VOICE_TYPE voices.")
            // TODO do something if this happens
            return null
        }

        // Send request to Polly
        val synthReq = SynthesizeSpeechRequest.builder()
            .text(text)
            .voiceId(voice!!.id())
            .outputFormat(OutputFormat.MP3)
            .build()
        return client!!.synthesizeSpeech(synthReq)
    }


    companion object {

        // TODO make a class collecting AWS environmental config

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

}
