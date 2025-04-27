package digital.boline.callAssistant

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.event.Level
import kotlin.time.measureTime

private val dummyScope = CoroutineScope(
    SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("DummyScope")
)


object DummyText2SpeechTest {

    class DummyService(private val latencyRandomMillisecond: IntRange = 30..1000) : ReusableService<String>(dummyScope) {

        init { logger.setLevel(Level.INFO) }

        override suspend fun doComputeAsync(input: String) { // IT runs on a separate coroutine.
            delay(latencyRandomMillisecond.random().toLong())
            println("\t[$serviceName] processing: $input")
        }

        // TODO test when these returns false
        override fun doActivate() {} // Do nothing.
        override fun doDeactivate() {} // Do nothing.
    }


    @Test
    fun testServiceInterface(): Unit = runBlocking {
        val tts = DummyService()
        tts.setLoggingLevel(Level.DEBUG)

        //Should do nothing since it is not activated.
        val outcome1 = tts.computeAsync("")
        assertFalse(tts.isActive.get() && tts.isComputing.get() && outcome1)

        //Should do nothing since it is already activated.
        val outcome2 = tts.deactivate()
        assertFalse(tts.isActive.get() && tts.isComputing.get() && outcome2)

        //Should activate the service.
        val outcome3 = tts.activate()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome3)

        // Should computeAsync the service.
        val outcome4 = tts.computeAsync("Hello")
        assertTrue(tts.isActive.get() && tts.isComputing.get() && outcome4)

        // Should wait the service.
        val outcome5 = tts.wait()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome5)

        // Should computeAsync again the service.
        val outcome6 = tts.computeAsync("World")
        assertTrue(tts.isActive.get() && tts.isComputing.get() && outcome6)

        // Should stop the service.
        val outcome7 =tts.stop()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome7)

        // Should not stop the service.
        val outcome8 =tts.stop()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && !outcome8)

        // Should not re-activate the service.
        val outcome9 = tts.activate()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && !outcome9)

        // Should deactivate the service.
        val outcome10 = tts.deactivate()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && outcome10)

        // Should not re-deactivate the service.
        val outcome11 = tts.deactivate()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && !outcome11)

        // Should not computeAsync the service.
        val outcome12 = tts.computeAsync("Hello")
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && !outcome12)

        // Should not wait the service.
        val outcome13 = tts.wait()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && !outcome13)

        // Should not stop the service.
        val outcome14 = tts.stop()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && !outcome14)

        // Should not deactivate the service .
        val outcome15 = tts.deactivate()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && !outcome15)

        // Should activate the service.
        val outcome16 = tts.activate()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome16)

        // Should not stop the service.
        val outcome17 = tts.stop()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && !outcome17)

        // Should not wait the service.
        val outcome18 = tts.wait()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && !outcome18)

        // Should computeAsync the service.
        val outcome19 = tts.computeAsync("World")
        assertTrue(tts.isActive.get() && tts.isComputing.get() && outcome19)

        // Should stop the service.
        val outcome20 = tts.stop()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome20)

        // Should computeAsync again.
        val outcome21 = tts.computeAsync("Hello")
        assertTrue(tts.isActive.get() && tts.isComputing.get() && outcome21)

        // should wait the service.
        val outcome22 = tts.wait()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome22)

        // Should computeAsync again.
        val outcome23 = tts.computeAsync("World")
        assertTrue(tts.isActive.get() && tts.isComputing.get() && outcome23)

        // Should not deactivate the service.
        val outcome24 = tts.deactivate()
        assertTrue(tts.isActive.get() && tts.isComputing.get() && !outcome24)

        // Should stop the service.
        val outcome25 = tts.stop()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome25)

        // Should deactivate the service.
        val outcome26 = tts.deactivate()
        assertTrue(!tts.isActive.get() && !tts.isComputing.get() && outcome26)

        // Should activate the service.
        val outcome27 = tts.activate()
        assertTrue(tts.isActive.get() && !tts.isComputing.get() && outcome27)

        // Timeout test
        val timeoutSpec = FrequentTimeout(12, 10) { println("React to timeout!") }
        val computationTime = measureTime {
            tts.computeAsync("Hello", timeoutSpec)
            tts.wait()
        }
        println("Computation time $computationTime")
        assertTrue(computationTime.absoluteValue.inWholeMilliseconds <= 12 + 10) // Allow some milliseconds of delay

    }


    // TODO test callback interface
}




class MyReusableService: ReusableService<String>(myScope){
    val onResultCallback = CallbackManager<String, Unit>(logger)

    init {
        setLoggingLevel(Level.INFO)
    }

    override fun doActivate() {
        println("Initializing service resources.")
    }

    override suspend fun doComputeAsync(input: String) {
        // perform some computation here.
        delay(1000)
        val output = "\"$input\""
        println("computation done!")
        // Propagate the result to the callback.
        onResultCallback.invoke(output)
    }

    override fun doStop() {
        // If necessary, stop here the jobs that have been started on `doComputeAsync`.
        super.doStop()
    }

    override suspend fun doWait() {
        // If necessary, wait here for the jobs that have been started on `doComputeAsync`.
        super.doWait()
    }

    override fun doDeactivate() {
        println("Releasing service resources.")
    }

    companion object {
        private val myScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("myScope")
        )
    }
}

suspend fun main() {
    // Construct a new service
    val myReusableService = MyReusableService()

    // Initialize Callbacks
    myReusableService.onErrorCallbacks.add { error: ServiceError ->
        println("Error (${error.source}): ${error.throwable}")
    }
    myReusableService.onResultCallback.add { result: String ->
        println("Result: $result")
    }

    println("------------------------------")

    // Activate the service
    myReusableService.activate()

    // Perform some computation. Note that timeout is optional.
    val computingTimeout = FrequentTimeout(2000, 100) {
        println("Computation went on timeout.")
    }
    myReusableService.computeAsync("Servicing fist request.", computingTimeout)

    // Wait for the computation to finish. Note that the timeout is optional.
    val waitingTimeout = Timeout(2000) {
        println("Waiting went on timeout.")
    }
    myReusableService.wait(waitingTimeout)

    println("------------------------------")

    // Perform another computation.
    myReusableService.computeAsync("Servicing second request.")

    // Stop the computation
    myReusableService.stop()

    // Deactivate the service
    myReusableService.deactivate()

    // You might want to activate the service again and perform some other computation...
}