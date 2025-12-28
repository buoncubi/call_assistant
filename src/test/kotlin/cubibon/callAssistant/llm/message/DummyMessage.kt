package cubibon.callAssistant.llm.message


class DummyMessage private constructor() : LlmMessage<String> {
    // See documentation on `LlmMessage`
    override fun getRawMessage(role: MetaRole, contents: List<String>): String {
        return "<$role>: '$contents'"
    }

    private inner class Builder : LlmMessage.Builder<String> {
        override fun public() = this@DummyMessage
    }

    companion object {
        // If necessary pass a logger to DummyMessage and get it from the lambda function below.F
        fun build(): LlmMessage.Builder<String> = DummyMessage().Builder()
    }
}


fun main() {
    val manager = MessagesManager {logger -> MetaMessage.build(DummyMessage.build(), logger) }
    manager.addUser("Dummy user message")
    manager.addAssistant("Dummy assistant message")
    println(manager)

    println(manager.messages)
}