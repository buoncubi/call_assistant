package cubibon.callAssistant.dialogue

import cubibon.callAssistant.Loggable
import cubibon.callAssistant.ServiceError
import cubibon.callAssistant.ServiceInterface
import cubibon.callAssistant.llm.AwsBedrock
import cubibon.callAssistant.llm.AwsBedrockRequest
import cubibon.callAssistant.llm.LlmResponse
import cubibon.callAssistant.llm.LlmService
import cubibon.callAssistant.llm.message.MessageWrapper
import cubibon.callAssistant.llm.message.MessagesManager
import cubibon.callAssistant.llm.message.MetaRole
import cubibon.callAssistant.llm.message.Summarizing
import cubibon.callAssistant.llm.message.buildAwsMessagesManager
import cubibon.callAssistant.llm.prompt.PromptsManager
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.Message

/**
 * Abstract base class for managing a dialogue with a Large Language Model (LLM).
 *
 * This class provides a foundational structure for interacting with an LLM service. It handles
 * message management, prompt formatting, and the lifecycle of the LLM service interaction
 * (activation, deactivation, computation). It is designed to be extended by concrete
 * implementations that specify the message format and the LLM service to be used.
 *
 * @param M The specific message type used by the concrete LLM service (e.g., `software.amazon.awssdk.services.bedrockruntime.model.Message`).
 * @param L The concrete `LlmService` implementation.
 * @property promptsManager Manages the system prompts used in the dialogue.
 * @property messagesManager Manages the history of messages exchanged during the dialogue.
 * @property llmService The service responsible for communicating with the LLM.
 */
abstract class LlmDialogue<M, L: LlmService<*, *>>(
    protected val promptsManager: PromptsManager,
    protected val messagesManager: MessagesManager<M>,
    protected val llmService: L
) : Loggable()
{

    //protected val messageMutex = Mutex() // Does not allow to manipulate the messages concurrently.

    /**
     * Make the dialogue with the LLM happen.
     * It is invoked by the dialogue manager when a new message from the user is incoming.
     */
    abstract fun converse()


    /**
     * Add a user message to the dialogue.
     *
     * @param message The content of the user message.
     */
    fun addUserMessage(message: String): MessageWrapper<M>? =
        addMessage(MetaRole.USER, message)


    /**
     * Add an assistant message to the dialogue.
     *
     * @param message The content of the assistant message.
     */
    fun addAssistantMessage(message: String): MessageWrapper<M>? =
        addMessage(MetaRole.ASSISTANT, message)


    /**
     * Add a message to the dialogue. It is used by [addAssistantMessage] and [addUserMessage].
     *
     * @param role The role of the message sender.
     */
    private fun addMessage(role: MetaRole, message: String): MessageWrapper<M>? =
        //runBlocking {messageMutex.withLock {
            messagesManager.addMessage(role, listOf(message))
        //}}


    /**
     * Activate the LLM service by the mean of the [LlmService] interface.
     *
     * @param sourceTag The source tag for logging purposes.
     */
    open fun activate(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) =
        llmService.activate(sourceTag)


    /**
     * Deactivate the LLM service by the mean of the [LlmService] interface.
     *
     * @param sourceTag The source tag for logging purposes.
     */
    open fun deactivate(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) =
        llmService.deactivate(sourceTag)


    /**
     * Cancel the scope of the LLM service by the mean of the [LlmService] interface.
     */
    open fun cancelScope() = llmService.cancelScope()


    //open suspend fun wait(timeoutSpec: Timeout? = null, sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) =
    //    llmService.wait(timeoutSpec, sourceTag)


    /**
     * Stop the LLM service by the mean of the [LlmService] interface.
     *
     * @param sourceTag The source tag for logging purposes.
     */
    open fun stop(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) {
        if (isComputing())
            llmService.stop(sourceTag)
    }


    /**
     * Check if the LLM service is computing based on the [LlmService] interface.
     *
     * @return `true` if the LLM service is computing, `false` otherwise.
     */
    fun isComputing(): Boolean = llmService.isComputing.get()


    /**
     * Add a callback to the LLM service by the mean of the [LlmService] interface.
     *
     * @param callback The callback to be added.
     * @return The ID of the callback.
     */
    fun addOnResultCallback(callback: suspend ((LlmResponse) -> Unit)):  String =
        llmService.onResultCallbacks.add(callback)


    /**
     * Add an error callback to the LLM service by the mean of the [LlmService] interface.
     *
     * @param callback The error callback to be added.
     * @return The ID of the error callback.
     */
    fun addOnErrorCallback(callback: suspend ((ServiceError) -> Unit)):  String =
        llmService.onErrorCallbacks.add(callback)


    /**
     * Remove a callback from the LLM service by the mean of the [LlmService] interface.
     *
     * @param callbackId The ID of the callback to be removed.
     */
    fun removeOnResultCallback(callbackId: String) =
        llmService.onResultCallbacks.remove(callbackId)


    /**
     * Remove an error callback from the LLM service by the mean of the [LlmService] interface.
     *
     * @param callbackId The ID of the error callback to be removed.
     */
    fun removeOnErrorCallback(callbackId: String) =
        llmService.onErrorCallbacks.remove(callbackId)


    /**
     * Store the used prompts and messages.
     */
    fun store() {
        logInfo("Used prompts:\n{}", promptsManager)
        logInfo("Used messages:\n{}", messagesManager)
        // todo to store somewhere and check synchronization
    }
}



