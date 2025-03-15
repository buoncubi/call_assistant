package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient
import software.amazon.awssdk.services.transcribestreaming.model.*
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*

// TODO activate AWS transcribe only if there is some voice (reduce cost on silence calls)
// TODO restart transcribe if it gets close where there are more than 15 seconds of silence

/**
 * It is a class implementing the [Speech2TextInterface] based on AWS Transcribe Streaming service for real-time audio
 * transcription. It parametrises the audio input as [InputStream], while the outputs are AWS [Result]. However, this
 * class is not instantiable since it proves a more abstract implementation, which is agonist by the type actual source
 * of input stream.
 * 
 * See `AwsTranscribeRunner.kt` in the test src folder, for an example of how to use this class.
 *
 * This class requires (i.e., default values are not given) the following virtual environment variables:
 *  - `AWS_TRANSCRIBE_LANGUAGE`: see [LANGUAGE_CODE],
 *  - `AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE`: see [AUDIO_STREAM_CHUNK_SIZE_IN_BYTES],
 *  - `AWS_REGION`: see [AWS_VENV_REGION],
 *  - `AWS_ACCESS_KEY_ID`:  see `AWS_ACCESS_KEY_ID`, and [DefaultCredentialsProvider],
 *  - `AWS_SECRET_ACCESS_KEY`: see `AWS_SECRET_ACCESS_KEY`, and [DefaultCredentialsProvider].
 *
 * @property serverListening Boolean
 * @property callbacks MutableSet<Function1<Result, Unit>>
 * @property client TranscribeStreamingAsyncClient?
 * @property request StartStreamTranscriptionRequest?
 * @property responseHandler StartStreamTranscriptionResponseHandler
 *
 * @see Speech2TextInterface
 * @see AwsTranscribeFromMicrophone
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class AwsTranscribe : Speech2TextAsynch<InputStream?, Result>() {

    /** Companion object for the AwsPollyMute class. It defines requires environmental variables. */
    protected companion object {

        /**
         * The language code for the transcription, e.g., `it-IT`. This value is required from the
         * environmental variable `AWS_TRANSCRIBE_LANGUAGE`.
         */
        private val LANGUAGE_CODE = System.getenv("AWS_TRANSCRIBE_LANGUAGE") // ?: "it-IT"

        /**
         * The size of the audio buffer in bytes, e.g., 1024. This value is required from the environmental variable
         * `AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE` as an integer.
         */
        private val AUDIO_STREAM_CHUNK_SIZE_IN_BYTES = System.getenv("AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE").toInt() //orNull() ?:1024


        /**
         * The audio sampling rate in Hz, set to 16000Hz. This value is required from the environmental variable
         * `AWS_TRANSCRIBE_SAMPLE_RATE` as an integer. Note that [AwsTranscribeFromMicrophone.MIC_SAMPLE_SIZE_IN_BIT]
         * is related to this value.
         * .
         */
        internal const val SAMPLE_RATE = 16000

        /**
         * The audio encoding format. This value is required from the environmental variable
         * `AWS_TRANSCRIBE_MEDIA_ENCODING`. Its value can only be `pcm`, `flac` or `ogg-opus`, which are respectively
         * related to [MediaEncoding.PCM], [MediaEncoding.FLAC] and [MediaEncoding.OGG_OPUS]. The characteristics of
         * these encoding are:
         *  - `pcm`: Uncompressed, with high quality but high latency as well.
         *  - `flac`: Uncompressed, with high quality, medium bandwidth and latency. It is a tradeoff between the other.
         *  - `ogg-opus`: Compressed, low quality and low latency as well.
         */
        private val MEDIA_ENCODING = MediaEncoding.PCM

    }


    /**
     * Provides an AWS Transcribe Streaming asynchronous client for real-time transcription operations.
     * 
     * This property lazily initializes the AWS [TranscribeStreamingAsyncClient] instance. During initialization,
     * it uses the `[DefaultCredentialsProvider]` and configures the client for a specific AWS region.
     * 
     * If an error occurs during the initialization it returns a `null` client.
     */
    private val client: TranscribeStreamingAsyncClient? = initAwsClient()


    /**
     * Initializes an asynchronous AWS Transcribe Streaming client.
     *
     * This method attempts to set up an instance of the AWS [TranscribeStreamingAsyncClient] with default credentials
     * and the region specified in the [REGION]. If the initialization succeeds, the client instance is returned;
     * otherwise, an exception is logged and the method returns ´null´.
     *
     * @return A configured instance of [TranscribeStreamingAsyncClient], or `null` if an error occurs during
     * initialization.
     */
    private fun initAwsClient(): TranscribeStreamingAsyncClient? {
        try {
            // Create a new AWS client with login credentials.
            logInfo("Initializing AWS Transcribe client...")
            return TranscribeStreamingAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(AWS_VENV_REGION))
                .build()
        } catch (ex: Exception) {
            logError("Error initializing the AWS Transcribe Client", ex)
            return null
        }
    }


    /**
     * Provides a lazily initialized [StartStreamTranscriptionRequest] for AWS Transcribe service.
     * 
     * This property constructs and returns a new transcription request with predefined language, encoding, and sample
     * rate settings. If an error occurs during the request creation, it logs the specific error and returns `null`.
     */
    private val request: StartStreamTranscriptionRequest? = initAwsRequest()


    /**
     * Initializes and constructs an AWS StartStreamTranscriptionRequest instance with the appropriate audio
     * configuration for AWS Transcribe Streaming.
     *
     * The method handles the creation of the request, setting necessary parameters such as language code, media
     * encoding, and sample rate. If an error occurs during the request creation process, it logs the specific error
     * and returns `null`.
     *
     * @return A [StartStreamTranscriptionRequest] instance configured for AWS Transcribe Streaming, or `null` if an
     * error occurs during the initialization process.
     */
    private fun initAwsRequest(): StartStreamTranscriptionRequest? {
        try {
            // Create a new AWS request with audio configuration
            logInfo("Creating AWS Transcribe request...")
            return StartStreamTranscriptionRequest.builder()
                .languageCode(LANGUAGE_CODE)
                .mediaEncoding(MEDIA_ENCODING)
                .mediaSampleRateHertz(SAMPLE_RATE)
                .build()
        } catch (ex: Exception) {
            val errorMessage = when (ex) {
                is IllegalArgumentException ->
                    "Invalid AWS Transcribe request parameters"
                else ->
                    "Generic error on AWS Transcribe request"
            }
            logError(errorMessage, ex)
            return null
        }
    }


    /**
     * Starts the AWS Transcribe service to process audio input for speech-to-text conversion.
     * 
     * This method initializes the process required to stream audio data using AWS Transcribe Streaming. It ensures that
     * both the client and the request are properly initialized before attempting to start the transcription service.
     * If the service is already in a listening state or an error occurs during the start process, it handles the
     * corresponding cases and provides feedback through logging. A successful start sets the [serverListening] flag to
     * `true`.
     *
     * @return `true` if the service was started successfully and is actively listening, `false` otherwise.
     */
    override fun startListening(): Boolean {
        if (!super.startListening())
            // Do not start listening a service that did not stop listening.
            return false

        try {
            if (client == null || request == null) {
                logWarn("Cannot start AWS Transcribe client since client or request is not initialized.")
                return false
            }

            // Start AWS transcribe service
            logInfo("Starting AWS Transcribe client.")
            val audioPublisher = AudioStreamPublisher(this.inputStream, logger)
            client.startStreamTranscription(request, audioPublisher, responseHandler)
            serverListening = true
            return true

        } catch (ex: Exception) {
            val errorMessage = when(ex) {
                is BadRequestException,
                is LimitExceededException,
                is InternalFailureException,
                is ConflictException,
                is ServiceUnavailableException,
                is SdkClientException,
                is TranscribeStreamingException,
                is SdkException,
                is IllegalStateException,
                is IOException ->
                    "Error starting the AWS Transcribe client"
                else ->
                    "Generic error on starting the AWS Transcribe client"
            }
            logError(errorMessage, ex)
            client?.close()
            return false
        }
    }


    /**
     * A [Publisher] implementation responsible for providing audio data from an [InputStream] to a [Subscriber]. This
     * is designed as part of a reactive stream framework to handle processing and delivering audio events, implementing
     * backpressure management and subscription lifecycle.
     *
     * This class supports only a single active subscriber at a time and facilitates audio event-driven communication by
     * reading data from the provided audio stream.
     *
     * @constructor Takes an [InputStream] as its source for audio data. If `null` is provided, the publisher will
     * notify subscribers of an error.
     *
     * @param inputStream The input stream source for audio data. It must remain open and available during the
     * subscription lifecycle.
     * @param logger A logger instance associated with the [AwsTranscribe] class.
     *
     * @see AwsTranscribe
     *
     * @author Luca Buoncompagni © 2025
     */
    private class AudioStreamPublisher(val inputStream: InputStream?, val logger: CentralizedLogger) : Publisher<AudioStream?> {

        /**
         * Represents the current active subscription for the [AudioStreamPublisher].
         * 
         * This variable holds a reference to a [Subscription] object, which is used to manage the flow of audio data
         * from the [inputStream] to a [Subscriber].
         * 
         * - Stores the subscription created during the [subscribe] method, allowing the publisher to manage the
         *   subscriber and related resources.
         * - Supports only a single subscriber. If a new subscriber is added, any existing subscription is cancelled
         *   before the new one is registered.
         * - Managed and cleared during lifecycle operations such as subscription updates or resource cleanup (e.g.,
         *   via the [Subscription.cancel] method).
         *
         * It can be `null` if no active subscription exists.
         */
        private var currentSubscription: Subscription? = null

        /**
         * Subscribes a [Subscriber] to the audio stream. This method establishes a subscription to the `inputStream` if
         * available, allowing the `Subscriber` to receive audio data.
         *
         * @param subscriber The object to be subscribed to the audio stream. It will receive audio data or error
         * notifications in case of failures.
         */
        override fun subscribe(subscriber: Subscriber<in AudioStream?>) {
            // Add a subscriber to the `inputStream`
            try {
                if (inputStream != null) {
                    // If it is not null, cancel its task before to overwrite with a new `SubscriptionImpl`
                    currentSubscription?.cancel()
                    currentSubscription = SubscriptionImpl(subscriber, inputStream, logger)
                    subscriber.onSubscribe(currentSubscription)
                    logger.info("Subscribed to audio input stream for speech recognition.")
                } else {
                    subscriber.onError(RuntimeException("Failed to access the audio input stream."))
                    currentSubscription?.cancel()
                }
            } catch (ex: Exception) {
                val errorMessage = when(ex) {
                    is SecurityException -> "Subscription failure due to audio input stream access. ({})"
                    is LineUnavailableException -> "Audio input stream unavailable for subscription. ({})"
                    else -> "Generic error during subscription to audio input stream. ({})"
                }
                logger.error(errorMessage, ex.message)
                subscriber.onError(ex)
                currentSubscription?.cancel()
            }
        }

        /**
         * Implementation of the Subscription interface that facilitates stream-based communication of audio data to a
         * subscriber. This class manages the lifecycle of a subscription, including handling requests for data,
         * managing backpressure, and cleaning up resources upon cancellation.
         * 
         * Handles fetching audio data from a provided input stream, delivering it in manageable chunks to an associated
         * subscriber, and managing the backpressure based on demands from the subscriber.
         *
         * @param subscriber The subscriber that will receive events representing audio data.
         * @param inputStream The input stream from which audio data is read.
         *
         * @see AudioStreamPublisher
         * @see AwsTranscribe
         *
         * @author Luca Buoncompagni © 2025
         */
        class SubscriptionImpl(
            private val subscriber: Subscriber<in AudioStream?>,
            private val inputStream: InputStream,
            private val logger: CentralizedLogger
        ) : Subscription {

            /**  ExecutorService responsible for managing the execution of  a single asynchronous tasks. */
            private val executor: ExecutorService = Executors.newFixedThreadPool(1)

            /**
             * Represents the current demand for processing audio events in a subscription implementation.
             * 
             * This variable tracks the number of requested audio events that need to be processed. It ensures
             * thread-safe operations for incrementing and decrementing the demand and is primarily used in the
             * [request] method to handle backpressure in a reactive stream context.
             * 
             * A non-negative value indicates the number of events that are yet to be processed, while the value is
             * decremented as each audio event is successfully processed or completed.
             */
            private val demand = AtomicLong(0)

            /**
             * Represents a ByteBuffer that holds the next chunk of audio data to be consumed. This is a computed
             * property that attempts to read audio data from an input stream and wraps it in a [ByteBuffer]. If the
             * read operation fails, `null` is returned.
             */
            private val nextEvent: ByteBuffer?
                get() {
                    try {
                        // Put audio data into a buffer.
                        val audioBytes = ByteArray(AUDIO_STREAM_CHUNK_SIZE_IN_BYTES)
                        val len: Int = inputStream.read(audioBytes)
                        val audioBuffer: ByteBuffer? = if (len <= 0) {
                            ByteBuffer.allocate(0)
                        } else {
                            ByteBuffer.wrap(audioBytes, 0, len)
                        }
                        return audioBuffer
                    } catch (ex: Exception) {
                        val errorMessage = when(ex) {
                            is IOException -> "Failed to read next audio chunk from audio input stream"
                            else -> "Generic error while reading audio chunk from audio input stream"
                        }
                        logger.error(errorMessage, ex)
                        return null
                    }
                }

            /**
             * Requests a specific number of items to be delivered to the subscriber.
             * 
             * If an error occurs, then [StartStreamTranscriptionResponseHandler.Builder.onError] is invoked.
             *
             * @param itemsNumber The number of items requested. Must be a positive number; otherwise, an error will be
             * reported to the subscriber.
             */
            override fun request(itemsNumber: Long) {
                if (itemsNumber <= 0) {
                    subscriber.onError(IllegalArgumentException("Demand must be positive"))
                    cancel()
                    return
                }

                // Get audio data in chunks and submit it into a thread
                demand.getAndAdd(itemsNumber)
                executor.submit {
                    try {
                        while (demand.decrementAndGet() >= 0) {
                            val audioBuffer = nextEvent
                            if (audioBuffer != null) {
                                if (audioBuffer.remaining() > 0) {
                                    val audioEvent = AudioEvent.builder()
                                        .audioChunk(SdkBytes.fromByteBuffer(audioBuffer))
                                        .build()
                                    subscriber.onNext(audioEvent)
                                } else {
                                    break
                                }
                            }
                        }
                        logger.info("Stop requesting next audio event for speech recognition.")
                    } catch (ex: Exception) {
                        val errorMessage = when(ex) {
                            is InterruptedException -> "Interrupted while waiting for next input audio event. ({})"
                            is UncheckedIOException -> "Failed to read next input audio event. ({})"
                            is RejectedExecutionException -> "Failed to submit next input audio event. ({})"
                            else -> "Generic error while reading next input audio event. ({})"
                        }
                        logger.error(errorMessage, ex.message)
                        subscriber.onError(RuntimeException("Task execution failed for next input audio event: ${ex.cause}", ex))
                        cancel()
                    }
                }
            }

            /**
             * Cancels the subscription to the audio channel and releases associated resources.
             * 
             * This method ensures that any active resources, such as streams or executors, are closed or shut down. It
             * also notifies the subscriber that the subscription is complete. This function is called by
             * [stopListening].
             * 
             * If an error occurs, then [StartStreamTranscriptionResponseHandler.Builder.onError] is invoked.
             */
            override fun cancel() {
                // This is called by `AwsTranscribe.stopListening()`
                try {
                    executor.shutdownNow()
                    subscriber.onComplete()
                    inputStream.close()
                    logger.info("Subscription to input audio stream cancelled.")
                } catch (ex: Exception) {
                    val errorMessage = when(ex) {
                        is SecurityException -> "Error while cancelling subscription to input audio stream. ({})"
                        is IOException -> "Error while closing input audio stream. ({})"
                        else -> "Generic error while cancelling subscription to input audio stream. ({})"
                    }
                    logger.error(errorMessage, ex.message)
                    subscriber.onError(RuntimeException("Task execution failed for input audio stream: ${ex.cause}", ex))
                }
            }
        }
    }


    /**
     * A property representing a response handler for managing AWS Transcribe streaming events.
     * 
     * This handler is responsible for:
     * - Reacting to different stages of the transcription process, such as when the service starts, completes, or
     *   encounters an error.
     * - Processing the transcription results and invoking registered callbacks with the final processed transcription
     *   data.
     * 
     * Key components handled by this response handler:
     * 1. [StartStreamTranscriptionResponseHandler.Builder.onResponse]: Triggered when the AWS Transcribe service sends
     *   an initial response, indicating successful initialization of the session.
     *
     * 2. [StartStreamTranscriptionResponseHandler.Builder.onError]: Invoked when any errors or exceptions are
     *   encountered during the transcription process. Error handling includes specific messages for known issues such
     *   as [TranscribeStreamingException] or [IOException].
     *
     * 3. [StartStreamTranscriptionResponseHandler.Builder.onComplete]: Executed when the transcription stream is
     *   successfully completed, either by finishing audio input or stopping the service explicitly.
     *
     * 4. [StartStreamTranscriptionResponseHandler.Builder.subscriber]: Processes [TranscriptResultStream] events from
     *    AWS Transcribe, extracting and formatting transcribed text data, and invoking callbacks with finalized results
     *    when available.
     * 
     * This property dynamically initializes an instance of [StartStreamTranscriptionResponseHandler] using a builder
     * pattern for managing individual handler events, ensuring modular and reactive handling throughout the
     * transcription session.
     */
    private val responseHandler: StartStreamTranscriptionResponseHandler
        get() = StartStreamTranscriptionResponseHandler.builder()
            .onResponse {
                // It is called as soon as the AWS Transcribe client starts.
                logInfo("Ready to use AWS Transcribe client.")
            }
            .onError { ex: Throwable ->
                // React to errors during AWS transcription.
                val errorMessage = when (ex) {
                    is TranscribeStreamingException -> "AWS Transcribe request failed during transcription"
                    is IOException -> "AWS Transcribe I/O error during transcription"
                    is IllegalStateException -> "AWS Transcribe state error during transcription"
                    else -> "Generic AWS Transcribe error during transcription"
                }
                logError(errorMessage, ex)
            }
            .onComplete {
                // Called if the audio is finished or if the AWS Transcribe client is closed with `stopListening`.
                logInfo("AWS Transcribe client completed its stream.")
            }
            .subscriber { event: TranscriptResultStream ->
                try {
                    // Get transcribed text for AWS.
                    val results = (event as TranscriptEvent).transcript().results()
                    if (results.isNotEmpty()) {
                        val result = results[0]
                        if (result.alternatives()[0].transcript().isNotEmpty()) {
                            logDebug("AWS Transcribe recognition on realtime: '{}' ...",
                                results[0].alternatives()[0].transcript())

                            if (!result.isPartial) {
                                val transcribed = result.alternatives()
                                logInfo("AWS Transcribe sentence recognised ({} alternatives): '{}'",
                                    transcribed.size, transcribed[0].transcript())
                                onCallbackResults(result)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    val errorMessage = when(ex) {
                        is TranscribeStreamingException -> "Error handling result stream from the AWS Transcribe service"
                        is ClassCastException -> "Error casting transcription result from the AWS Transcribe service"
                        else -> "Generic error handling result stream from the AWS Transcribe service"
                    }
                    logError(errorMessage, ex)
                }
            }
            .build()


    /**
     * Stops the AWS Transcribe listening process and cleans up associated resources. This method ensures that the
     * listening service is stopped only if it is currently running. It attempts to close the AWS Transcribe client, and
     * handles any exceptions that may occur during this process.
     *
     * @return `true` if the listening process was stopped successfully, `false` otherwise.
     */
    override fun stopListening(): Boolean {
        if (!super.stopListening())
            // Do not stop listening a service that did not start listening.
            return false

        try {
            // Stop the AWS Transcribe servie and close relative resources
            client?.close()  // It also calls SubscriptionImpl.cancel()
            logInfo("AWS transcription client closed successfully.")
            serverListening = false
            return true
        } catch (ex: Exception) {
            val errorMessage = when(ex) {
                is IOException -> "I/O error while stopping AWS Transcribe client"
                else -> "Generic error while stopping AWS Transcribe client"
            }
            logError(errorMessage, ex)
            return false
        }
    }
}


/**
 * A class that extends the capabilities of [AwsTranscribe] to support speech-to-text operations using audio captured
 * directly from the system's microphone.
 * 
 * This class handles capturing audio input from the microphone, converting the audio into the appropriate format, and
 * providing an input stream that can be used by the AWS Transcribe service for real-time transcription.
 * 
 * The audio is captured in a linear PCM format with specific configurations including a 16-bit sample size, mono
 * channel, and a sample rate compatible with the service's supported frequencies.
 *
 * See `AwsTranscribeRunner.kt` in the test src folder, for an example of how to use this class.
 *
 * @see Speech2TextInterface
 * @see AwsTranscribe
 *
 * @author Luca Buoncompagni © 2025
 */
class AwsTranscribeFromMicrophone : AwsTranscribe() {

    /**
     * Provides constants used for audio data processing during
     * speech-to-text operations based on microphone.
     */
    companion object {

        // The sample size for audio data. If higher the representation is more precise but bigger.
        private const val MIC_SAMPLE_SIZE_IN_BIT: Int = 16

        // The audio channel, i..e, 1: Mono, 2: Stereo.
        private const val MIC_AUDIO_CHANNEL: Int = 1

        // The sample rate based on AWS Transcribe configuration (it comes from the companion object of `AwsTranscribe`)
        private val MIC_SAMPLE_RATE: Float = SAMPLE_RATE.toFloat()
    }

    /**
     * Provides an `InputStream` to access audio data captured from the system's microphone.
     * 
     * This property establishes a connection to the microphone and configures it as a mono audio
     * input stream with a sample rate of 16kHz and 16-bit sample size, using the PCM format.
     * 
     * If the microphone is unavailable, it returns a `null` input stream.
     * 
     * The input stream is computed during constructor and used in the request computed by
     * `startListening()`.
     */
    override val inputStream: InputStream? = initStream()

    /**
     * Initializes and returns an input stream connected to the system's microphone for audio capture.
     * The audio is captured with a specific format: signed PCM, 16kHz sample rate, 16-bit sample size, and mono channel.
     * If the microphone is unavailable or another error occurs, this function logs an error and returns null.
     *
     * @return An InputStream for audio data, or null if the initialization fails.
     */
    private fun initStream(): InputStream? {
        try {
            // Signed PCM AudioFormat with 16kHz, 16 bit sample size, mono
            val format = AudioFormat(MIC_SAMPLE_RATE, MIC_SAMPLE_SIZE_IN_BIT, MIC_AUDIO_CHANNEL, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)

            // Check microphone availability.
            if (!AudioSystem.isLineSupported(info)) {
                throw LineUnavailableException("Line is not supported for Microphone: $info")
            }

            // Interface with the microphone and open an `InputStream`
            val microphoneLine = AudioSystem.getLine(info) as TargetDataLine
            microphoneLine.open(format)
            microphoneLine.start()
            logInfo("Microphone opened successfully for AWS Transcribe client.")
            return AudioInputStream(microphoneLine)
        } catch (ex: Exception) {
            val errorMessage = when(ex) {
                is LineUnavailableException ->
                    "Microphone is unavailable for the AWS Transcribe client"
                is SecurityException ->
                    "Access to microphone is denied to the AWS Transcribe client"
                else ->
                    "Generic error while accessing microphone for the AWS Transcribe client"
            }
            logError(errorMessage, ex)
        }
        return null
    }

}
