package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.Loggable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import java.io.InputStream


/**
 * A dummy speech-to-text service used to test the [Speech2Text] and [Speech2TextStreamBuilder] interfaces.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class DummySpeech2Text(private val latencyRandomMillisecond: IntRange = 1..1000)
    : Speech2Text (DummyInputStreamBuilder)
{

    init { logger.setLevel(Level.INFO) }

    private var counter = 1

    override suspend fun doComputeAsync(input: Unit, sourceTag: String) { // IT runs on a separate coroutine.
        delay(latencyRandomMillisecond.random().toLong())
        onResultCallbacks.invoke(Transcription("Dummy transcribed text $counter ($sourceTag)."))
        counter++
    }

    override fun doActivate(sourceTag: String) {} // Do nothing.
    override fun doDeactivate(sourceTag: String) {} // Do nothing.

    object DummyInputStreamBuilder: Speech2TextStreamBuilder, Loggable() {
        override fun build(): InputStream? {
            // Here it should generate an audio input stream.
            return null
        }
    }

    companion object {
        fun runTest(): Unit = runBlocking {
            val s2t = DummySpeech2Text(100..1000)

            s2t.onResultCallbacks.add { result ->
                println("Transcribed: $result")
            }

            s2t.activate()
            s2t.computeAsync()
            s2t.wait()
            s2t.computeAsync()
            s2t.stop()
            s2t.deactivate()
        }
    }
}


fun main() = DummySpeech2Text.runTest()