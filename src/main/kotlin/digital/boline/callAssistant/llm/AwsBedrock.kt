package digital.boline.callAssistant.llm

import digital.boline.callAssistant.*
import digital.boline.callAssistant.ApplicationRunner.Companion.AWS_VENV_REGION
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.SdkCancellationException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture



/**
 * The implementation of [LlmResponse] for AWS Bedrock, which should be given to [AwsBedrock.computeAsync].
 *
 * @param prompts The list of prompts.
 * @param messages The list of messages. Each item in the list encodes the message `role`, and the last item should have
 * the `USER` role.
 * @param maxTokens The maximum number of tokens that the LLM model can produce. By default, it is set through the
 * `AWS_BEDROCK_MAX_TOKENS` environmental variable.
 * @param temperature The temperature to configure the LLM model. By default, it is set to the `AWS_BEDROCK_TEMPERATURE`
 * environmental variable.
 * @param topP The top-probability to configure the LLM model. By default, it is set to the `AWS_BEDROCK_TOP_P`
 * environmental variable.
 * @param modelName The LLM model to be used. By default, it is set to the `AWS_BEDROCK_MODEL_NAME` environmental variable.
 *
 * @see LlmRequest
 * @see AwsBedrock
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class AwsBedrockRequest(
    override val prompts: List<SystemContentBlock>,
    override val messages: List<Message>,
    override val maxTokens: Int = System.getenv("AWS_BEDROCK_MAX_TOKENS").toInt(),
    override val temperature: Float = System.getenv("AWS_BEDROCK_TEMPERATURE").toFloat(),
    override val topP: Float = System.getenv("AWS_BEDROCK_TOP_P").toFloat(),
    override val modelName: String = System.getenv("AWS_BEDROCK_MODEL_NAME")
) : LlmRequest<List<SystemContentBlock>, List<Message>>
{

    companion object {
        /**
         * Builds the AWS compliant system prompts from a list of string.
         *
         * @param prompts The list of prompts as strings.
         * @return The list of prompts as a list of [SystemContentBlock].
         */
        fun buildPrompts(prompts: List<String>): List<SystemContentBlock> =
            prompts.map { SystemContentBlock.fromText(it) }

        /**
         * Builds the AWS compliant system prompts from a string.
         *
         * @param prompts The prompt as a string.
         * @return The prompt a list of [SystemContentBlock].
         */
        fun buildPrompts(prompts: String): List<SystemContentBlock> =
            buildPrompts(listOf(prompts))

        /**
         * Builds the AWS compliant messages from a list of string.
         *
         * @param role The role of the messages.
         * @param message The message as strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildMessages(role: ConversationRole, message: String): Message =
            Message.builder().role(role).content(ContentBlock.fromText(message)).build()

        /**
         * Builds the AWS compliant messages from a list of string.
         *
         * @param role The role of the messages.
         * @param messages The messages as a list of strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildMessages(role: ConversationRole, messages: List<String>): Message =
            Message.builder().role(role).content(messages.map { ContentBlock.fromText(it) }).build()

        /**
         * Builds the AWS compliant messages from a string with the [ConversationRole.USER] role.
         *
         * @param message The messages as a strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildUserMessages(message: String): Message =
            buildMessages(ConversationRole.USER, message)

        /**
         * Builds the AWS compliant messages from a list of string with the [ConversationRole.USER] role.
         *
         * @param messages The messages as a list of strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildUserMessages(messages: List<String>): Message =
            buildMessages(ConversationRole.USER, messages)

        /**
         * Builds the AWS compliant messages from a string with the [ConversationRole.ASSISTANT] role.
         *
         * @param message The messages as a strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildAssistantMessages(message: String): Message =
            buildMessages(ConversationRole.ASSISTANT, message)

        /**
         * Builds the AWS compliant messages from a list of string with the [ConversationRole.ASSISTANT] role.
         *
         * @param messages The messages as a list of strings.
         * @return The AWS Bedrock [Message] encoding the input data.
         */
        fun buildAssistantMessages(messages: List<String>): Message =
            buildMessages(ConversationRole.ASSISTANT, messages)
    }
}



