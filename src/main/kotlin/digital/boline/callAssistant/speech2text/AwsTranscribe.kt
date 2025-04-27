package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.*
import digital.boline.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
import kotlinx.coroutines.*
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient
import software.amazon.awssdk.services.transcribestreaming.model.*
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*


// TODO activate AWS transcribe only if there is some voice (reduce cost on silence calls)


/**
 * The class implementing [Speech2Text] based on AWS Transcribe Streaming service for real-time audio streaming
 * transcription.
 *
 * This class requires an [Speech2TextStreamBuilder], which instantiate an input stream that contains the audio to be
 * converted into text. It also requires these virtual environment variables:
 *  - `AWS_TRANSCRIBE_LANGUAGE`: see [LANGUAGE_CODE],
 *  - `AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE`: see [AUDIO_STREAM_CHUNK_SIZE_IN_BYTES],
 *  - `AWS_REGION`: see [AWS_VENV_REGION],
 *  - variables for AWS credential, i.e., `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`.
 *
 * This class performs computation asynchronously based on [ReusableService], which allow defining timeout and
 * callbacks. Here it follows a usage example:
 * ```
 *    // Instantiate the service with the ability to read `InputStream` from the microphone.
 *    val transcriber = AwsTranscribe (DesktopMicrophone)
 *
 *     // Set the callback invoked when a not final transcription has been provided by AWS Transcribe
 *     transcriber.onResultCallbacks.add { result: Result ->
 *         println("Callback -> ${result.alternatives()[0].transcript()}")
 *     }
 *
 *     // Set the callback invoked when an error occurred.
 *     transcriber.onErrorCallbacks.add { se: ServiceError ->
 *         println("Error during transcription: ('${se.errorSource}') ${se.throwable.message}")
 *     }
 *
 *     // Initialize the AWS Transcribe resources.
 *     transcriber.activate()
 *
 *     // Define the timeout with its callback
 *     val timeoutSpec = FrequentTimeout(timeout = 5000, checkPeriod = 50) {
 *         println("Computation timeout reached!") // This is called when timeout occurs.
 *         // Note that this timeout is reset  everytime some audio is converted into text.
 *     }
 *
 *     // Start asynchronous listener for the microphone (the timeout is optional).
 *     transcriber.computeAsync(timeoutSpec)
 *
 *     // Eventually, wait for the computation to finish (the timeout is optional).
 *     transcriber.wait(Timeout(timeout = 20000) {
 *         println("Waiting timeout reached!")
 *     })
 *
 *     // You might want to stop the transcription service.
 *     transcriber.stop()
 *
 *     // Here you can use `computeAsync` again (together with `wait` or `stop`)...
 *
 *     // Always remember to close the service resources.
 *     transcriber.deactivate()
 *
 *     // You can re-activate the service and make more computation...
 * ```
 *
 * @property client The AWS Transcribe client initialized with [activate], and closed with [deactivate]. This property
 * is `null` if the service is not active, and it is `private`.
 * @property request The configuration of the AWS Transcribe client it is instantiated with [activate]. This property is
 * `null` if the service is not active, and it is `private`.
 * @property transcribeJob The asynchronous job that is launched by [computingJob], it is used to implement the [stop]
 * and [wait] methods. This property is `null` if the service is not computing, and it is `private`.
 * @property streamBuilder The object providing the input audio stream to process. However, this is a `private`
 * property.
 * @property onResultCallbacks The callback manager for the text transcription results.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see ReusableService
 * @see Speech2Text
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class AwsTranscribe(inputStreamBuilder: Speech2TextStreamBuilder) : Speech2Text<Result>(inputStreamBuilder) {

    // See documentation above.
    private var client: TranscribeStreamingAsyncClient? = null
    private var request: StartStreamTranscriptionRequest? = null
    private var transcribeJob: CompletableFuture<Void>? = null


    /**
     * Initialize the AWS Transcribe client. It is invoked by [activate] to store the returned value in the [client]
     * property.
     *
     * This method requires these environmental variables: `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
     * and `AWS_SESSION_TOKEN`.
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     *
     * @return The AWS transcribe client.
     */
    private fun initClient(): TranscribeStreamingAsyncClient =
        TranscribeStreamingAsyncClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.create()) // TODO adjust credential provider
            .region(Region.of(AWS_VENV_REGION))
            /*.httpClient( // TODO to use?
                // It is faster with respect to Netty (default) httpClient but less stable
                // It requires `implementation("software.amazon.awssdk:aws-crt-client")` as gradle dependence
                AwsCrtAsyncHttpClient.builder()
                //.connectionTimeout(Duration.ofSeconds(1))
                .build()
            )*/
            .build()


    /**
     * Configure the AWS Transcribe. It is invoked by [activate] to store the returned value in the [request] property.
     *
     * This method requires these environmental variables: [LANGUAGE_CODE], [MediaEncoding], and [SAMPLE_RATE].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     *
     * @return The request configuration for the AWS transcribe client.
     */
    private fun initRequest(): StartStreamTranscriptionRequest =
        StartStreamTranscriptionRequest.builder()
                .languageCode(LANGUAGE_CODE)
                .mediaEncoding(MEDIA_ENCODING)
                .mediaSampleRateHertz(SAMPLE_RATE)
                .build()


    /**
     * Activate the service by initializing the AWS Transcribe [client] with its configuration [request] based on
     * [initClient] and [initRequest]. This method is invoked by [activate].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.ACTIVATING].
     */
    override fun doActivate() {
        client = initClient()
        request = initRequest()
        // The `activate` method will return `false` in case of exceptions.
    }


    /**
     * Perform asynchronous computation that listen to the input stream given by [streamBuilder] and produce text that
     * can be obtained with [onResultCallbacks]. This function is invoked by [computeAsync].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.COMPUTING].
     *
     * @param input This method takes nothing as input parameter. Thus, this parameter is not used.
     */
    override suspend fun doComputeAsync(input: Unit) { // Runs on a separate thread
        // Start AWS transcribe service
        // Get input stream (e.g., from microphone).
        val inputStream: InputStream = streamBuilder.build()
            ?: throw IOException("Cannot build input stream") // This exception is cached by `doThrow`.
        // Instantiate the publisher for AWS Transcribe.
        val audioPublisher = AudioStreamPublisher(inputStream, logger)
        // Instantiate the object that handle the AWS response.
        val handler = getResponseHandler()
        // Start the AWS Transcription stream
        transcribeJob = client?.startStreamTranscription(request, audioPublisher, handler)
        transcribeJob?.join()
    }


    /**
     * Returns the object that handle the AWS response. This method is invoked by [doComputeAsync].
     *
     * The returned object implement several functionalities of [StartStreamTranscriptionResponseHandler] which are
     * called by AWS Transcribe during the speech-to-text transcription process:
     * - [StartStreamTranscriptionResponseHandler.Builder.onResponse]: Triggered when the AWS Transcribe service sends
     *   an initial response, indicating successful initialization of the session. Currently, this method only produces
     *   log.
     * - [StartStreamTranscriptionResponseHandler.Builder.onError]: Invoked when any exceptions are encountered during
     *   the transcription process. Error handling is made by the mean of [doThrow], which invokes the
     *   [onErrorCallbacks] with `errorSource` set to [ErrorSource.COMPUTING]. When an error occurs, [stop] is invoked.
     * - [StartStreamTranscriptionResponseHandler.Builder.onComplete]: Executed when the transcription stream is
     *   successfully completed, either by finishing audio input or stopping the service explicitly. Currently, this
     *   method only produces log.
     * - [StartStreamTranscriptionResponseHandler.Builder.subscriber]: Processes [TranscriptResultStream] events from
     *   AWS Transcribe, extracting and formatting transcribed text data, and invoking [onResultCallbacks] with
     *   finalized results when available. This method is also used to invoke [resetTimeout] when some text has been
     *   transcribed.
     *
     * @return The object that handle the AWS response.
     */
    private fun getResponseHandler() =
        StartStreamTranscriptionResponseHandler.builder()
            .onResponse {
                // It is called as soon as the AWS Transcribe client starts.
                logDebug("Ready to use AWS Transcribe client.")
            }
            .onError { ex: Throwable ->
                stop()
                if (doThrow(ex, ErrorSource.COMPUTING) == true) throw ex
            }
            .onComplete {
                // Called if the audio is finished or if the AWS Transcribe client is closed with `stopListening`.
                logDebug("AWS Transcribe client completed its stream.")
            }
            .subscriber { event: TranscriptResultStream ->
                // Get transcribed text for AWS.
                val results = (event as TranscriptEvent).transcript().results()
                if (results.isNotEmpty()) {
                    val result: Result = results[0]
                    if (result.alternatives()[0].transcript().isNotEmpty()) {
                        logTrace(
                            "AWS Transcribe realtime recognition: '{}' ...",
                            results[0].alternatives()[0].transcript()
                        )
                        resetTimeout()

                        if (!result.isPartial) {
                            val transcribed = result.alternatives()
                            logDebug(
                                "AWS Transcribe sentence recognised {} alternative(s): '{}'",
                                transcribed.size, transcribed[0].transcript()
                            )
                            onResultCallbacks.invoke(result)
                        }
                    }
                }
            }
            .build()


    /**
     * Stop the service while it is computing and sets [transcribeJob] to `null`.
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.STOPPING].
     */
    override fun doStop() {
        AudioStreamPublisher.stop() // It makes transcribeJob finishing
        transcribeJob?.cancel(true)
        transcribeJob = null
        super.doStop()
    }


    /**
     * Deactivate the service by closing the AWS Transcribe client [client] and set it, as well as [request], to `null`.
     * This method is invoked by [deactivate].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.DEACTIVATING].
     */
    override fun doDeactivate() {
        // Stop the AWS Transcribe server and close relative resources.
        client?.close() // It takes 2 seconds with Natty HTTP-client (see other commented client, i.e., AwsCrt).
        client = null
        request = null
        // The `activate` method will return `false` in case of exceptions.
    }


    companion object {
        // TODO make var configurable from API

        /**
         * The language code for the transcription, e.g., `it-IT`. This value is required from the
         * environmental variable `AWS_TRANSCRIBE_LANGUAGE`.
         */
        private val LANGUAGE_CODE = System.getenv("AWS_TRANSCRIBE_LANGUAGE") // ?: "it-IT"


        /**
         * The size of the audio buffer in bytes, e.g., 1024. This value is required from the environmental variable
         * `AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE` as an integer.
         *
         * It is suggested to set it as `chunk_size_in_bytes = chunk_duration_in_millisecond / 1000 * audio_sample_rate * 2`
         */
        internal val AUDIO_STREAM_CHUNK_SIZE_IN_BYTES =
            System.getenv("AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE").toInt() //orNull() ?:1024


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

}



