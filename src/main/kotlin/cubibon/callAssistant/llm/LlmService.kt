package cubibon.callAssistant.llm

import cubibon.callAssistant.CallbackInput
import cubibon.callAssistant.CallbackManager
import cubibon.callAssistant.ReusableService
import cubibon.callAssistant.speech2text.AwsTranscribe
import cubibon.callAssistant.speech2text.DesktopMicrophone
import cubibon.callAssistant.speech2text.Speech2TextStreamBuilder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


/**
 * Defines the scope in which Coroutine for SpeechToText (i.e., class derived from [LlmService]). This scope involves
 * 3 jobs, one makes WEB based requests, and the other two waits to check timeout.
 *
 * This scope runs on [Dispatchers.Default], which is optimized for CPU-intensive tasks. It also has a [CoroutineName]
 * that identifies the scope as `Speech2TextScope`. It uses the [SupervisorJob], which ensures that any child coroutine
 * will be cancelled if the parent scope is cancelled.
 */
private val llmServiceScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineName("LlmServiceScope")
)



/**
 * The base interface defining the request object to be given to the LLM provider. It is basically a data class, and it
 * is required by [LlmService].
 *
 * @param M The type of the messages to be given to the LLM provider.
 * @param P The type of the prompt to be given to the LLM provider.
 *
 * @property prompts The prompt to be given to the LLM model.
 * @property messages The messages to be given to the LLM model.
 * @property modelName The name of the model to be used by the LLM provider.
 * @property maxTokens The maximum number of tokens that the LLM can produce.
 * @property temperature The temperature to configure the LLM model.
 * @property topP The top-probability to configure the LLM model.
 *
 * @see LlmService
 * @see AwsBedrock
 * @see AwsBedrockRequest
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface LlmRequest<P, M>  {
    val prompts: P
    val messages: M
    val modelName: String
    val temperature: Float
    val topP: Float
    val maxTokens: Int
    // Other parameter might be possible, e.g., `maxToken`, `stopSequence`, `guardrail`, `tool`, etc.
}



/**
 * The base interface defining the response object given by the LLM provider. It is basically a data class, and it is
 * required by [LlmService]. This class is provided as input to the [LlmService.onResultCallbacks].
 *
 * @property message The message response given by the LLM.
 * @property responseLatency The computation time in milliseconds that the LLM took to produce the `message`. If the
 * value is undefined, it is `-1`
 * @property inputToken The number of input tokens given by the LLM provider. If the value is undefined, it is `-1`.
 * @property outputToken The number of output tokens given by the LLM provider. If the value is undefined, it is `-1`.
 * @property sourceTag An identifier given by the class that requested the LLM response, and it is propagated to the
 * callback. If the value is undefined, it is an empty string.
 *
 * @see LlmService
 * @see AwsBedrock
 * @see AwsBedrockResponse
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface LlmResponse : CallbackInput {
    val message: String
    val responseLatency: Long
    val inputToken: Int
    val outputToken: Int
    override val sourceTag: String
    // Other parameter might be possible, e.g., `stopReason`, etc.
}



/**
 * An abstract class that implements the interaction with an LLM model based on [ReusableService].
 *
 * A [ReusableService] need to be [activate] before to perform computation based on [computeAsync], which allow defining
 * a timeout with relative callback. Then, you can decide to [wait] for the computation to be done, with an optional
 * timeout (and related callback), or you can manually [stop] the computation. Finally, you can perform [computeAsync]
 * again and, when the service is no longer need, you should use [deactivate]. To allow such a behaviour, this class
 * implements the [doActivate], [doComputeAsync], [doWait], [doStop] and [doDeactivate] specifically for speech-to-text
 * processing. Note that all these operations already occur into try-catch blocks managed by the [doThrow] method, which
 * invokes [onErrorCallbacks]. For more see [ReusableService].
 *
 * In this class, [computeAsync] is in charge invoke an LLM model based on the data given through an [LlmRequest]. Then,
 * it gives back the results through the [onResultCallbacks], which provides an [LlmResponse].
 *
 * @param I The type of the request object to be given to the LLM provider.
 * @param O The type of the response object given by the LLM provider.
 *
 * @property onResultCallbacks The object providing the input audio stream to process.
 * @property onErrorCallbacks The object providing the output audio stream to process.
 * @property isActive Whether the service resources have been initialized or not.
 * @property isComputing Whether the service is currently computing or not.
 *
 * @see Speech2TextStreamBuilder
 * @see DesktopMicrophone
 * @see AwsTranscribe
 * @see ReusableService
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class LlmService<I: LlmRequest<*, *>, O: LlmResponse> : ReusableService<I>(llmServiceScope) {
    val onResultCallbacks = CallbackManager<O>(logger)
}
