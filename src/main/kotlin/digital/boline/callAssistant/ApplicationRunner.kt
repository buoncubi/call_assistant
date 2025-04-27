package digital.boline.callAssistant

/**
 * The entry point of this project. It runs the phone call-based Human-LLM interaction.
 */
class ApplicationRunner: Loggable() {

    init {
        try {
            runApplication()
        } catch (e: Exception) {
            logError("Error while running application", e)
            // TODO something in case of error, use throw to respawn clients
        }
    }

    private fun runApplication() {
        logInfo("Application started")

        // TODO implement
    }

    companion object {
        val AWS_VENV_REGION: String = System.getenv("AWS_REGION")
    }
}


fun main() {
    ApplicationRunner()
}