/**
 * A data class that holds the configuration for system prompts used in a dialogue.
 *
 * It bundles together the manager for all prompt templates and specifies which
 * sections of those templates should be used for the main conversation (`callSections`)
 * and for generating summaries (`summarySections`).
 *
 * @property promptsManager The manager that holds and formats all available prompt templates.
 * @property callSections A list of prompt section names to be used during the main conversational turn.
 * @property summarySections A list of prompt section names to be used when generating a summary of the conversation.
 */
data class SystemPrompts(
    val promptsManager: PromptsManager,
    val callSections: List<String>,
    val summarySections: List<String>,
)



/**
 * Manages a dialogue with an AWS Bedrock Large Language Model (LLM), including real-time conversation
 * and periodic summarization.
 *
 * This class extends [LlmDialogue] to implement a specific interaction pattern with AWS Bedrock.
 * It uses two separate [AwsBedrock] service instances: one for handling the primary conversation flow
 * ([llmService]) and another for generating summaries of the conversation history ([llmSummary]).
 *
 * The `converse` method orchestrates the main interaction. It sends the current conversation
 * history and relevant system prompts to the LLM. After a few turns, it also triggers an
 * asynchronous summarization task. The resulting summary is then incorporated into the system
 * prompt for subsequent conversation turns, providing the LLM with context without sending the
 * entire message history each time.
 *
 * @param systemPrompts A data class containing the [PromptsManager] and lists of prompt section
 *                      names to be used for the main conversation and for summarization.
 */
