package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.Loggable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import java.io.InputStream


/**
 * A dummy speech-to-text service used to test the [Speech2Text] and [Speech2TextStreamBuilder] interfaces.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object DummySpeech2Text : Speech2Text (DummyInputStreamBuilder) {

    private val latencyRandomMillisecond: IntRange = 100..1000

    init { logger.setLevel(Level.INFO) }

    private var counter = 1

    override suspend fun doComputeAsync(input: Unit, sourceTag: String, scope: CoroutineScope) { // IT runs on a separate coroutine.
        delay(latencyRandomMillisecond.random().toLong())
        onResultCallbacks.invoke(Transcription("Dummy transcription $counter.", confidence = 1.0), scope)
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


    fun runTest(): Unit = runBlocking {
        val s2t = DummySpeech2Text

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


fun main() = DummySpeech2Text.runTest()