/**
 * A [Publisher] implementation responsible for providing audio data from an [InputStream] to the AWS stream Transcribe
 * client. This handles audio events, implementing backpressure management and subscription lifecycle.
 *
 * This class supports only a single active subscriber at a time and facilitates audio event-driven communication by
 * reading data from the provided audio stream.
 *
 * @constructor Takes an [InputStream] as its source for audio data and make it available to AWS Transcribe by the mean
 * of [SubscriptionImpl].
 *
 * @param inputStream The input stream source for audio data. It must remain open and available during the
 * subscription lifecycle. Such an input stream is given by [AwsTranscribe.streamBuilder]. This property is `private`.
 * @param logger A logger instance associated within the [AwsTranscribe] class. This property is `private`.
 *
 * @see AwsTranscribe
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 * @see SubscriptionImpl
 * @see Speech2Text
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
private class AudioStreamPublisher(private val inputStream: InputStream,
                                   private val logger: CentralizedLogger)
        : Publisher<AudioStream?> {

    /**
     * Subscribes a [Subscriber] to the audio stream. This method establishes a subscription based on [SubscriptionImpl]
     * and, if it already exists, it closes the previous one before to open a new subscription.
     *
     * Note that in case of exceptions, they are propagated to [AwsTranscribe.doThrow].
     *
     * @param subscriber The object to be subscribed to the audio stream.
     */
    override fun subscribe(subscriber: Subscriber<in AudioStream?>) {
        synchronized(mutex) {
            try {
                logger.debug("AWS Transcribe subscribing to audio stream.")
                if (currentSubscription == null) {
                    currentSubscription = SubscriptionImpl(subscriber, inputStream, logger)
                } else {
                    currentSubscription!!.cancel()
                    currentSubscription = SubscriptionImpl(subscriber, inputStream, logger)
                }
                subscriber.onSubscribe(currentSubscription)
            } catch (ex: Exception) {
                logger.error("Error during subscription to audio stream for AWS Transcribe", ex.message)
                subscriber.onError(ex) // It propagates exceptions to `doThrow`.
            }
        }
    }

    companion object {

        /** The audio stream subscription used to provide audio data to AWS Transcribe. */
        private var currentSubscription: SubscriptionImpl? = null

        /** A mutex to synchronize [subscribe] and [stop] methods */
        private val mutex = Any()

        /**  Stops the audio stream subscription, if it exists. This method is invoked by [AwsTranscribe.doStop]. */
        fun stop() {
            synchronized(mutex) {
                currentSubscription?.stop()
                currentSubscription = null
            }
        }
    }
}



