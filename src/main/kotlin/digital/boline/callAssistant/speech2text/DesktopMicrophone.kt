package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.Loggable
import java.io.InputStream
import javax.sound.sampled.*


/**
 * The object that opens a new`InputStream` from a desktop microphone to be used with [Speech2Text] and derived classes.
 *
 * @see Speech2Text
 * @see AwsTranscribe
 * @see Speech2TextStreamBuilder
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object DesktopMicrophone: Speech2TextStreamBuilder, Loggable() {

    // TODO manage constants and make them configurable

    /** The sample size for audio data. If higher the representation is more precise but bigger. By default, is `16`. */
    private const val MIC_SAMPLE_SIZE_IN_BIT: Int = 16

    /** The audio channel, i..e, 1: Mono, 2: Stereo. By default, is `1`. */
    private const val MIC_AUDIO_CHANNEL: Int = 1

    /** The sample rate based on AWS Transcribe configuration. By default, is equal to [AwsTranscribe.SAMPLE_RATE]. */
    private const val MIC_SAMPLE_RATE: Float = AwsTranscribe.SAMPLE_RATE.toFloat()

    /**
     * Returns a new input stream from the microphone. Such a stream is configured to use PCM encoding,
     * [MIC_SAMPLE_RATE], [MIC_SAMPLE_SIZE_IN_BIT], and [MIC_AUDIO_CHANNEL]; see [AudioFormat] for more.
     * @return A new `InputStream` based on the microphone, or `null` if the microphone is not available.
     */
    override fun build(): InputStream? {
        try {
            // Signed PCM AudioFormat with 16kHz, 16 bit sample size, mono
            val format = AudioFormat(MIC_SAMPLE_RATE, MIC_SAMPLE_SIZE_IN_BIT, MIC_AUDIO_CHANNEL, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)

            // Check microphone availability.
            if (!AudioSystem.isLineSupported(info)) {
                logError("Line is not supported for Microphone: $info")
                return null
            }

            // Interface with the microphone and open an `InputStream`
            val microphoneLine = AudioSystem.getLine(info) as TargetDataLine
            microphoneLine.open(format)
            microphoneLine.start()

            logDebug("Microphone opened successfully for AWS Transcribe client.")
            return AudioInputStream(microphoneLine)

        } catch (ex: Exception) {
            logError("Error while accessing microphone for AWS Transcribe.", ex)
        }
        return null
    }
}