package digital.boline.callAssistant.awsRunners

import digital.boline.callAssistant.llm.AwsBedrock
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import software.amazon.awssdk.services.bedrockruntime.model.*


/**
 * A simple class to run and manually test the AWS Bedrock service for LLM models based on the Streaming Converse API.
 *
 * @see AwsBedrock
 *
 * @author Luca Buoncompagni © 2025
 */
fun main() {

    Configurator.setRootLevel(Level.WARN)

    // Initialize the AWS Bedrock based on the streaming Converse API
    val awsBedrock = AwsBedrock()
    // Add a callback to print responses
    awsBedrock.addCallback { response ->
        println("Received response: ${response.message}")
    }
    // Start the service
    awsBedrock.start()

    // Create the prompt for the LLM model
    val promptMessages = "Always speak in Italian."
    val prompts = listOf(SystemContentBlock.fromText(promptMessages))

    /*
    // Alternatively, Prompts can be converted from a list of string with:
    val promptMessages = listOf("Always speak in Italian", "...")
    val prompts = promptMessages.map { SystemContentBlock.fromText(it) }
    // Also, you can use the designed interface to manage prompts.
     */

    // Create a sample dialogue. It should start and end with a user message
    val userMessage = Message.builder()
        .content(listOf(ContentBlock.fromText("Hi")))
        .role(ConversationRole.USER)
        .build()
    val assistantMessage = Message.builder()
        .content(listOf(ContentBlock.fromText("I am Antonio")))
        .role(ConversationRole.ASSISTANT)
        .build()
    val userMessage2 = Message.builder()
        .content(listOf(ContentBlock.fromText("What's your name again?")
            , ContentBlock.fromText("Describe yourself in 3 words.")))
        .role(ConversationRole.USER)
        .build()
    val dialogue = listOf(userMessage, assistantMessage, userMessage2) // It must always start with a USER message !!!


    /*
    // Alternatively, you can manage the dialogue with the designed interface.
    val messagesManager = buildAwsMessagesManager()
    messagesManager.addUser("Hi")
    messagesManager.addAssistant("I am Antonio")
    messagesManager.addUser(listOf("What's your name again?", "Describe yourself in 3 words."))
    val dialogue = messagesManager.messages
     */

    // Send the requests
    val job = awsBedrock.makeRequest(dialogue, prompts)

    // Wait for the callback to get called
    job?.join()
    // Close the AWS Bedrock server
    awsBedrock.stop()

    // TODO test start and stopping again
}