package cubibon.callAssistant.speech2text

import cubibon.callAssistant.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min


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
 * A data class that is given as input to [Speech2Text] callbacks added to the [Speech2Text.onResultCallbacks] manager.
 *
 * @constructor Construct this class by specifying all its properties. If properties are not specified, they will be
 * set to default values, which encompass [UNKNOWN_MESSAGE], [UNKNOWN_CONFIDENCE], [UNKNOWN_TIME], and
 * [ServiceInterface.UNKNOWN_SOURCE_TAG].
 *
 * @param message The message transcript from an audio signal.
 * @param confidence The confidence of the transcription as a number in `[0,1]`.
 * @param startTime The absolute time in milliseconds when the transcription starts.
 * @param endTime The absolute time in milliseconds when the transcription starts.
 * @param sourceTag An identifier set from the class that required th [Speech2Text] functionalities, that will be
 * propagated to the callback.
 *
 * @see Speech2Text
 * @see CallbackManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class Transcription(message: String = UNKNOWN_MESSAGE,
                    confidence: Double = UNKNOWN_CONFIDENCE,
                    startTime: Long = UNKNOWN_TIME,
                    endTime: Long = UNKNOWN_TIME,
                    sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG
) : CallbackInput
{

    override fun copy() = Transcription(
        this.message,
        this.confidence,
        this.startTime,
        this.endTime,
        this.sourceTag
    )

    /**
     * A private [StringBuilder] used to build the [message] string.
     */
    private val messageBuilder = StringBuilder(message)

    // See documentation above.
    val message: String
        get() = messageBuilder.toString()

    // See documentation above.
    var confidence: Double = confidence
        private set

    // See documentation above.
    var startTime: Long = startTime
        private set

    // See documentation above.
    var endTime: Long = endTime
        private set

    // See documentation above.
    override var sourceTag: String = sourceTag
        private set


    /**
     * Merges the data af another transcription to this one. It appends the message, updates the average confidence, and
     * set the start and end time with the new minimum and maximum values respectively. It does change `this.sourceTag`
     * only if it is empty, and sets it to `other.sourceTag`.
     *
     * @param other The transcription to be merged with this one.
     */
    fun merge(other: Transcription) {
        // Appending the message.
        messageBuilder.append(" ${other.messageBuilder}")

        // Update average confidence.
        confidence = when {
            confidence == UNKNOWN_CONFIDENCE -> other.confidence
            other.confidence == UNKNOWN_CONFIDENCE -> confidence
            else -> (confidence + other.confidence) / 2
        }

        // Takes the new start time as the minim value while disregarding the default unknown value.
        startTime = when {
            startTime == UNKNOWN_TIME -> other.startTime
            other.startTime == UNKNOWN_TIME -> startTime
            else -> min(startTime, other.startTime)
        }

        // Takes the new end time as the maximum value while disregarding the default unknown value.
        endTime = when {
            startTime == UNKNOWN_TIME -> other.startTime
            other.startTime == UNKNOWN_TIME -> startTime
            else -> max(endTime, other.endTime)
        }

        if(sourceTag == ServiceInterface.UNKNOWN_SOURCE_TAG)
            sourceTag = other.sourceTag
    }



    /**  Clears the message and resets the average confidence, start and end time to their default unknown values. */
    fun reset() {
        messageBuilder.clear()
        confidence = UNKNOWN_CONFIDENCE
        startTime = UNKNOWN_TIME
        endTime = UNKNOWN_TIME
        sourceTag = ServiceInterface.UNKNOWN_SOURCE_TAG
    }



    /**
     * Check if an object is equal to this one. This occurs if [message], [confidence], [startTime], and
     * [endTime] are equal.
     * @param other Another object to be compared with this one.
     * @return `ture` if `other` is an instance of `Transcription` and has all properties, equal to this one.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transcription) return false

        if (confidence != other.confidence) return false
        if (startTime != other.startTime) return false
        if (endTime != other.endTime) return false
        if (messageBuilder != other.messageBuilder) return false
        if (sourceTag != other.sourceTag) return false

        return true
    }



    /**
     * Returns the hash code value for this object. The hash code is computed based on the values of [confidence],
     * [startTime], [endTime], and [messageBuilder]. SUch a computation is based on [Any.hashCode]
     * @return The hash code value for this object.
     */
    override fun hashCode(): Int {
        var result = confidence.hashCode()
        result = 31 * result + startTime.hashCode()
        result = 31 * result + endTime.hashCode()
        result = 31 * result + messageBuilder.hashCode()
        result = 31 * result + sourceTag.hashCode()
        return result
    }



    /**
     * Returns a string representation of this object. The string includes the [messageBuilder], [confidence],
     * [startTime], and [endTime] properties.
     * @return A string representation of this object.
     */
    override fun toString(): String {
        return "{'$messageBuilder', avgConfidence=$confidence, startTime=$startTime, endTime=$endTime}"
    }



    companion object {

        /** A constant representing an unknown time value. By default, it is set to `-1`. */
        const val UNKNOWN_TIME = -1L

        /** A constant representing an unknown confidence value. By default, it is set to `-1`. */
        const val UNKNOWN_CONFIDENCE = -1.0

        /** A constant representing an unknown message. By default, it is set to an empty string.. */
        const val UNKNOWN_MESSAGE = ""
    }
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
 * text transcription of it through the [onResultCallbacks], which takes instances of [Transcription] as input. Also,
 * [computeAsync] might call [onStartTranscribingCallbacks] when the transcription starts (it can be used to stop the
 * LLM simulating a user's interruption), which takes [SimpleCallbackInput] as input. The latter encodes the
 * `serviceTag`, which is an identifier provided to the [computeAsync], it is not processed by this class, but it can
 * be used to identify the source of the callback.
 *
 * @property streamBuilder The object providing the input audio stream to process.
 * @property onResultCallbacks The callback manager for the text transcription results.
 * @property onStartTranscribingCallbacks The callback manager for the start of the transcription.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 * @see AwsTranscribe
 * @see ReusableService
 * @see Transcription
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class Speech2Text(protected val streamBuilder:Speech2TextStreamBuilder) : ReusableService<Unit>(speech2TextScope) {
    val onResultCallbacks = CallbackManager<Transcription>(logger)
    val onStartTranscribingCallbacks = CallbackManager<SimpleCallbackInput>(logger)


    /**
     * A shorthand for invoking [ReusableService.computeAsync] with no input parameter for computing the server, and
     * with optional timeout.
     *
     * @param timeoutSpec The timeout specification, which also encompass a lambda function.
     * @param sourceTag The identifier that will be propagated to the callback, which encompass:
     * [onStartTranscribingCallbacks], [onResultCallbacks], [onErrorCallbacks], and the callback that can be encoded in
     * [timeoutSpec].
     * @return `true` if the text-to-speech computation has been started, `false` otherwise.
     */
    fun computeAsync(timeoutSpec: FrequentTimeout? = null, sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) =
        super.computeAsync(Unit, timeoutSpec, sourceTag)
}