/**
 * Implementation of the Subscription interface to stream audio data to AWS Transcribe.
 *
 * Handles fetching audio data from a provided input stream, delivering it in manageable chunks to an associated
 * subscriber, and managing the backpressure based on demands from the subscriber.
 *
 * This class run a separate thread, which is not managed through Kotlin coroutine due to issues with the AWS API.
 *
 * @param subscriber The subscriber that will receive events representing audio data. It is given by
 * [AudioStreamPublisher.subscribe]. This property is `private`.
 * @param inputStream The input stream from which audio data is read. It is given by [AwsTranscribe.streamBuilder].
 * This property is `private`.
 * @param logger A logger instance associated within the [AwsTranscribe] class. This property is `private`.
 *
 * @see AudioStreamPublisher
 * @see AwsTranscribe
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 * @see Speech2Text
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class SubscriptionImpl internal constructor(
                                            private val subscriber: Subscriber<in AudioStream?>,
                                            private val inputStream: InputStream,
                                            private val logger: CentralizedLogger)
                                            : Subscription {


    /**
     * The object that manages the execution of a single asynchronous task, where the audio is buffered and provided.
     */
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
     * Indicates whether the subscription is open or closed. This flag is used to control the execution of the
     * subscription's task and to manage the lifecycle of the subscription when it is stopped.
     */
    private val isOpen = AtomicBoolean(true)


    /**
     * Returns a buffer that holds the next chunk of audio data to be consumed.  Data is wrapped from an `InputStream`
     * to a `ByteBuffer`. This method relies on `AwsTranscribe.AUDIO_STREAM_CHUNK_SIZE_IN_BYTES` to process the audio
     * stream.
     *
     * If the  read operation fails, `null` is returned. In this case, [AwsTranscribe.stop] in invoked by the mean of
     * `onError` method of the AWS Transcribe `ResponseHandler`.
     */
    private fun getNextEvent(): ByteBuffer {
        val audioBytes = ByteArray(AwsTranscribe.AUDIO_STREAM_CHUNK_SIZE_IN_BYTES)

        return try {
            val len = inputStream.read(audioBytes)

            if (len <= 0) {
                ByteBuffer.allocate(0)
            } else {
                ByteBuffer.wrap(audioBytes, 0, len)
            }
        } catch (e: IOException) {
            logger.error("Error while reading audio stream for AWS Transcribe.", e)
            throw UncheckedIOException(e)
        }
    }


    /**
     * Requests a specific number of items to be delivered to the subscriber related with AWS Transcribe.
     * If an error occurs, then [StartStreamTranscriptionResponseHandler.Builder.onError] is invoked.
     *
     * @param n The number of items to request.
     */
    override fun request(n: Long) {
        if (n <= 0) {
            subscriber.onError(IllegalArgumentException("Demand must be positive"))
            return
        }

        demand.getAndAdd(n)
        executor.submit {
            try {
                while (isOpen.get() && demand.get() > 0) {
                    val audioBuffer = getNextEvent()
                    if (audioBuffer.remaining() > 0) {
                        val audioEvent = AudioEvent.builder()
                            .audioChunk(SdkBytes.fromByteBuffer(audioBuffer))
                            .build()
                        subscriber.onNext(audioEvent)
                        logger.trace("AWS Transcribe processed next event.")
                    } else {
                        stop()
                        break
                    }
                    demand.decrementAndGet()
                }
            } catch (e: Exception) {
                // It also propagates error in `getNextEvent`.
                subscriber.onError(RuntimeException("Task execution failed for next input audio event: ${e.cause}", e))
            }
        }
    }


    /**
     * Cancels the subscription and closes the input stream. This method is invoked by the AWS Transcribe API, and it
     * should not be used directly; ise [stop] instead.
     */
    override fun cancel() {
        executor.shutdown()
        inputStream.close()
        logger.debug("Subscription to input audio stream cancelled.")
    }


    /**
     * Stops the subscription and closes the input stream. This method is invoked by [AudioStreamPublisher.stop], and
     * it implicitly calls [cancel].
     */
    fun stop() {
        isOpen.set(false)
        subscriber.onComplete()  // It also calls this.cancel()
    }
}
