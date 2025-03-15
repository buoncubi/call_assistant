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
        //val AWS_ACCESS_KEY_ID: String = System.getenv("AWS_ACCESS_KEY_ID")
        //val AWS_SECRET_ACCESS_KEY: String = System.getenv("AWS_SECRET_ACCESS_KEY")
        //val AWS_SESSION_TOKEN: String = System.getenv("AWS_SESSION_TOKEN")
    }
}


fun main() {
    ApplicationRunner()
}