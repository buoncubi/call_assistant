package cubibon.callAssistant.dialogue

import cubibon.callAssistant.llm.prompt.PromptsDeserializer
import cubibon.callAssistant.speech2text.AwsTranscribe
import cubibon.callAssistant.speech2text.DesktopMicrophone
import cubibon.callAssistant.text2speech.AwsPolly
import cubibon.callAssistant.text2speech.DesktopAudioPlayer
import kotlinx.coroutines.*

/*
// TEST CALLBACK SYNCHRONIZATION, START, and CANCEL
class Callback(val callback: suspend (CoroutineScope, Int) -> Unit) {

    private val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun run() {
        val parentJob = parentScope.launch {
            try {
                println("Parent coroutine started")


                repeat(5) {
                    launch {
                        try {
                            callback(this, it)
                            delay(20)
                        } catch (e: CancellationException) {
                            println("Aux coroutine cancelled")
                        }
                    }
                    delay(10)
                }


            } catch (e: CancellationException) {
                println("Parent coroutine cancelled")
            }
        }

        Thread.sleep(1500)
        println("Cancelling parent scope...")
        parentJob.cancel() // Cancels the parent scope and all children
    }
}

suspend fun myCallback(scope: CoroutineScope, id: Int) {
    val childJob = scope.launch {
        try {
            repeat(10) { i ->
                delay(400)
                println("Child $id coroutine running $i")
            }
        } catch (e: CancellationException) {
            println("Child $id coroutine cancelled")
        }
    }
    childJob.join()
}

fun main() {

    Callback(::myCallback).run()

    // Give the coroutine time to run
    Thread.sleep(3000)
    println("Main function ends")
}
*/






/*
class DialogueTest {

    // todo test dialogue synchronization based on dummy classes

}

fun main() {
    val text2Speech = AwsPolly(DesktopAudioPlayer)
    val speech2text = AwsTranscribe(DesktopMicrophone)

    val promptsFilePath = "src/main/resources/prompts.bytes"
    val llmDialogue = AwsDialogue.buildFromSerializedPrompts(promptsFilePath)

    val dialogue = DialogueManager(speech2text, text2Speech, llmDialogue)

    dialogue.activate()
    dialogue.start()
    Thread.sleep(120_000)
    dialogue.stopDeactivateAndCancelScopes()

    println("--------------------------")
    println(llmDialogue.promptsManager)
    println("--------------------------")
    println(llmDialogue.messagesManager)
    println("--------------------------")
}
*/





suspend fun main() {

    // Prompt file constants
    val promptFilePath = "src/main/resources/prompts/voicemail.bytes"
    val promptSectionTitle = listOf("Ruolo", "Formato", "Comportamento", "Report Chiamata")
    val summarySectionTitle = listOf("Sommarizzazione")
    // Deserialize prompt from file
    val promptManager = PromptsDeserializer.fromBytes(promptFilePath)!!
    val prompts =  SystemPrompts(promptManager,
        promptSectionTitle, summarySectionTitle)

    val dm = DialogueManager.build({
        AwsTranscribe(DesktopMicrophone)
    }, {
        AwsDialogue( prompts)
    }, {
        AwsPolly(DesktopAudioPlayer)
    })

    dm.open()

    delay(500_000)

    dm.close()
    dm.store()
}