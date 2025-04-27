package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.*
import kotlinx.coroutines.*
import org.slf4j.event.Level
import java.io.InputStream


/**
 * A dummy text-to-speech service used to test the [Text2Speech] and [Text2SpeechPlayer] interfaces.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class DummyText2Speech(private val latencyMillisecond: IntRange = 100..1000) : Text2Speech(DummyPlayer) {

    init { logger.setLevel(Level.INFO) }

    override fun doActivate(sourceTag: String) {} // Do nothing.
    override fun doDeactivate(sourceTag: String) {} // Do nothing.

    override fun fetchAudio(text: String): InputStream {
        Thread.sleep(latencyMillisecond.random().toLong())
        return "Dummy stream".byteInputStream()
    }

    object DummyPlayer : Text2SpeechPlayer<SimpleCallbackInput>() {

        private val latencyMillisecond: IntRange = 100..1000

        override suspend fun doComputeAsync(input: InputStream, sourceTag: String) {
            onBeginPlayingCallbacks.invoke(SimpleCallbackInput(sourceTag))
            delay(latencyMillisecond.random().toLong())
            onEndPlayingCallbacks.invoke(SimpleCallbackInput(sourceTag))
        }

        override val onBeginPlayingCallbacks = CallbackManager<SimpleCallbackInput, Unit>(logger)
        override val onEndPlayingCallbacks = CallbackManager<SimpleCallbackInput, Unit>(logger)

        init {
            // Callbacks takes `Unit` as input parameter and returns nothing.
            onBeginPlayingCallbacks.add { logInfo("Dummy callback: audio playing started") }
            onEndPlayingCallbacks.add { logInfo("Dummy callback: audio playing ended") }
        }
    }

    companion object : Loggable(DummyText2Speech::class.java) {
        fun runTest(): Unit = runBlocking {
            val timeout = FrequentTimeout(5000, 100) { logWarn("Timeout reached!") }

            val t2s = DummyText2Speech(100..1000)
            t2s.onErrorCallbacks.add { se: ServiceError -> logError("Error occurred! ${se.source}, ${se.throwable}") }

            // Add another `player.onEndPlayingCallbacks` callback
            t2s.onResultCallbacks.add { logInfo("Another dummy audio playing ended callback") }


            t2s.activate()
            t2s.computeAsync("Hello", timeout)
            t2s.wait()
            t2s.computeAsync("World")
            t2s.stop()
            t2s.deactivate()
        }
    }
}


fun main() = DummyText2Speech.runTest()