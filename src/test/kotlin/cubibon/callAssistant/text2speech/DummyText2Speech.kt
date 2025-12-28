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
object DummyText2Speech : Text2Speech(DummyPlayer) {

    private val latencyMillisecond: IntRange = 1000..2000

    init { logger.setLevel(Level.INFO) }

    override fun doActivate(sourceTag: String) {} // Do nothing.
    override fun doDeactivate(sourceTag: String) {} // Do nothing.

    override suspend fun fetchAudio(text: String): InputStream {
        delay(latencyMillisecond.random().toLong())
        return "Dummy stream".byteInputStream()
    }

    object DummyPlayer : Text2SpeechPlayer<SimpleCallbackInput>() {

        private val latencyMillisecond: IntRange = 1000..2000

        override suspend fun doComputeAsync(input: InputStream, sourceTag: String, scope: CoroutineScope) {
            onBeginPlayingCallbacks.invoke(SimpleCallbackInput(sourceTag), scope)
            delay(latencyMillisecond.random().toLong())
            onEndPlayingCallbacks.invoke(SimpleCallbackInput(sourceTag), scope)
        }

        override val onBeginPlayingCallbacks = CallbackManager<SimpleCallbackInput>(logger)
        override val onEndPlayingCallbacks = CallbackManager<SimpleCallbackInput>(logger)

        init {
            // Callbacks takes `Unit` as input parameter and returns nothing.
            onBeginPlayingCallbacks.add { logDebug("Dummy callback: audio playing started...") }
            onEndPlayingCallbacks.add { logInfo("Dummy callback: audio playing ended.") }
        }
    }

    fun runTest(): Unit = runBlocking {
        val timeout = FrequentTimeout(5000, 100) { logWarn("Timeout reached!") }

        val t2s = DummyText2Speech
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


fun main() = DummyText2Speech.runTest()