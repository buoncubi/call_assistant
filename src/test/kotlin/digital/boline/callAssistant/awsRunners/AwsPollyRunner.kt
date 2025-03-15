package digital.boline.callAssistant.awsRunners

import kotlinx.coroutines.*
import digital.boline.callAssistant.text2speech.AwsPolly
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator

/**
 * A simple class to run and manually test the AWS Polly service for text-to-speech.
 *
 * @see AwsPolly
 *
 * @author Luca Buoncompagni © 2025
 */
suspend fun main() {

    Configurator.setRootLevel(Level.WARN)

    val tts = AwsPolly()

    // Call `speakAsync`, `fetchAudioAsync`, and `playAudioAsync`
    val job1 = tts.speakAsync("Ciao, sono la tua assistente!") { success ->
        println("Speak operation completed: $success")
    }

    var job3: Job? = null
    val job2 = tts.fetchAudioAsync("Come posso aiutarti?") { audio ->
        println("Fetched audio: $audio")
        if (audio != null) {
            job3 = tts.playAudioAsync(audio) { success ->
                println("Play operation completed: $success")
            }
        }
    }

    println("Waiting for all operations to complete...")
    job1.join()
    job2.join()
    job3?.join()

    // TODO test start() and stop() again

}
