package digital.boline.callAssistant.text2speech


import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.ServiceError
import digital.boline.callAssistant.Timeout
import kotlinx.coroutines.*
import org.slf4j.event.Level


/**
 * A simple class to run and manually test [AwsPolly] with [DesktopAudioPlayer].
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object AwsPollyRunner: Loggable() {

    init {
        setLoggingLevel(Level.INFO)
    }

    private fun getPlayer(): DesktopAudioPlayer {
        val player = DesktopAudioPlayer
        player.setLoggingLevel(Level.DEBUG)

        // Set callback for audio play start and end.
        //player.onBeginPlayingCallbacks.add { event: PlaybackEvent? ->
        //    logger.info("Start audio player callback: $event")
        //}
        player.onEndPlayingCallbacks.add { event: PlaybackData ->
            logger.info("End audio player callback: $event")
            endPlayingAudioCallback()
        }

        return player
    }

    private fun getPolly(player: DesktopAudioPlayer): AwsPolly<PlaybackData> {
        val polly = AwsPolly(player)
        polly.setLoggingLevel(Level.DEBUG)
        polly.onErrorCallbacks.add {se: ServiceError ->
            logger.error("Error callback $se")
            endPlayingAudioCallback()
        }
        polly.onResultCallbacks.add {_: Any? ->

        }
        return polly
    }


    private fun endPlayingAudioCallback(){
        // Note that this is added twice.
        logger.info("Test multiple callbacks!")
    }


    fun runTest() = runBlocking{

        val player = getPlayer()
        val polly = getPolly(player)

        logInfo("--------------------------------------")

        polly.activate()
        polly.computeAsync("Hello", sourceTag = "TestSourceTag")
        polly.wait(Timeout(10_000) {id -> logWarn("Waiting timeout reached ($id)") })
        logInfo("-------------")
        polly.computeAsync("World.")
        Thread.sleep(200)
        polly.stop()
        polly.deactivate()

        logInfo("--------------------------------------")

        fun timeoutFunction(sourceTag: String = "") {
            logInfo("Timeout reached! ($sourceTag)")
        }

        val longTimeout = FrequentTimeout(2000, 20) { timeoutFunction() }
        val shortTimeout = FrequentTimeout(200, 20) { timeoutFunction(it) }

        polly.activate()
        polly.computeAsync("Hello", longTimeout)
        polly.wait()
        logInfo("-------------")
        polly.computeAsync("World", shortTimeout, "MySourceTag")
        polly.wait()
        polly.deactivate()

        logInfo("--------------------------------------")

        polly.cancelScope() // After it cannot be reactivated.
    }
}


fun main() = AwsPollyRunner.runTest()