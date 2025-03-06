package llmInterface

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.*
import java.util.concurrent.CompletableFuture

//TODO adjust documentation with links [] and @see
//TODO check @param vs @property also on other files, except for prompts and messages managers

/**
 * Represents the response from an assistant, encapsulating the details of the generated output
 * along with metadata about the interaction.
 *
 * This class is created in `AwsBedrock.makeRequest` method, and given as input parameters to its callbacks.
 *
 * @property message The generated message from the assistant.
 * @property stopReason The reason why the assistant's generation stopped, if applicable.
 * @property role The role of the assistant in the interaction.
 * @property metrics Metrics associated with the assistant's streaming interaction, such as latency or performance details.
 * @property usage Details about token usage during the interaction.
 *
 * @see `LlmStreamingInterface`, `AwsBedrock`
 *
 * @author Luca Buoncompagni © 2025
 */
data class AssistantResponse(val message: String,
                             val stopReason: StopReason?,
                             val role: ConversationRole?,
                             val metrics: ConverseStreamMetrics?,
                             val usage: TokenUsage?
// There is other data that might be added to this data class through the in the `AwsBedrock.makeResponseHandler` function
)

/**
 * Implementation of the `LlmStreamingInterface`, providing an interface to the AWS Bedrock
 * Streaming Converse API, which implement a service for streaming-based interactions with
 * a Large Language Model (LLM).
 *
 * This class manages the lifecycle of the AWS Bedrock client, makes requests to the LLM server,
 * and handles the responses through registered callbacks. It is designed to work in a streaming
 * manner, allowing incremental responses to be handled during the ongoing conversation.
 *
 * The implementation handles LLM configurations such as token limits, response creativity
 * (temperature), and randomness controls (top-p), which is related to environmental variables.
 *
 * Be aware that the servers should be `started` before to make request, and it can be `stopped`.
 * Results are given asynchronously to the callbacks can be added or removed at runtime.
 *
 * Thread safety in this implementation is achieved by using independent subscribers for
 * incoming LLM responses and synchronized handling for shared resources such as callback management.
 *
 * @see `LlmStreamingInterface`, `AssistantResponse`
 *
 * @author Luca Buoncompagni © 2025
 */
class AwsBedrock : LlmStreamingInterface<List<Message>, List<SystemContentBlock>, AssistantResponse> {

    // TODO adjust logs

    /**
     * Companion object for the `AwsBedrock` class containing constants for configuration and model behavior.
     *
     * This object defines default parameters such as the model name, region, and hyperparameters for
     * interacting with the large language model (LLM) via AWS Bedrock. These values aim to unify and
     * simplify the configuration process across different instances that work with the same model.
     *
     * Constants:
     * - `MODEL_NAME`: Specifies the name of the LLM model to be used. This must match the identifier
     *   expected by AWS Bedrock during requests.
     * - `REGION`: Defines the geographic region where the model resources are hosted.
     * - `MAX_TOKENS`: Indicates the maximum number of tokens the model is allowed to produce. Exceeding
     *   this limit truncates the output to avoid overflows.
     * - `TEMPERATURE`: Configures the randomness or "creativity" of the model's output. Higher values
     *   produce more diverse results, while lower values ensure more deterministic responses.
     * - `TOP_P`: Limits the model's vocabulary scope to decrease randomness. Smaller values restrict
     *   the vocabulary, making outputs more focused.
     */
    companion object {
        // TODO get them from environmental variables (also on other classes)
        private const val MODEL_NAME = "anthropic.claude-3-haiku-20240307-v1:0"
        private const val REGION = "eu-central-1"

        // The max number of tokens that the LLM model can produce, after that the response will be truncated.
        // Max value depends on the model, e.g., fo 'anthropic.claude-3-haiku' is 200K tokens of context
        // windows and 4096 output tokens (see more at https://docs.anthropic.com/en/docs/about-claude/models/all-models)
        private const val MAX_TOKENS = 512
        // The temperature (in [0,1]) is associated to the creativity.  Increasing it for more randomness
        // to the output, making it more imaginative but potentially less coherent.
        private const val TEMPERATURE = 0.9F
        // The topP (in [0,1]) is associated with model's vocabulary set. Increasing it to narrow down
        // such a set and, therefore, reduce randomness.
        private const val TOP_P = 0.1F
    }

    /**
     * A flag indicating whether the `AwsBedrock` service is currently running.
     *
     * This variable is used to track the server's operational state and is updated
     * when the `start` or `stop` methods are invoked. Ensures that the server is
     * not started or stopped multiple times consecutively, maintaining proper
     * life-cycle management.
     *
     * Note that when the service is running the `client` would not be `null`.
     */
    private var isRunning = false

    /**
     * Checks whether the server or system is currently running.
     *
     * Note that when the service is running the `client` would not be `null`.
     *
     * @return `true` if the server is running, `false` otherwise.
     */
    override fun isRunning(): Boolean = isRunning

