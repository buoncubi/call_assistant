package cubibon.callAssistant

/**
 * The entry point of this project. It runs the phone call-based Human-LLM interaction.
 */
class ApplicationRunner: cubibon.callAssistant.Loggable() {

    init {
        try {
            runApplication()
        } catch (e: Exception) {
            logError("Error while running application", e)
            // todo do something in case of error, use throw to respawn clients
        }
    }

    private fun runApplication() {
        logInfo("Application started")

        // todo to be implemented
    }

    companion object {
        val AWS_VENV_REGION: String = System.getenv("AWS_REGION")
    }
}


fun main() {
    ApplicationRunner()
}