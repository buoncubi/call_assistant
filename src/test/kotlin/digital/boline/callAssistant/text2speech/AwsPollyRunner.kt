package digital.boline.callAssistant.text2speech


import digital.boline.callAssistant.FrequentTimeout
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.ServiceError
import digital.boline.callAssistant.Timeout
import kotlinx.coroutines.*
import javazoom.jl.player.advanced.PlaybackEvent
import org.slf4j.event.Level


/**
 * A simple class to run and manually test the AWS Polly service for text-to-speech.
 *
 * @see AwsPolly
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
        player.onEndPlayingCallbacks.add { event: PlaybackEvent? ->
            logger.info("End audio player callback: $event")
        }

        return player
    }

    private fun getPolly(player: DesktopAudioPlayer): AwsPolly<PlaybackEvent?> {
        val polly = AwsPolly(player)
        polly.setLoggingLevel(Level.DEBUG)
        polly.onErrorCallbacks.add {se: ServiceError ->
            logger.error("Error callback $se")
        }
        return polly
    }


    fun runTest() = runBlocking{

        val player = getPlayer()
        val polly = getPolly(player)

        logInfo("--------------------------------------")

        polly.activate()
        polly.computeAsync("Hello")
        polly.wait(Timeout(10_000) { logWarn("Waiting timeout reached") } )
        logInfo("-------------")
        polly.computeAsync("World.")
        Thread.sleep(200)
        polly.stop()
        polly.deactivate()

        logInfo("--------------------------------------")

        fun timeoutFunction() {
            logInfo("Timeout reached!")
        }

        val longTimeout = FrequentTimeout(2000, 20) { timeoutFunction() }
        val shortTimeout = FrequentTimeout(200, 20) { timeoutFunction() }

        polly.activate()
        polly.computeAsync("Hello", longTimeout)
        polly.wait()
        logInfo("-------------")
        polly.computeAsync("World", shortTimeout)
        polly.wait()
        polly.deactivate()

        logInfo("--------------------------------------")
    }
}


fun main() {
    AwsPollyRunner.runTest()
}