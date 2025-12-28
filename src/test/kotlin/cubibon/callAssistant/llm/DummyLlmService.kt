package digital.boline.callAssistant.llm

import digital.boline.callAssistant.CallbackInput
import digital.boline.callAssistant.ServiceInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level


/**
 * A simple dummy data class for [DummyLlmService] used to test [LlmService] interfaces.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class DummyLlmRequest(
    override val prompts: String,
    override val messages: String,
    override val modelName: String = "myDummyModel",
    override val maxTokens: Int = 100,
    override val temperature: Float = 0.5f,
    override val topP: Float = 0.5f,
) : LlmRequest<String, String>


/**
 * A simple dummy data class for [DummyLlmService] used to test [LlmService] interfaces.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class DummyLlmResponse(
    override val message: String,
    override val responseLatency: Long = 0,
    override val inputToken: Int = 0,
    override val outputToken: Int = 0,
    override val sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG
) : LlmResponse, CallbackInput {
    override fun copy() = DummyLlmResponse(
        this.message,
        this.responseLatency,
        this.inputToken,
        this.outputToken,
        this.sourceTag
    )
}


/**
 * A dummy LLM-based service used to test the [LlmService] interfaces.
 *
 * @see DummyLlmResponse
 * @see DummyLlmRequest
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class DummyLlmService(private val latencyRandomMillisecond: IntRange = 1..1000) : LlmService<DummyLlmRequest, DummyLlmResponse>() {

    init { logger.setLevel(Level.INFO) }

    private var counter = 1

    override fun doActivate(sourceTag: String) {} // Do nothing.
    override fun doDeactivate(sourceTag: String) {} // Do nothing.
    override suspend fun doComputeAsync(input: DummyLlmRequest, sourceTag: String, scope: CoroutineScope) {
        delay(latencyRandomMillisecond.random().toLong())
        onResultCallbacks.invoke(DummyLlmResponse("Dummy LLM response $counter: '${input.messages}'."), scope)
        counter++
        logInfo("Processing: $input")
    }

    companion object{
        fun runTest(): Unit = runBlocking {
            val llm = DummyLlmService(100..1000)

            llm.onResultCallbacks.add { response: DummyLlmResponse ->
                println("Got llm result: ${response.message}")
            }

            val dummyPrompt = "My dummy prompt"

            llm.activate()
            llm.computeAsync(DummyLlmRequest(dummyPrompt, "Request 1"))
            llm.wait()
            llm.computeAsync(DummyLlmRequest(dummyPrompt, "Request 2"))
            llm.stop()
            llm.deactivate()
        }
    }
}


fun main() = DummyLlmService.runTest()