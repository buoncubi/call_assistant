package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.Loggable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import java.io.InputStream

object DummyInputStreamBuilder: Speech2TextStreamBuilder, Loggable() {
    override fun build(): InputStream? {
        logger.trace("Generating dummy input stream.")
            return null
    }
}

class DummySpeech2Text(private val latencyRandomMillisecond: IntRange = 1..1000)
    : Speech2Text<String> (DummyInputStreamBuilder)
    {

    init { logger.setLevel(Level.INFO) }

    private var counter = 1

    override suspend fun doComputeAsync(input: Unit) { // IT runs on a separate coroutine.
        delay(latencyRandomMillisecond.random().toLong())
        onResultCallbacks.invoke("dummy transcribed text $counter.")
        counter++
        logInfo("Processing: $input")
    }

    override fun doActivate() {} // Do nothing.
    override fun doDeactivate() {} // Do nothing.
}


fun main(): Unit = runBlocking {
    val stt = DummySpeech2Text(100..1000)

    stt.onResultCallbacks.add { result ->
        println("Transcribed: $result")
    }

    stt.activate()
    stt.computeAsync()
    stt.wait()
    stt.computeAsync()
    stt.stop()
    stt.deactivate()
}