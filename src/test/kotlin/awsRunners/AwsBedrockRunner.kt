package awsRunners

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import llmInterface.AwsBedrock
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.bedrockruntime.model.*


/**
 * A simple class to run and manually test the AWS Bedrock service to exploit LLM models based
 * on the Streaming Converse API.
 *
 * @see `AwsBedrock.kt`, `LlmInterface.kt`
 *
 * @author Luca Buoncompagni © 2025
 */
fun main() {

    // TODO Configure logger
    val logger = LoggerFactory.getLogger("ROOT") as Logger
    logger.level = Level.INFO

    // Initialize the AWS Bedrock based on the streaming Converse API
    val awsBedrock = AwsBedrock()
    // Add a callback to print responses
    awsBedrock.addCallback { response ->
        println("Received response: $response")
    }
    // Start the service
    awsBedrock.start()

    // Create the prompt for the LLM model
    val promptMessages = "Always speak in Italian"
    val prompts = listOf(SystemContentBlock.fromText(promptMessages))

    /*
    // Alternatively, Prompts can be converted from a list of string with:
    val promptMessages = listOf("Always speak in Italian", "...")
    val prompts = promptMessages.map { SystemContentBlock.fromText(it) }
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
    // Alternatively, dialogue can be converted from pairs of role enumerator and string as:
    enum class Role(val id: String) { // Move the enumerator outside the main function
        USER("user"), ASSISTANT("assistant" );
        fun toMessageRole(): ConversationRole = ConversationRole.fromValue(id)
    }
    val messages: List<Pair<Role, List<String>>> = listOf(
        Pair(Role.USER, listOf("Hi")),
        Pair(Role.ASSISTANT, listOf("I am Antonio")),
        Pair(Role.USER, listOf("What's your name again?", "Describe yourself in 3 words."))
    )
    val dialogue = messages.map { (role, texts) ->
        Message.builder()
            .content(texts.map { ContentBlock.fromText(it) })
            .role(role.toMessageRole())
            .build()
    }
    */

    // Send the requests
    val job = awsBedrock.makeRequest(dialogue, prompts)

    // Wait for the callback to get called
    job?.join()
    // Close the AWS Bedrock server
    awsBedrock.stop()

    // TODO test start and stopping again
}