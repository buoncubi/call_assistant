package digital.boline.callAssistant.dialogue

import digital.boline.callAssistant.llm.LlmInteract
import digital.boline.callAssistant.speech2text.Speech2Text
import digital.boline.callAssistant.text2speech.Text2Speech

class DialogueManager(
    private val speech2Text: Speech2Text<*>,
    private val text2speech: Text2Speech,
    private val llmInteract: LlmInteract<*,*>)
{

    init {
        speech2Text.onResultCallbacks.add { onUserMessage(it) }
    }

    fun onUserMessage(message: String) {

    }

    fun start() {




        val text = speech2Text.listen()
        val response = llmInteract.interact(text)
        text2speech.speak(response)
    }

    fun stop() {
        speech2Text.stop()
        speech2Text.stop()
        llmInteract.stop()

        speech2Text.deactivate()
        text2speech.deactivate()
        llmInteract.deactivate()
    }


}