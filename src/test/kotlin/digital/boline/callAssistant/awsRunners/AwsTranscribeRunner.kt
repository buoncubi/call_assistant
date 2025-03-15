package digital.boline.callAssistant.awsRunners

import kotlinx.coroutines.delay
import software.amazon.awssdk.services.transcribestreaming.model.Result
import digital.boline.callAssistant.speech2text.AwsTranscribeFromMicrophone

/**
 * A simple class to run and manually test the AWS Transcribe service for speech-to-text.
 *
 * @see AwsTranscribeFromMicrophone
 *
 * @author Luca Buoncompagni © 2025
 */
suspend fun main() {

    val callback: ((Result) -> Unit) = {
        println("Callback -> ${it.alternatives()[0].transcript()}")
    }
    
    val text2speech = AwsTranscribeFromMicrophone()
    text2speech.addCallback(callback) 

    try {
        text2speech.startListening()
        delay(14_000)
        text2speech.stopListening()
        text2speech.removeCallback(callback)
        println("Transcriber closed successfully.")
    } catch (e: Exception) {
        println("Error during transcription: ${e.message}")
        e.printStackTrace()
    }
}