package cubibon.callAssistant.speech2text

import cubibon.callAssistant.*
import cubibon.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
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


// todo activate AWS transcribe only if there is some voice (reduce cost on silence calls)



/**
 * The class implementing [Speech2Text] based on AWS Transcribe Streaming service for real-time audio streaming
 * transcription.
 *
 * This class requires an [Speech2TextStreamBuilder], which instantiates an input stream that contains the audio to be
 * converted into text. It also requires these virtual environment variables:
 *  - `AWS_TRANSCRIBE_LANGUAGE`: see [LANGUAGE_CODE],
 *  - `AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE`: see [AUDIO_STREAM_CHUNK_SIZE_IN_BYTES],
 *  - `AWS_REGION`: see [AWS_VENV_REGION],
 *  - variables for AWS credential, i.e., `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`.
 *
 * This class performs computation asynchronously based on [ReusableService], which allow defining timeout and
 * callbacks. In particular, it provides three types of callback
 *  - [onErrorCallbacks], which are invoked in case of exception by providing the throwable object and an [ErrorSource].
 *  - [onStartTranscribingCallbacks], which are invoked when the user started speaking. This callback is invoked all the
 *   time a partial transcription exceed a limit of minim number of words set equal to
 *   [MINIMUM_WORDS_FOR_PARTIAL_RESULT].
 *  - [onResultCallbacks], which are invoked when a not partial transcription has been provided by AWS Transcribe.
 *   Before to invoke the callback, this class waits from [TRANSCRIPTION_BUFFERING_TIME] milliseconds and, if a new
 *   partial transcription is obtained in that time interval, it waits for the final transcription and merge them
 *   together. This is done to assure that the callback is invoked when the user is not speaking anymore. Note, that the
 *   transcription provided to the callback is the one with the best confidence.
 * See [handleTranscriptions] for more info on [onStartTranscribingCallbacks] and [onResultCallbacks].
 *
 * Here it follows a usage example:
 * ```
 *     // Instantiate the service with the ability to read `InputStream` from the microphone.
 *     val transcriber = AwsTranscribe (DesktopMicrophone)
 *
 *      // Set the callback invoked when a not partial transcription has been provided by AWS Transcribe
 *      transcriber.onResultCallbacks.add { result: Transcription ->
 *          println("Callback -> $result")
 *      }
 *
 *      // Set the callback invoked when the user started speaking.
 *      transcriber.onStartTranscribingCallbacks.add {
 *          println("The user started speaking!")
 *      }
 *
 *      // Set the callback invoked when an error occurred.
 *      transcriber.onErrorCallbacks.add { se: ServiceError ->
 *          println("Error during transcription: ('${se.source}', ${se.sourceTag}) ${se.throwable.message}")
 *      }
 *
 *      // Initialize the AWS Transcribe resources.
 *      transcriber.activate()
 *
 *      // Define the timeout with its callback
 *      val timeoutSpec = FrequentTimeout(timeout = 5000, checkPeriod = 50) { sourceTag ->
 *          println("Computation timeout reached! ($sourceTag)") // This is called when timeout occurs.
 *         // Note that this timeout is reset  everytime some audio is converted into text.
 *      }
 *
 *      // Start asynchronous listener for the microphone (the timeout and sourceTag are optional).
 *      transcriber.computeAsync(timeoutSpec, "MySourceTag")
 *
 *      // Eventually, wait for the computation to finish (the timeout is optional).
 *      transcriber.wait(Timeout(timeout = 20000) { sourceTag ->
 *          println("Waiting timeout reached! ($sourceTag)")
 *      })
 *
 *      // You might want to stop the transcription service.
 *      transcriber.stop()
 *
 *      // Here you can use `computeAsync` again (together with `wait` or `stop`)...
 *
 *      // Always remember to close the service resources.
 *      transcriber.deactivate()
 *
 *      // You can re-activate the service and make more computation...
 *
 *      // Cancel the scope and all related jobs. After this the service cannot be activated again.
 *      transcriber.cancelScope()
 * ```
 * See `AwsTranscribeRunner.kt` in the test src folder, for an example of how to use this class.
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
 * @property onStartTranscribingCallbacks The callback manager for the start of transcription. It is triggered once for
 * each partial transcription that has more than [MINIMUM_WORDS_FOR_PARTIAL_RESULT] words.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 * @property bufferingTranscription The transcribed data waiting to be sent to the [onResultCallbacks].
 * @property mergingJob The job that waits for merging transcribed result close in time into [bufferingTranscription].
 * This property is `null` if the service is not processing a result, and it is `private`.
 * @property audioStreamStartTime The time timestamp in milliseconds when the audio stream started. This property is
 * `null` if the service is not processing a result, and it is `private`.
 * @property userStartedSpeaking A flag used to trigger the [onStartTranscribingCallbacks] when it goes from `false` to
 * `true`. This property is `true` when a partial transcription with a number of words more
 * [MINIMUM_WORDS_FOR_PARTIAL_RESULT] is found, or `false`otherwise. Thi property is `private`.
 * @property userIsSpeaking A flag used to manage the [mergingJob]. It is `true` when a partial transcription is
 * obtained, and it becomes `false` when the final transcription is obtained. This property is  `private`.
 * @property sourceTag A private identifier given by the class calling [computeAsync] and propagated to the callback
 * related to on start transcription, on transcription result, on error and on timeout events.
 *
 * @see ReusableService
 * @see Speech2Text
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class AwsTranscribe(inputStreamBuilder: Speech2TextStreamBuilder) : Speech2Text(inputStreamBuilder) {

    // See documentation above.
    private var client: TranscribeStreamingAsyncClient? = null
    private var request: StartStreamTranscriptionRequest? = null
    private var transcribeJob: CompletableFuture<Void>? = null
    private val bufferingTranscription = Transcription()

    // Property used by `handleTranscriptions`.
    private var mergingJob: Job?  = null
    private var audioStreamStartTime: Long? = null
    // Flag used to trigger callbacks on `onStartTranscribingCallbacks`.
    private var userStartedSpeaking = false
    // Flag used to manage transcription buffering. It is as `startSpeaking` but without the `MINIMUM_WORDS_FOR_PARTIAL_RESULT` check.
    private var userIsSpeaking = AtomicBoolean(false)
    // Set at the beginning of `computeAsync` and reset and the end of it.
    var sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.


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
            .region(Region.of(AWS_VENV_REGION))
            .credentialsProvider(DefaultCredentialsProvider.create()) // todo manage credential on production and further configure client
            /*.httpClient(
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
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doActivate(sourceTag: String) {
        client = initClient()
        request = initRequest()
        // The `activate` method will return `false` in case of exceptions.
    }

    /**
     * The coroutine scope associated with the service.
     */
    private var computingScope: CoroutineScope? = null

    /**
     * Perform asynchronous computation that listen to the input stream given by [streamBuilder] and produce text that
     * can be obtained with [onResultCallbacks]. This function is invoked by [computeAsync].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.COMPUTING].
     *
     * @param input This method takes nothing as input parameter. Thus, this parameter is not used.
     * @param sourceTag An identifier propagated to the on start transcription and on result callbacks.
     * @param scope The coroutine scope associated with the service.
     */
    override suspend fun doComputeAsync(input: Unit, sourceTag: String, scope: CoroutineScope) {
        // It runs on a separate thread

        // Set the source tag associated with this computation.
        this.sourceTag = sourceTag
        this.computingScope = scope

        // Get input stream (e.g., from microphone).
        val inputStream: InputStream = streamBuilder.build()
            ?: throw IOException("Cannot build input stream") // This exception is cached by `doThrow`.
        // Set the timestamp for onResultCallback input parameter.
        audioStreamStartTime = System.currentTimeMillis()
        // Instantiate the publisher for AWS Transcribe.
        val audioPublisher = AudioStreamPublisher(inputStream, logger)
        // Instantiate the object that handle the AWS response.
        val handler = getResponseHandler()

        // Start the AWS Transcription stream
        transcribeJob = client?.startStreamTranscription(request, audioPublisher, handler)
        transcribeJob?.join()

        // Reset the source tag.
        this.sourceTag = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.
        this.computingScope = null
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
     *   AWS Transcribe over time. It reacts to new transcription incoming into the stream. Such transcriptions are
     *   processed by [transcribeJob], which is in charge to filter transcription and invoke the
     *   [onStartTranscribingCallbacks] and [onResultCallbacks].
     *
     * @return The object that handle the AWS response.
     */
    private fun getResponseHandler() = StartStreamTranscriptionResponseHandler.builder()
            .onResponse {
                // It is called as soon as the AWS Transcribe client starts.
                logDebug("Ready to use AWS Transcribe client.")
            }
            .onError { ex: Throwable ->
                stop(sourceTag)
                if (doThrow(ex, ErrorSource.COMPUTING, sourceTag, computingScope) == true) throw ex
            }
            .onComplete {
                // Called if the audio is finished or if the AWS Transcribe client is closed with `stopListening`.
                logDebug("AWS Transcribe client completed its stream.")
            }
            .subscriber { event: TranscriptResultStream ->
                // Get transcribed text over time, as they came into the stream.
                val results = (event as TranscriptEvent).transcript().results()
                if (results.isNotEmpty())
                    // Filter data, parse the result, and invoke callback
                    handleTranscriptions(results)
            }
            .build()


    /**
     * Transforms a list AWS Transcribe [Result] into a list of [Transcription], which are the object that the
     * [Speech2Text] interface requires to be given as input parameter to the [onResultCallbacks].
     *
     * It computes a `confidence` as the average confidence of the chunks transcribed by AWS and associate a start and
     * end time to the transcribed message as the absolute time stamp with respect to [audioStreamStartTime]. It also
     * set the [sourceTag] to the returned [Transcription] object.
     *
     * @param results The list of transcribed alternatives as provided by AWS Transcribe.
     * @return The list of transcribed alternatives as required by [Speech2Text].
     */
    private fun parseTranscriptions(results: List<Result>): List<Transcription> = results.map { result ->
        val alternative = result.alternatives()[0]
        val transcribed = alternative.transcript()

        // Calculate average confidence from transcribed chunks.
        val chunks = alternative.items()
        val averageConfidence = if (chunks.isNotEmpty()) {
            chunks.mapNotNull { it.confidence() }   // filters out null values
                .takeIf { it.isNotEmpty() }         // check if we have any non-null values
                ?.average()                         // calculate average if we have values
                ?: Transcription.UNKNOWN_CONFIDENCE // default to 0.0 if all values were null
        } else {
            Transcription.UNKNOWN_CONFIDENCE
        }

        /**
         * A helping function that converts Double timestamp in seconds into a Long timestamp in milliseconds. It also
         * checks that the timestamp is feasible.
         *
         * @param time The Double timestamp in seconds to be converted
         * @return The Long timestamp in milliseconds corresponding to the input timestamp, or
         * [Transcription.UNKNOWN_TIME] if the input timestamp is not feasible.
         */
        fun checkTime(time: Double): Long =
            if (time != Double.MAX_VALUE && time != Double.MIN_VALUE && time != 0.0)
                    (time * 1000).toLong()
            else
                Transcription.UNKNOWN_TIME

        val absoluteStartTime = if(audioStreamStartTime != null) {
            audioStreamStartTime!!
        }  else {
            logError("Absolute time for transcription result is not set!")
            0L
        }

        // AWS provides the transcription time relative to when the audio streaming started.
        val relativeStartTime = checkTime(result.startTime())
        val relativeEndTime = checkTime(result.endTime())

        // Build the object that is returned by this function.
        Transcription(
            message = transcribed,
            confidence = averageConfidence,
            startTime = absoluteStartTime + relativeStartTime,
            endTime = absoluteStartTime + relativeEndTime,
            sourceTag = sourceTag
        )
    }


    /**
     * Manage what AWS transcribe with respect to time, and invoke the [onStartTranscribingCallbacks] and
     * [onResultCallbacks].
     *
     * This class has three purposes:
     *  1. it updates the timeout associated with [computeAsync] by invoking [resetTimeout].
     *  2. it manages partial AWS transcriptions by invoking the [onStartTranscribingCallbacks] and setting the
     *  [userIsSpeaking] flag.
     *  3. it manages final AWS transcriptions by invoking the [onResultCallbacks] based on the [bufferingTranscription]
     *
     * When a transcription is partial, it sets the [userIsSpeaking] flag, which is reset when a final transcription
     * is obtained. Then, it checks if the transcription contains at least a number of words equal to
     * [MINIMUM_WORDS_FOR_PARTIAL_RESULT] and, if this is the case, it sets the [userStartedSpeaking] flag to `true`,
     * which is reset when a final result is obtained. When [userIsSpeaking] changes from `false` to `true`, it invokes
     * the [onStartTranscribingCallbacks].
     *
     * When a transcription is final, it exploits [parseTranscriptions] to transform the input [Result], and it chooses
     * the transcription with the highest confidence, which data is stored in the [bufferingTranscription]. Then it
     * asynchronously waits for [TRANSCRIPTION_BUFFERING_TIME] milliseconds and, if in the [userIsSpeaking] flags
     * becomes `true` again it waits for the incoming final transcription to arrive. Otherwise, it calls the
     * [onResultCallbacks] with the [bufferingTranscription] data, and resets such a data to empty.
     *
     * Note that [sourceTag] set during [computeAsync] will be propagated to the [onStartTranscribingCallbacks] and
     * [onResultCallbacks].
     *
     * @param results The AWS Transcribe partial or final results to manage for calling the callbacks.
     */
    private fun handleTranscriptions(results: List<Result>) {

        // Reset the timeout associated with `computeAsync`, since something is being transcribed from the audio.
        resetTimeout()

        // Check if there is at least one not partial result.
        val hasFinalResult = results.any { !it.isPartial }

        if (!hasFinalResult) {
            // Get all the transcription alternatives in results
            val transcripts: List<String> = results.flatMap { it.alternatives() }.map { it.transcript() }

            logDebug("AWS Transcribe realtime recognition with {} alternative(s). First transcription: '{}' ...",
                results.size, transcripts[0])

            // The user is speaking, and we should wait for a final transcription to arrive.
            userIsSpeaking.set(true)

            // Notify only once for each partial result through callback.
            if(!userStartedSpeaking) {
                // Get number of words of the string in `transcriptions` that has the maximum number of words.
                val maxWordCount = transcripts.maxOf { str ->
                    str.trim().split("\\s+".toRegex()).size
                }

                // Notify the `onStartTranscribingCallbacks` if the user said at least some words.
                if (maxWordCount >= MINIMUM_WORDS_FOR_PARTIAL_RESULT) {
                    onStartTranscribingCallbacks.invoke(SimpleCallbackInput(sourceTag), computingScope)
                    // Set flag to avoid calling the callback several time from the same transcription.
                    userStartedSpeaking = true
                }
            }
        } else {
            // Transform all transcriptions and get the best transcription.
            val transcriptions: List<Transcription> = parseTranscriptions(results)
            val bestTranscription: Transcription = transcriptions.maxBy { it.confidence }

            logInfo("AWS Transcribe sentence recognised: '{}'", bestTranscription)

            // Manage the transcription data across different speech-to-text result close on time.
            synchronized( bufferingTranscription) {
                // Update the transcription with the new data
                bufferingTranscription.merge(bestTranscription)

                // Stop waiting job since a new one is going to be launched.
                if (mergingJob?.isActive == true) {
                    mergingJob?.cancel()
                    logTrace("AWS Transcribe merged results as '{}'", bestTranscription)
                }

                // Reset the flag related to the user speaking (i.e., related to partial transcription).
                userStartedSpeaking = false // Managed only once for each transcription based on a minimum number of words.
                userIsSpeaking.set(false) // Managed for each partial transcription.
            }

            // Run a new asynchronous job for waiting and see if other final transcription are shortly obtained.
            mergingJob = scope.launch {
                // Wait same time to see if the user keep speaking
                delay(TRANSCRIPTION_BUFFERING_TIME)

                // If the user does not keep speaking, invoke the callback. Otherwise, a new `mergingJob` will run when
                // the user transcription will be final, and it will invoke the callback.
                if (!userIsSpeaking.get()) {
                    synchronized(bufferingTranscription) {
                        logDebug("AWS Transcribe generated final and merged transcription '{}'", bufferingTranscription)
                        // Actually invoke the callback.
                        onResultCallbacks.invoke(bufferingTranscription, computingScope)

                        // Reset the data and job for the next callback.
                        bufferingTranscription.reset()
                        mergingJob = null
                    }
                }
            }
        }
    }


    /**
     * Stop the service while it is computing and sets [transcribeJob] to `null`. It also stops the [mergingJob], and
     * sets [audioStreamStartTime] to `null`.
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.STOPPING].
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doStop(sourceTag: String) {
        AudioStreamPublisher.stop() // It makes transcribeJob finishing

        transcribeJob?.cancel(true)
        transcribeJob = null

        mergingJob?.cancel()
        audioStreamStartTime = null

        this.sourceTag = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.
        this.computingScope = null

        super.doStop(sourceTag)
    }


    /**
     * Deactivate the service by closing the AWS Transcribe client [client] and set it, as well as [request], to `null`.
     * This method is invoked by [deactivate].
     *
     * Note that this method runs in a try-catch block managed by [doThrow], which invokes callbacks set through
     * [onErrorCallbacks] with `errorSource` set to [ErrorSource.DEACTIVATING].
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doDeactivate(sourceTag: String) {
        // Stop the AWS Transcribe server and close relative resources.
        client?.close() // It takes 2 seconds with Natty HTTP-client (see other commented client, i.e., AwsCrt).
        client = null
        request = null
        // The `activate` method will return `false` in case of exceptions.
    }


    companion object {

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
         * `AWS_TRANSCRIBE_SAMPLE_RATE` as an integer. Note that [DesktopMicrophone.MIC_SAMPLE_SIZE_IN_BIT] is related
         * to this value.
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


        /**
         * Minimum number of words that has to be transcribed before to invoke the [onStartTranscribingCallbacks].
         * Such a count is reset after each finalized transcribed text that is sent to the [onResultCallbacks]. It is
         * set equal to 4.
         */
        private const val MINIMUM_WORDS_FOR_PARTIAL_RESULT = 4  // todo parametrize with environmental variables.


        /**
         * The delay in milliseconds used to wait for new transcriptions to be sent to the [onResultCallbacks] if the
         * user is still speaking even if a final transcription has been provided. By default, it is set equal to 1000.
         */
        private const val TRANSCRIPTION_BUFFERING_TIME = 1000L // todo parametrize with environmental variables.
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
private class AudioStreamPublisher(
    private val inputStream: InputStream,
    private val logger: CentralizedLogger
) : Publisher<AudioStream?>
{

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
    private val logger: CentralizedLogger
) : Subscription
{


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