open class AwsDialogue(systemPrompts: SystemPrompts) : // It is an open class only for testing purposes
    LlmDialogue<Message, AwsBedrock>(systemPrompts.promptsManager,
        buildAwsMessagesManager(),
        AwsBedrock()
    )
{
    /**
     * A reference to [SystemPrompts.callSections].
     */
    private val promptsSectionsCall = systemPrompts.callSections


    /**
     * A reference to [SystemPrompts.summarySections].
     */
    private val promptsSectionSummary = systemPrompts.summarySections


    /**
     * An instance of [AwsBedrock] used to interact with the LLM.
     */
    protected val llmSummary = AwsBedrock() // todo it should be private.


    /**
     * An instance of [Summarizing] used to generate summaries of the conversation.
     */
    private var summaryInfo: Summarizing? = null


    /**
     * A data class to store the unique identifiers for callbacks related to the summarization service.
     *
     * This is used to keep track of the specific `onSummary` and `onError` callbacks registered
     * with the `llmSummary` service, allowing for their clean removal during deactivation.
     *
     * @property onSummary The unique ID for the callback that handles successful summary generation.
     * @property onError The unique ID for the callback that handles errors during summary generation.
     */
    data class CallbackId(val onSummary: String, val onError: String)


    /**
     * An instance of the [CallbackId] class.
     */
    private val callbackId: CallbackId


    //private val servicesMutex = Mutex() // Used to assure proper service deactivation.
    //private val promptsMutex = Mutex() // Does not allow to manipulate the prompt summary concurrently.


    /**
     * Initializes the [CallbackId] instance.
     */
    init {
        val summaryCallbackId = llmSummary.onResultCallbacks.add( ::onSummary)
        val summaryErrorCallbackId = llmSummary.onErrorCallbacks.add( ::onSummaryError)
        callbackId = CallbackId(summaryCallbackId, summaryErrorCallbackId)
    }


    /*
     * Activates the [AwsDialogue] instance. See super class documentation for this method.
     *
     * @param sourceTag The source tag for logging purposes.
     * @return `True` if the service is activated, `False` otherwise.
     */
    override fun activate(sourceTag: String) : Boolean {
        if (llmSummary.activate(sourceTag))
            return super.activate(sourceTag)
        return false
    }


    /*
     * Deactivates the [AwsDialogue] instance. See super class documentation for this method.
     *
     * @param sourceTag The source tag for logging purposes.
     * @return `True` if the service is deactivated, `False` otherwise.
     */
    override fun deactivate(sourceTag: String) : Boolean {
        //runBlocking {
        //    servicesMutex.withLock {
            if (llmSummary.isComputing.get())
                llmSummary.stop(sourceTag)

            llmSummary.onResultCallbacks.remove(callbackId.onSummary)
            llmSummary.onErrorCallbacks.remove(callbackId.onError)

            if (llmSummary.deactivate(sourceTag))
                return super.deactivate(sourceTag)
            //else
            return false
       //     }
       // }
    }


    /*
     * Cancels the scope of the [AwsDialogue] instance. See super class documentation for this method.
     */
    override fun cancelScope() {
        super.cancelScope()
        llmSummary.cancelScope()
    }


    /*
     * Makes the dialogue happen. See super class documentation for this method.
     */
    override fun converse() {
        //runBlocking {
            val messages: List<MessageWrapper<*>> = // It is a copy, i.e., an immutable reference.
                //messageMutex.withLock {
                    messagesManager.messagesStr
                //}

            val prompts = // It is an immutable reference.
                //promptsMutex.withLock {
                    promptsManager.formatPrompts(promptsSectionsCall, includeTitle = true, includeSummary = true)
                //}

            //servicesMutex.withLock {
                invokeLlm(prompts, messages)
            //}

            if (!llmSummary.isComputing.get() && messages.size > 3)
            // todo parametrize (consider that it is not called if the transcription T2S has low confidence).
                doSummary()
       // }
    }


    /**
     * Invokes the LLM model with the given prompt and messages. This method is used by [converse].
     *
     * @param prompt The prompt to be given to the LLM model.
     * @param messages The list of messages to be given to the LLM model.
     */
    protected open fun invokeLlm(prompt: String, messages: List<MessageWrapper<*>>){
        // todo manage AWS troutling error if LLM calls are done too quickly.
        // Invoke the LLM model
        val llmRequest = AwsBedrockRequest(
            prompts = prompt,
            messages = messages,
            // arguments: `temperature`, `maxTokens`, `topP` and `modelName` are given by environmental variables.
        )

        llmService.computeAsync(llmRequest)
    }


    /**
     * Performs a summary of the conversation. This method is used by [converse].
     * This method uses [invokeSummary] to change the messages available to the LLM.
     */
    private fun doSummary(){
        //runBlocking {
            summaryInfo = // It is an immutable reference.
                //messageMutex.withLock {
                    messagesManager.getSummaryInfo()
                //}
            val messagesToSummarizing = summaryInfo!!.format(preamble = "", includePreviousSummary = false)

            val prompts: String = // It is an immutable reference.
                //promptsMutex.withLock {
                    promptsManager.formatPrompts(promptsSectionSummary, includeTitle = true, includeSummary = true)
                //}

            //servicesMutex.withLock {
                // Invoke LLM to perform summarization
                invokeSummary(prompts, messagesToSummarizing)
            //}
        //}
    }


    /**
     * Invokes the LLM model with the given prompt and message. This method is used by [doSummary].
     *
     * @param prompt The prompt to be given to the LLM.
     * @param message The set of messages to be given to the LLM.
     */
    protected open fun invokeSummary(prompt: String, message: String) {
        // todo manage AWS troutling error if LLM calls are done too quickly.
        val llmRequest = AwsBedrockRequest(
            prompts = prompt,
            role = ConversationRole.USER,
            message = message,
            // arguments: `temperature`, `maxTokens`, `topP` and `modelName` are given by environmental variables.
        )
        llmSummary.computeAsync(llmRequest)
    }


    /**
     * Handles the response from the LLM model. This method is invoked by the computation of
     * [doSummary] from a callback.
     *
     * @param response The response from the LLM model.
     */
    private fun onSummary(response: LlmResponse) {
        //runBlocking {
            val summary = response.message
            //messageMutex.withLock {
                //promptsMutex.withLock {
                    messagesManager.addSummary(summary, summaryInfo!!)
                    promptsManager.messageSummary = summary
                //}
            //}
        //}
    }


    /**
     * Handles the error from the LLM model. This method is invoked by the computation of
     * [doSummary] from a callback.
     *
     * @param error The error from the LLM model.
     */
    private fun onSummaryError(error: ServiceError) {
        // todo do something here!
        logError("Summary error: {}", error)
    }
}