    /**
     * Represents a client for interacting with the AWS Bedrock runtime asynchronously.
     *
     * This variable is initialized when the `start` method is successfully executed, setting up the necessary
     * configurations for connecting to the Bedrock service. It is set to `null` when the service is not running
     * or has been stopped. The client is employed in making requests to the Bedrock service, handling
     * communication, and managing the lifecycle of the connection.
     *
     * The variable is managed internally within the `AwsBedrock` class, ensuring proper initialization and cleanup
     * during the start and stop phases of the service lifecycle. Access to this variable should be appropriately
     * synchronized as needed when used across multiple threads, although it is thread-safe by design.
     */
    private var client: BedrockRuntimeAsyncClient? = null

    /**
     * A thread-safe collection of callbacks to handle responses from the assistant. Each callback
     * receives an instance of `AssistantResponse`, which encapsulates the details of the
     * assistant's response message, metadata, and token usage.
     *
     * This set is synchronized during addition, removal, and invocation of callbacks to ensure
     * thread safety. Callbacks are typically invoked during the completion of asynchronous operations
     * such as processing requests in `makeRequest`.
     */
    private val callbacks = mutableSetOf<(AssistantResponse) -> Unit>()

    /**
     * Starts the AWS Bedrock server by initializing the client and setting the system status to running.
     * This function ensures the server is not already running before proceeding with the start operation.
     *
     * @return `true` if the server starts successfully, `false` otherwise.
     */
    override fun start(): Boolean {
        if (!super.start()) {
            return false // Do not start twice.
        }
        try {
            // Initialise the client and start the connecting with AWS Bedrock based on the Streaming Converse API.
            client  = BedrockRuntimeAsyncClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(REGION))
                .build()

            isRunning = true
            println("LLM server started.")
            return true
        } catch (ex: Exception) {
            println("Error starting LLM server: ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }

    /**
     * Stops the AWS Bedrock server and releases any associated resources.
     * This function attempts to close the client connection and update the server status.
     *
     * @return `true` if the server stops successfully, `false` otherwise.
     */
    override fun stop(): Boolean {
        if (!super.stop()) {
            return false
        } // else: `isRunning` is necessary `true`

        try {
            // Stop the connection with the AWS Bedrock service
            client!!.close()    // if ``isRunning` is `true` than  `client` is not `null`
            //client = null

            isRunning = false
            println("LLM server stopped.")
            return true
        } catch (ex: Exception) {
            println("Error stopping LLM server: ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }

    /**
     * Adds a callback to handle responses from the assistant.
     * This method enables clients to register a function that will be invoked whenever
     * a new `AssistantResponse` is provided by the assistant. The callback is added
     * in a thread-safe manner.
     *
     * @param callback The function to be called with an `AssistantResponse` as its parameter.
     *                 It defines the logic to process or handle the assistant's response.
     *
     * @return `true` if the callback was successfully added, `false` otherwise. Returns `false`
     *         if the callback is already added or if the operation fails.
     */
    override fun addCallback(callback: (AssistantResponse) -> Unit): Boolean {
        super.addCallback(callback) // Log a warning if the AWS Bedrock service is already running

        synchronized(callback) {
            return callbacks.add(callback)
        }
    }

    /**
     * Removes a previously registered callback from the assistant response handler.
     * This method ensures thread-safe removal of the callback from the internal list of registered callbacks.
     *
     * @param callback The function to be removed, which was previously registered to handle `AssistantResponse`.
     *                 The callback defines processing logic for the assistant's response.
     *
     * @return `true` if the callback was successfully removed, `false` otherwise. Returns `false`
     *         if the callback was not registered or if the operation fails.
     */
    override fun removeCallback(callback: (AssistantResponse) -> Unit): Boolean {
        super.removeCallback(callback) // Log a warning if the AWS Bedrock service is already running

        synchronized(callback) {
            return callbacks.remove(callback)
        }
    }

    /**
     * Creates and returns a `ConverseStreamResponseHandler` instance for handling responses from a conversational
     * large language model (LLM) stream. The response handler processes various events emitted during the
     * streaming operation, such as message start, content updates, metadata retrieval, and stream completion.
     *
     * The handler accumulates incremental content responses into a complete message, tracks metadata such as
     * token usage and metrics, and processes the stop reason of the conversation. Once the stream completes,
     * it constructs an `AssistantResponse` object encapsulating the processed information, and invokes all
     * the registered callbacks with the generated response.
     *
     * It is thread safe by design since each request to AWS Bedrock made through `makeRequest`  exploits a
     * new subscriber returned by `makeResponseHandler`. This allows to disregard waiting and synchronization,
     * but it might create to many subscriber.
     *
     * @return A `ConverseStreamResponseHandler` capable of processing and handling a response stream from an LLM,
     *         and used by the `makeRequest` method.
     */
    private fun makeResponseHandler(): ConverseStreamResponseHandler {

        // Initialise the data that will be encoded in a `AssistantResponse` instance
        // and provided to the callback when the results are ready
        val responseMessage = StringBuilder()
        var responseStopReason: StopReason? = null
        var responseRole: ConversationRole? = null
        var responseMetrics: ConverseStreamMetrics? = null
        var responseUsage: TokenUsage? = null

        // Build and the return a new subscribe for the AWS Bedrock request that will be called back
        // asynchronously by the Converse Streaming API.
        return ConverseStreamResponseHandler.builder()
            .subscriber(
                // Functions called by the AWS Bedrock Streaming Converse API over time.
                ConverseStreamResponseHandler.Visitor.builder()
                    .onMessageStart { startEvent ->  // Called when an incoming message occurs?
                        // println("LLM message's response.")
                        responseRole = startEvent.role()
                    }
                    //.onContentBlockStart { // Called when a new response block starts being evaluated?
                    //    println("Start LLM block response...")
                    //}
                    .onContentBlockDelta { chunk ->  // Called when a new response is available.
                        // Incremental LLM response
                        //chunk.delta().type() // will always be `text` since images or other are not used.
                        responseMessage.append(chunk.delta().text())
                    }
                    //.onContentBlockStop {  // Called when a new response block stops being evaluated?
                    //    println("\nStop LLM block response.")
                    //}
                    .onMessageStop { stopEvent ->  // Called when a new response block stops being evaluated
                        //println("\nConversation complete (due to $reason): '${it.stopReasonAsString()}'")
                        //it.additionalModelResponseFields() // It will always be `null` since no documents are requested to the LLM model.
                        responseStopReason = stopEvent.stopReason()
                    }
                    .onMetadata { metadata -> // Called when metadata of the response is ready, it occurs when the response is ready.
                        //println("(latency: $latency ms, tokens: ${metadata.usage()})
                        responseMetrics = metadata.metrics()
                        responseUsage = metadata.usage()

                        // In the current implementation they are always null
                        //metadata.trace().guardrail()
                        //metadata.performanceConfig()
                    }
                    //.onDefault { streamOutput -> // Called when either an unknown event, or an unhandled event occurs.
                    //    println("WARNING: Unknown AWS Bedrock Converse streaming state: ${streamOutput}.")
                    //}
                    .build()
            )
            .onError {
                // Called if the AWS Bedrock Streaming Converse API generate an error.
                println("Error during AWS request: ${it.message}")
            }
            .onComplete {
                // Called when the result of the LLM model is final.
                println("Stream completed.")

                // Construct object for the callbacks.
                // There are other possible data that can be associated with the `AssistantResponse`.
                val assistantResponse = AssistantResponse(
                    message=responseMessage.toString(),
                    stopReason=responseStopReason,
                    role=responseRole,
                    metrics=responseMetrics,
                    usage=responseUsage,
                )

                // Invokes all the callbacks in a thread safe manner.
                synchronized(callbacks) {
                    callbacks.forEach {it(assistantResponse) }
                }

                // responseMessage.clear()
                // TODO be sure that all the resources are closed
            }
            .build()
    }

    /**
     * Sends an asynchronous request to the AWS Bedrock server using the provided dialogue and system prompts.
     * If the server is not running, the method returns `null`. Otherwise, it initiates a streaming
     * conversation request and processes the response using a response handler. Results of the
     * AWS Bedrock requests trigger the call of the callbacks associated with this class.
     *
     * @param dialogue A list of `Message` objects representing the conversational dialogue to be sent.
     * @param prompts A list of `SystemContentBlock` objects defining the system-specific prompts or configuration.
     *
     * @return A `CompletableFuture` instance that represents the asynchronous operation for the streaming conversation,
     *         or `null` if the server is not running or an error occurs during the interaction.
     */
    override fun makeRequest(dialogue: List<Message>, prompts: List<SystemContentBlock>): CompletableFuture<*>? {
        if(!isRunning()) {
            // Avoid making request if the AWS Bedrock service is stopped.
            println("Error: cannot make a request to a not running AWS Bedrock server.")
            return null
        } // else: `client` is necessary `true`


        try {
            // Ask something to the LLM model and wait for their response through callback
            val job = client!!.converseStream(
                { requestBuilder ->
                    requestBuilder.modelId(MODEL_NAME)
                        .system(prompts)
                        .messages(dialogue)
                        .inferenceConfig { configBuilder ->
                            configBuilder
                                .maxTokens(MAX_TOKENS)
                                .temperature(TEMPERATURE)
                                .topP(TOP_P)
                                // .stopSequences("stop1", "stop2")
                        }
                },
                makeResponseHandler()
            )

            return job
        } catch (ex: Exception) {
            println("Error during inference: ${ex.message}")
            return null
        }
    }
}
