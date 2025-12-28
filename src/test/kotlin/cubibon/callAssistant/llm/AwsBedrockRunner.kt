package digital.boline.callAssistant.llm

import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.ServiceError
import digital.boline.callAssistant.Timeout
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import software.amazon.awssdk.services.bedrockruntime.model.*


/**
 * A simple class to run and manually test [AwsBedrock].
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object AwsBedrockRunner: Loggable() {

    init {
        setLoggingLevel(Level.INFO)
    }


    private fun getMessages(): List<Message> {
        val messages = mutableListOf<Message>()
        messages.add(AwsBedrockRequest.buildUserMessage("Hi"))
        messages.add(AwsBedrockRequest.buildAssistantMessage("I am Antonio"))
        messages.add(AwsBedrockRequest.buildUserMessage(listOf("What's your name again?", "Describe yourself in 3 words.")))


        /*
        // Alternatively, you can manage the dialogue with the designed interface.
        val messagesManager = buildAwsMessagesManager()
        messagesManager.addUser("Hi")
        messagesManager.addAssistant("I am Antonio")
        messagesManager.addUser(listOf("What's your name again?", "Describe yourself in 3 words."))
        val messages = messagesManager.messages
         */

        return messages
    }


    fun runTest() = runBlocking {

        val bedrock = AwsBedrock()
        bedrock.setLoggingLevel(Level.DEBUG)

        bedrock.onResultCallbacks.add { response: AwsBedrockResponse ->
            logInfo("Got LLM Bedrock response callback: $response")
        }

        bedrock.onErrorCallbacks.add { se: ServiceError ->
            logError("Got LLM Bedrock error callback: ('${se.source}') ${se.throwable}")
        }

        val prompts = AwsBedrockRequest.buildPrompts("Always speak in Italian.")
        val messages = getMessages()

        logInfo(" 1 -------------------------------------")

        bedrock.activate()
        bedrock.computeAsync(AwsBedrockRequest(prompts, messages))
        bedrock.wait()
        logInfo(" 2 -------------------------------------")

        bedrock.computeAsync(AwsBedrockRequest(prompts, messages, topP = 0.1f, temperature = 0.8f))
        Thread.sleep(200)
        bedrock.stop()
        bedrock.deactivate()

        logInfo(" 3 -------------------------------------")

        val timeoutSpec = FrequentTimeout(200, 20) {
            logInfo("Timeout occurred!")
        }
        bedrock.activate()
        bedrock.computeAsync(AwsBedrockRequest(prompts, messages), timeoutSpec =timeoutSpec)
        bedrock.wait(timeoutSpec = Timeout(40000) { sourceTag ->
            println("Waiting timeout reached! ($sourceTag)")
        }, sourceTag = "MySourceTag")
        bedrock.deactivate()

        logInfo(" 4 -------------------------------------")
        logInfo("End test.")

        bedrock.cancelScope() // After it cannot be reactivated.
    }
}

fun main() = AwsBedrockRunner.runTest()