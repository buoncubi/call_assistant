package awsRunners

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import text2speech.AwsPolly

/**
 * A simple class to run and manually test the AWS Polly service for text-to-speech
 *
 * @see `AwsPolly.kt`, `Text2SpeechInterfaces.kt`
 *
 * @author Luca Buoncompagni © 2025
 */
suspend fun main() {
    // (TODO to generalize and better chose logging framework)
    val LOG_TAG = "ROOT"
    val logLevel = Level.toLevel(Level.INFO.levelStr)
    val logger = LoggerFactory.getLogger(LOG_TAG) as Logger
    logger.level = logLevel

    val tts = AwsPolly()

    // Call `speakAsync`, `fetchAudioAsync`, and `playAsync`
    val job1 = tts.speakAsync("Hello, I am your assistant!") { success ->
        println("Speak operation completed: $success")
    }

    lateinit var job3: Job
    val job2 = tts.fetchAudioAsync("How can I help you today?") { audio ->
        println("Fetched audio: $audio")
        if (audio != null) {
            job3 = tts.playAsync(audio) { success ->
                println("Play operation completed: $success")
            }
        }
    }

    println("Waiting for all operations to complete...")
    job1.join()
    job2.join()
    job3.join()

    // TODO test start() and stop() again

}
