package cubibon.callAssistant.dialogue

import cubibon.callAssistant.Loggable
import cubibon.callAssistant.Utils
import cubibon.callAssistant.llm.AwsBedrockResponse
import cubibon.callAssistant.llm.message.MessageWrapper
import cubibon.callAssistant.llm.prompt.PromptsParser
import cubibon.callAssistant.speech2text.Speech2Text
import cubibon.callAssistant.speech2text.Speech2TextStreamBuilder
import cubibon.callAssistant.speech2text.Transcription
import cubibon.callAssistant.text2speech.DummyText2Speech
import kotlinx.coroutines.*
import org.slf4j.event.Level
import java.io.InputStream


/*
object DummyLlmDialogue : LlmDialogue, Loggable() {

    override val llmService = DummyLlmService()
    override val promptsManager = getDummyPromptsManager()
    override val messagesManager = MessagesManager {logger -> MetaMessage.build(DummyMessage.build(), logger) }

    override fun converse(userMessage: Transcription) {
        val dummyPrompt = promptsManager.formatPrompts(listOf(DUMMY_PROMPT_SECTION_TITLE))

        val llmRequest = DummyLlmRequest(
            prompts = dummyPrompt,
            messages = userMessage.message,
        )

        llmService.computeAsync(llmRequest)
    }



    private const val DUMMY_PROMPT_SECTION_TITLE = "Dummy Section"
    private fun getDummyPromptsManager(): PromptsManager {
        val promptSyntax = StringBuilder(
            """
            |__ $DUMMY_PROMPT_SECTION_TITLE __
            |This is a dummy prompt section.
            | 
            """.trimMargin("|")
        )
        return PromptsParser.parse(StringBuilder(promptSyntax)).getPromptManager()
    }
}




object DummyDialogue : Loggable(DummyDialogue::class.java) {

    suspend fun runTest() {

        setLoggingLevel(Level.INFO)

        val llmDialogue = DummyLlmDialogue
        val s2t = DummySpeech2Text
        val dialogue = DialogueManager(s2t, DummyText2Speech, llmDialogue)

        dialogue.activateAndStart()

        // Simulate user input
        val dummyUser = CoroutineScope(Dispatchers.Default).launch {
            try {
                val latencyRandomMillisecond: IntRange = 500..2000
                delay(latencyRandomMillisecond.random().toLong())
                var cnt = 2

                while (this.isActive) {
                    delay(latencyRandomMillisecond.random().toLong())
                    val userMessage = "Simulated dummy transcribed text $cnt."
                    logInfo("User input: '$userMessage'")
                    s2t.onResultCallbacks.invoke(Transcription(userMessage, confidence = 1.0))
                    cnt++
                }
            } catch (_: CancellationException) {}
        }

        delay(10_000)
        dummyUser.cancel()
        dialogue.stopDeactivateAndCancelScopes()

        println(llmDialogue.messagesManager)
    }

}

suspend fun main() = DummyDialogue.runTest()
 */


class TestAwsDialogue : AwsDialogue(DummyPrompt.getSystemPrompt()) {

    init { setLoggingLevel(Level.INFO) }


    override fun invokeLlm(prompt: String, messages: List<MessageWrapper<*>>) {
        Thread.sleep(IntRange(200, 2000).random().toLong())

        val messageStr = messages.joinToString { message ->
            "${message.role}: ${message.contents}"
        }

        logInfo("Invoking LLM with prompts: '${Utils.escapeCharacters(prompt)}'," +
                " and message:  '${Utils.escapeCharacters(messageStr)}'.")

        val msg = messages.last().contents.joinToString()
        val dummyResponse = AwsBedrockResponse(message = msg, responseLatency = 0L, inputToken = 0, outputToken = 0, sourceTag = "" )



        llmService.onResultCallbacks.invoke(dummyResponse, null)
    }


