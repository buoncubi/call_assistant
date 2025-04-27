package digital.boline.callAssistant.llm

import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.ServiceError
import digital.boline.callAssistant.Timeout
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level
import software.amazon.awssdk.services.bedrockruntime.model.*


/**
 * A simple class to run and manually test the AWS Bedrock service for LLM models based on the Streaming Converse API.
 *
 * @see AwsBedrock
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object AwsBedrockRunner: Loggable() {

    init {
        setLoggingLevel(Level.INFO)
    }


    private fun getMessages(): List<Message> {
        val messages = mutableListOf<Message>()
        messages.add(AwsBedrockRequest.buildUserMessages("Hi"))
        messages.add(AwsBedrockRequest.buildAssistantMessages("I am Antonio"))
        messages.add(AwsBedrockRequest.buildUserMessages(listOf("What's your name again?", "Describe yourself in 3 words.")))


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
        bedrock.computeAsync(AwsBedrockRequest(prompts, messages), timeoutSpec)
        bedrock.wait(Timeout(40000) { println("Waiting timeout reached!") })
        bedrock.deactivate()

        logInfo(" 4 -------------------------------------")
        logInfo("End test.")
    }
}

fun main() {
    AwsBedrockRunner.runTest()
}