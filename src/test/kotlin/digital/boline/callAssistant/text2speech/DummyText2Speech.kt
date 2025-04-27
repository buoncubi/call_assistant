package digital.boline.callAssistant.text2speech

import digital.boline.callAssistant.CallbackManager
import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.ServiceError
import kotlinx.coroutines.*
import org.slf4j.event.Level
import java.io.InputStream

// TODO briefly document (also on other tests)

private val dummyScope = CoroutineScope(
    SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("DummyScope")
)

object DummyPlayer : Text2SpeechPlayer<Unit>() {

    private val latencyMillisecond: IntRange = 100..1000

    override suspend fun doComputeAsync(input: InputStream) {
        delay(latencyMillisecond.random().toLong())
    }

    override val onBeginPlayingCallbacks = CallbackManager<Unit, Unit>(logger)
    override val onEndPlayingCallbacks = CallbackManager<Unit, Unit>(logger)

    init {
        // Callbacks takes `Unit` as input parameter and returns nothing.
        // onBeginPlayingCallbacks.add { logInfo("Dummy callback started") }
        onEndPlayingCallbacks.add { logInfo("Dummy audio playing ended callback") }
    }
}

class DummyText2Speech(private val latencyMillisecond: IntRange = 100..1000) : Text2Speech(DummyPlayer) {

    init { logger.setLevel(Level.INFO) }

    override suspend fun doComputeAsync(input: String) { // IT runs on a separate coroutine.
        delay(latencyMillisecond.random().toLong())
        logInfo("Processing: $input")
    }

    override fun doActivate() {} // Do nothing.
    override fun doDeactivate() {} // Do nothing.
    override fun fetchAudio(text: String): InputStream? = null // Do nothing.
}


fun main(): Unit = runBlocking {
    val timeout = FrequentTimeout(5000, 100) { println("Timeout reached!") }

    val tts = DummyText2Speech(100..1000)
    tts.onErrorCallbacks.add { se: ServiceError -> println("Error occurred! ${se.source}, ${se.throwable}") }

    tts.activate()
    tts.computeAsync("Hello", timeout)
    tts.wait()
    tts.computeAsync("World")
    tts.stop()
    tts.deactivate()
}