    override fun invokeSummary(prompt: String, message: String) {
        Thread.sleep(IntRange(200, 2000).random().toLong()) // todo remove all thread.sleep that should be stoppable in a coroutine

        logInfo("Invoking LLM to perform summarization with prompts: " +
                "'${Utils.escapeCharacters(prompt)}', " +
                "and message: '${Utils.escapeCharacters(message)}'.")

        val dummyResponse = AwsBedrockResponse(message = message, responseLatency = 0L, inputToken = 0, outputToken = 0, sourceTag = "" )
        llmSummary.onResultCallbacks.invoke(dummyResponse, null)
    }
}


object DummySpeech2TextKeyboard : Speech2Text(DummyInputStreamBuilder) {

    init { logger.setLevel(Level.INFO) }

    // if `false` write through keyboard, if `true` automatically generates messages with incremental numbers.
    private const val AUTOMATIZE = true

    override suspend fun doComputeAsync(input: Unit, sourceTag: String, scope: CoroutineScope) { // IT runs on a separate coroutine.
        var cnt = 0
        while (this.isActive.get()) {
            val startTime = System.currentTimeMillis()
            val message = if (!AUTOMATIZE) {
                // read from keyboard
                readln().trim()
            } else {
                // Automatize, i.e., generate automatic user input as incremental numbers.
                val latencyRandomMillisecond: IntRange = 100..2000
                delay(latencyRandomMillisecond.random().toLong())
                cnt++
                cnt.toString()
            }
            val endTime = System.currentTimeMillis()
            // Set a random confidence in [0,1]
            val confidence = IntRange(1, 100).random() / 100.0
            val dummyTranscription =
                Transcription(message, confidence = confidence, startTime = startTime, endTime = endTime)
            onResultCallbacks.invoke(dummyTranscription, scope)
        }
    }

    override fun doActivate(sourceTag: String) {} // Do nothing.
    override fun doDeactivate(sourceTag: String) {} // Do nothing.

    object DummyInputStreamBuilder: Speech2TextStreamBuilder, Loggable() {
        override fun build(): InputStream? {
            // Here it should generate an audio input stream.
            return null
        }
    }
}



suspend fun main() {
    val dm = DialogueManager.build({
        DummySpeech2TextKeyboard
    }, {
        TestAwsDialogue()
    }, {
        DummyText2Speech
    })

    DummyText2Speech.setLoggingLevel(Level.INFO)

    dm.open()

    /*
    // Simulate user input
    val dummyUser = CoroutineScope(Dispatchers.Default).launch {
        try {
            val latencyRandomMillisecond: IntRange = 500..800
            delay(latencyRandomMillisecond.random().toLong())
            var cnt = 2

            while (this.isActive) {
                delay(latencyRandomMillisecond.random().toLong())
                //val userMessage = "$cnt"

                val userMessage = readln().trim()

                DummySpeech2Text.onResultCallbacks.invoke(Transcription(userMessage, confidence = 1.0))
                cnt++
            }
        } catch (_: CancellationException) {}
    }
    delay(7_000)
    dummyUser.cancel()
     */

    delay(100_000)


    dm.close()
    dm.store()
}

object DummyPrompt{

    fun getSystemPrompt(): SystemPrompts {

        val dummyPromptSectionTitle = "Call Context"
        val dummySummarySectionTitle = "Summary Instruction"

        val promptSyntax = StringBuilder(
            """
            |__* Meta *__ 
            |- *MessageSummaryTitle* = Previous Dialogue
            |
            |__ $dummyPromptSectionTitle __
            |Response with 10 words at most.
            |
            |__ $dummySummarySectionTitle __
            |Summarize this dialogue as concisely as possible.
            | 
            """.trimMargin("|")
        )
        val promptManager = PromptsParser.parse(StringBuilder(promptSyntax)).getPromptManager()
        return SystemPrompts(promptManager,
            listOf(dummyPromptSectionTitle),
            listOf(dummySummarySectionTitle))
    }
}