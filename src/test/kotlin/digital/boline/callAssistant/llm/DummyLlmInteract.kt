package digital.boline.callAssistant.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.event.Level

data class DummyLlmRequest(
    override val prompts: String,
    override val messages: String,
    override val modelName: String = "myDummyModel",
    override val maxTokens: Int = 100,
    override val temperature: Float = 0.5f,
    override val topP: Float = 0.5f,
) : LlmRequest<String, String>


data class DummyLlmResponse(
    override val message: String,
    override val responseLatency: Long = 0,
    override val inputToken: Int = 0,
    override val outputToken: Int = 0,
) : LlmResponse


class DummyLlmInteract(private val latencyRandomMillisecond: IntRange = 1..1000) : LlmInteract<DummyLlmRequest, DummyLlmResponse>() {

    init { logger.setLevel(Level.INFO) }

    private var counter = 0

    override fun doActivate() {} // Do nothing.
    override fun doDeactivate() {} // Do nothing.
    override suspend fun doComputeAsync(input: DummyLlmRequest) {
        delay(latencyRandomMillisecond.random().toLong())
        onResultCallbacks.invoke(DummyLlmResponse("Dummy LLM response $counter: ${input.messages}."))
        counter++
        logInfo("Processing: $input")
    }
}


fun main(): Unit = runBlocking {
    val llm = DummyLlmInteract(100..1000)

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