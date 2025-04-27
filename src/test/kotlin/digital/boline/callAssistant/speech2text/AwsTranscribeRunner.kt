package digital.boline.callAssistant.speech2text

import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.ServiceError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import kotlin.test.assertTrue

/**
 * A simple class to run and manually test the AWS Transcribe service for speech-to-text.
 *
 * @see AwsTranscribe
 * @see DesktopMicrophone
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object AwsTranscribeRunner: Loggable() {

    init {
        setLoggingLevel(Level.INFO)
    }


    fun runTest() = runBlocking{

        val transcriber = AwsTranscribe (DesktopMicrophone)

        transcriber.setLoggingLevel(Level.DEBUG)

        var shouldReceiveData = false
        transcriber.onResultCallbacks.add { result ->
            logInfo("Callback -> ${result.alternatives()[0].transcript()}")
            assertTrue(shouldReceiveData)
        }

        transcriber.onErrorCallbacks.add { se: ServiceError ->
            logError("Error during transcription threw to callback: ('${se.source}') ${se.throwable.message}")
            // Manage your error recovery logic here!!!
        }

        val delayMs = 5000L

        transcriber.activate()
        println("1 ------------  LISTEN FOR ${delayMs/1000} SECONDS --------------------")
        shouldReceiveData = true
        transcriber.computeAsync()

        delay(delayMs)
        logInfo("Intentionally stop the transcribe service.")
        transcriber.stop()
        shouldReceiveData = false
        println("2 ------------  DO NOT LISTEN FOR ${delayMs/1000} SECONDS --------------------")
        delay(delayMs)

        println("3 ----------- LISTEN UNTIL YOU DO NOT SPEAK FOR ${delayMs/1000} SECONDS---------------------")
        val timeoutSpec = FrequentTimeout(delayMs, 50) {
            // This is called when timeout occurs.
            logInfo("Computation timeout reached")
            shouldReceiveData = false
        }
        shouldReceiveData = true
        transcriber.computeAsync(timeoutSpec)
        transcriber.wait()

        println("3 ----------- LISTEN FOR ${delayMs/1000} SECONDS---------------------")
        transcriber.deactivate()
        transcriber.activate()
        transcriber.computeAsync(FrequentTimeout(delayMs, 50))
        shouldReceiveData = true

        delay(delayMs)
        logInfo("Intentionally stop the transcribe service.")
        println("5 ----------- STOP LISTENING ---------------------")
        transcriber.stop()
        shouldReceiveData = false
        transcriber.deactivate()

        logInfo("Test run finished!")
    }
}

fun main() {
    AwsTranscribeRunner.runTest()
}