/**
 * The implementation of [LlmResponse] for AWS Bedrock, which is given by [AwsBedrock.onResultCallbacks].
 *
 * @property message The generated message from the LLM.
 * @property responseLatency The time in milliseconds that was taken by the LLM to generate the response. If the value
 * is undefined, it is `-1`.
 * @property inputToken The number of tokens in the input prompt. If the value is undefined, it is `-1`.
 * @property outputToken The number of tokens in the output response. If the value is undefined, it is `-1`.
 * @property sourceTag An identifier given by the class that calls [AwsBedrock.computeAsync] and propagated to the
 * [AwsBedrock.onResultCallbacks]. By default, it is an empty string.
 *
 * @see LlmResponse
 * @see AwsBedrock
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class AwsBedrockResponse(
    override val message: String,
    override val responseLatency: Long,
    override val inputToken: Int,
    override val outputToken: Int,
    override val sourceTag: String
) : LlmResponse



/**
 * The implementation of [LlmService] for AWS Bedrock based on the Streaming Converse API.
 *
 * It implements a [ReusableService], which provides [activate], [computeAsync], [wait], [stop], and [deactivate]
 * facilities for managing the lifecycle of the service asynchronously. It also provides logging, service's state
 * management, timeout handling, and error as well as result callbacks.
 *
 * For using the LLM model, this class requires [AwsBedrockRequest] (which has default value defined through
 * environmental variables) and provides [AwsBedrockResponse]. Also, this class requires these environmental variables:
 * `AWS_REGION`, `AWS_ACCESS_KEY_ID`, and `AWS_ACCESS_KEY_ID`.
 *
 * It follows an example for using this class
 * ```
 *      val bedrock = AwsBedrock()
 *
 *      // Set the callback for errors within AwsBedrock.
 *      bedrock.onErrorCallbacks.add { se: ServiceError ->
 *          println("Got LLM Bedrock error: ('${se.source}', ${se.sourceTag}) ${se.throwable}")
 *      }
 *
 *      // Set the callback for results within AwsBedrock.
 *      bedrock.onResultCallbacks.add { response: AwsBedrockResponse ->
 *          println("Got LLM Bedrock response: $response")
 *      }
 *
 *      // Initialize the AWS Bedrock service.
 *      bedrock.activate()
 *
 *      // Define the request to the LLM model.
 *      val prompts: List<SystemContentBlock> = AwsBedrockRequest.buildPrompts("My prompt")
 *      val message: Message = AwsBedrockRequest.buildMessages(ConversationRole.USER,"My message")
 *      // Note that `request` allow defining other parameter (e.g., temperature, top_p, etc.)
 *      val request: AwsBedrockRequest = AwsBedrockRequest(prompts, listOf(message))
 *      // Optionally define a computation timeout (which is reset every time a part of the LLM response is received).
 *      val timeoutSpec = FrequentTimeout(200, 20) {
 *          println("Time out occurred!")
 *      }
 *      // Make the request to the LLM model
 *      bedrock.computeAsync(request, timeoutSpec, "MySourceTag")
 *
 *      // Wait for the response from the LLM model with an optional timeout.
 *      val waitTimeout = Timeout(20000) { sourceTag ->
 *          println("Waiting timeout occurred! ($sourceTag)")
 *      }
 *      bedrock.wait(waitTimeout, "MyTimeoutSourceTag")
 *      // Or stop the computation.
 *      bedrock.stop()
 *
 *      // Eventually, make new computations...
 *
 *      // Finally, always remember to release the AWS Bedrock resources when it is no longer needed.
 *      bedrock.deactivate()
 *
 *      // You might want to `activate` the Bedrock service again and start new computation.
 *
 *      // Cancel the scope and all related jobs. After this the service cannot be activated again.
 *      bedrock.cancelScope()
 * ```
 * See `AwsBedrockRunner.kt` in the test src folder, for an example of how to use this class.
 *
 * @property client The AWS Bedrock client. It is `null` when the service is not activated. This property is `private`.
 * @property llmJob The job of the LLM computation. It is `null` when the service is not running. This property is
 * `private` and it is used to implement stopping mechanisms.
 * @property requestHandler The handler of the AWS Bedrock streaming event. It is `null` when the service is not
 * running. This property is `private` and it is used to implement stopping mechanisms.
 * @property onResultCallbacks The list of callbacks invoked when the LLM is producing a response.
 * @property onErrorCallbacks The list of callbacks invoked when an error occurs.
 * @property isActive Whether the service resources have been initialized or closed.
 * @property isComputing Whether the service is currently computing a request from the LLM.
 * @property sourceTag An identifier given by the class that invokes the [computeAsync] and provided to the
 * [onResultCallbacks], or [onErrorCallbacks] in case of exception. By default, it is an empty string.
 *
 * @see ReusableService
 * @see LlmService
 * @see AwsBedrockRequest
 * @see AwsBedrockResponse
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class AwsBedrock : LlmService<AwsBedrockRequest, AwsBedrockResponse>() {

    // See documentation above.
    private var client: BedrockRuntimeAsyncClient? = null
    private var llmJob: CompletableFuture<Void>? = null
    private var requestHandler: ConverseStreamResponseHandler? = null
    var sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.


    /**
     * Acquire the resources required by the AWS Bedrock server by initializing the [client] property. This method is
     * called by [activate]. It runs in a try-catch block that handle any exceptions, and it invokes the
     * [onErrorCallbacks] with [ErrorSource.ACTIVATING].
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doActivate(sourceTag: String) {
        client = BedrockRuntimeAsyncClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.create()) // TODO manage credential on production and further configure client
            .region(Region.of(AWS_VENV_REGION))
            /*.httpClient(
                // It is faster with respect to Netty (default) httpClient but less stable
                // It requires `implementation("software.amazon.awssdk:aws-crt-client")` as gradle dependence
                AwsCrtAsyncHttpClient.builder()
                //.connectionTimeout(Duration.ofSeconds(1))
                .build()
            )*/
            .build()
    }


    /**
     * Release the resources required by the AWS Bedrock server by closing the [client] property and setting it to
     * `null`. This method is called by [deactivate]. It runs in a try-catch block that handle any exceptions, and
     * it invokes the [onErrorCallbacks] with [ErrorSource.DEACTIVATING].
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doDeactivate(sourceTag: String) {
        // Stop the connection with the AWS Bedrock service
        client!!.close() // It takes 2 seconds with Natty HTTP-client (see other commented client, i.e., AwsCrt).
        client = null
    }


    /**
     * Creates and returns a [ConverseStreamResponseHandler] instance for handling responses from a conversational LLM
     * stream. It encompasses several event of the stream, i.e.,
     *  - `onMessageStart`: Called when an incoming message occurs.
     *  - `onContentBlockStart`: Called when a new response block starts being evaluated.
     *  - `onContentBlockDelta`: Called when a new response is available.
     *  - `onContentBlockStop`: Called when a new response block stops being evaluated.
     *  - `onMessageStop`: Called when the message stops.
     *  - `onMetadata`: Called when metadata is available.
     *  - `onComplete`: Called when the stream completes. It invokes the [onResultCallbacks].
     *  - `onDefault`: Called when either an unknown or an unhandled event occurs.
     *  - `onError`: Called when an error occurs during the stream. It invokes the [onErrorCallbacks] with
     *  [ErrorSource.COMPUTING].
     *
     * The handler accumulates incremental content responses into a complete message, tracks metadata such as token
     * usage and metrics, etc. Once the stream completes, it constructs an [AwsBedrockResponse] object encapsulating the
     * processed information, and invokes all the registered callbacks with the generated response. Note that this
     * handler is designed to reset the timeout every time a part of the LLM response is obtained.
     *
     * Note that this method will propagate the [sourceTag] to [onErrorCallbacks] and [onResultCallbacks].
     *
     * @return A [ConverseStreamResponseHandler] capable of processing and handling a response stream from an LLM, and
     * used by the [computeAsync] method.
     */
    private fun buildResponseHandler(): ConverseStreamResponseHandler {

        // Initialise the data that will be encoded in a `AssistantResponse` instance
        // and provided to the callback when the results are ready
        val responseMessage = StringBuilder()
        var inputToken: Int = -1
        var outputToken: Int = -1
        var responseLatency: Long = -1

        // Build and the return a new subscribe for the AWS Bedrock request that will be called back
        // asynchronously by the Converse Streaming API.
        return ConverseStreamResponseHandler.builder()
            .subscriber(
                // Functions called by the AWS Bedrock Streaming Converse API over time.
                ConverseStreamResponseHandler.Visitor.builder()
                    .onMessageStart { startEvent ->  // Called when an incoming message occurs?
                        logDebug("Bedrock client responded to incoming messages (start role: '{}').",
                            startEvent.role())
                        // responseRole = startEvent.role()
                    }
                    .onContentBlockStart { // Called when a new response block starts being evaluated?
                        logTrace("Bedrock client got a response block...")
                    }
                    .onContentBlockDelta { chunk ->  // Called when a new response is available.
                        // Incremental LLM response
                        //chunk.delta().type() // will always be `text` since images or other are not used.
                        val chunkText = chunk.delta().text()
                        responseMessage.append(chunkText)
                        resetTimeout()
                        logTrace("Bedrock client got a response chunk: {}.", chunkText)
                    }
                    .onContentBlockStop {  // Called when a new response block stops being evaluated?
                        logTrace("Bedrock client finished a response block...")
                    }
                    .onMessageStop { stopEvent ->  // Called when a new response block stops being evaluated
                        logTrace("Bedrock client conversation complete due to '{}'",
                            stopEvent.stopReasonAsString())
                        //it.additionalModelResponseFields() // It will always be `null` since no documents are requested to the LLM model.
                        // responseStopReason = stopEvent.stopReason()
                        // Stop reason might be:
                        //    "end_turn" -> // Normal completion
                        //    "max_tokens" -> // Reached token limit
                        //    "tool_use" -> // Model requested to use a tool
                        //    "content_filtered" -> // Content was filtered
                        //    "stop_sequence"
                        //    "guardrail_intervened"
                        //    UNKNOWN_TO_SDK_VERSION((String)null);
                    }
                    .onMetadata { metadata -> // Called when metadata of the response is ready, it occurs when the response is ready.
                        responseLatency = metadata.metrics().latencyMs()
                        inputToken = metadata.usage().inputTokens()
                        outputToken = metadata.usage().outputTokens()
                        // In the current implementation they are always null
                        //metadata.trace().guardrail()
                        //metadata.performanceConfig()
                        logTrace("Bedrock client completed conversation step with latency: {}ms, input tokens: {}, and output tokens: {}.",
                            responseLatency, inputToken, outputToken)
                    }
                    .onDefault { streamOutput -> // Called when either an unknown event, or an unhandled event occurs.
                        logWarn("Unknown Bedrock streaming state: {}.", streamOutput)
                    }
                    .build()
            )
            .onError { e: Throwable ->
                // Called if the AWS Bedrock Streaming Converse API generate an error.
                if (e.cause !is CancellationException && e !is CancellationException && e !is SdkCancellationException) {
                    logError("Bedrock client got an error during request", e)
                    doThrow(ServiceError(e, ErrorSource.COMPUTING, sourceTag))
                } else {
                    logTrace("AWS Bedrock client cancelled with exception: ", e)
                }
            }
            .onComplete {
                // Called when the result of the LLM model is final.

                // Construct object for the callbacks.
                // There are other possible data that can be associated with the `AssistantResponse`.
                val assistantResponse = AwsBedrockResponse(
                    message=responseMessage.toString(),
                    responseLatency = responseLatency,
                    inputToken = inputToken,
                    outputToken = outputToken,
                    sourceTag = sourceTag
                )

                // Invokes all the callbacks in a thread safe manner.
                if (llmJob != null) // If it is null, it means that the request has been cancelled.
                    onResultCallbacks.invoke(assistantResponse)

                //logInfo("Bedrock client completed streaming request (and sent callbacks notified) with results: {}.",
                //    assistantResponse)
            }
            .build()
    }


    /**
     * Sends an asynchronous request to the AWS Bedrock server using the provided [AwsBedrockRequest]. This function
     * is called by [computeAsync] and it invokes [onResultCallbacks] when the LLM provide a response.
     *
     * Note that this function run in a try-catch block that handle any exceptions, and it invokes the
     * [onErrorCallbacks] with [ErrorSource.COMPUTING].
     *
     * @param input An `AwsBedrockRequest` object containing the necessary information for the request.
     * @param sourceTag An identifier given by the class that invokes the [computeAsync] and provided to the
     * [onResultCallbacks], or [onErrorCallbacks] in case of exception.
     */
    override suspend fun doComputeAsync(input: AwsBedrockRequest, sourceTag: String) {

        this.sourceTag = sourceTag

        // Ask something to the LLM model and wait for their response, which will be provided through callback.
        requestHandler = buildResponseHandler()
        llmJob = client?.converseStream(
            { requestBuilder ->
                requestBuilder.modelId(input.modelName)
                    .apply { // Only set system prompts if they're not null or empty
                        if (input.prompts.isNotEmpty()) {
                            system(input.prompts)
                        }
                    }
                    .messages(input.messages)
                    .inferenceConfig { configBuilder ->
                        configBuilder
                            .maxTokens(input.maxTokens)
                            .temperature(input.temperature)
                            .topP(input.topP)
                            // .stopSequences("stop1", "stop2")
                    }
            },
            requestHandler
        )
        llmJob?.join()

        // reset resources
        llmJob = null
        requestHandler = null
        this.sourceTag = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.
    }


    /**
     * Stops the current request by cancelling the [llmJob] and invoking `requestHandler.complete`. This method is
     * called by [stop].
     *
     * Note that this function run in a try-catch block that handle any exceptions, and it invokes the
     * [onErrorCallbacks] with [ErrorSource.STOPPING].
     *
     * @param sourceTag It is not used in this implementation.
     */
    override fun doStop(sourceTag: String) {
        llmJob?.cancel(true)
        llmJob = null

        requestHandler?.complete()
        requestHandler = null

        this.sourceTag = ServiceInterface.UNKNOWN_SOURCE_TAG // i.e., an empty string.

        super.doStop(sourceTag)
    }
}
