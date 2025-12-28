package cubibon.callAssistant

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.event.Level
import kotlin.time.measureTime


/**
 * A simple test of the [ReusableService], which also include [Service] and [CallbackManager].
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object DummyText2SpeechTest {

    private val dummyScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("DummyScope")
    )

    data class DummyCallbackInput(val data: String, override val sourceTag: String): CallbackInput{
        override fun copy() = DummyCallbackInput(
            this.data,
            this.sourceTag
        )
    }


    class DummyService(private val latencyRandomMillisecond: IntRange = 30..1000) : ReusableService<String>(dummyScope) {

        init { logger.setLevel(Level.INFO) }

        val onResultCallback = CallbackManager<DummyCallbackInput>(logger)

        override suspend fun doComputeAsync(input: String, sourceTag: String, scope: CoroutineScope) { // IT runs on a separate coroutine.
            delay(latencyRandomMillisecond.random().toLong())
            onResultCallback.invoke(DummyCallbackInput(input, sourceTag), scope)
        }

        override fun doActivate(sourceTag: String) {} // Do nothing.
        override fun doDeactivate(sourceTag: String) {} // Do nothing.
    }



    @Test
    fun testServiceInterface(): Unit = runBlocking {
        val service = DummyService()
        service.setLoggingLevel(Level.DEBUG)

        //Should do nothing since it is not activated.
        val outcome1 = service.computeAsync("")
        assertFalse(service.isActive.get() && service.isComputing.get() && outcome1)

        //Should do nothing since it is already activated.
        val outcome2 = service.deactivate()
        assertFalse(service.isActive.get() && service.isComputing.get() && outcome2)

        //Should activate the service.
        val outcome3 = service.activate()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome3)

        // Should computeAsync the service.
        val outcome4 = service.computeAsync("Hello")
        assertTrue(service.isActive.get() && service.isComputing.get() && outcome4)

        // Should wait the service.
        val outcome5 = service.wait()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome5)

        // Should computeAsync again the service.
        val outcome6 = service.computeAsync("World")
        assertTrue(service.isActive.get() && service.isComputing.get() && outcome6)

        // Should stop the service.
        val outcome7 =service.stop()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome7)

        // Should not stop the service.
        val outcome8 =service.stop()
        assertTrue(service.isActive.get() && !service.isComputing.get() && !outcome8)

        // Should not re-activate the service.
        val outcome9 = service.activate()
        assertTrue(service.isActive.get() && !service.isComputing.get() && !outcome9)

        // Should deactivate the service.
        val outcome10 = service.deactivate()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && outcome10)

        // Should not re-deactivate the service.
        val outcome11 = service.deactivate()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && !outcome11)

        // Should not computeAsync the service.
        val outcome12 = service.computeAsync("Hello")
        assertTrue(!service.isActive.get() && !service.isComputing.get() && !outcome12)

        // Should not wait the service.
        val outcome13 = service.wait()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && !outcome13)

        // Should not stop the service.
        val outcome14 = service.stop()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && !outcome14)

        // Should not deactivate the service .
        val outcome15 = service.deactivate()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && !outcome15)

        // Should activate the service.
        val outcome16 = service.activate()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome16)

        // Should not stop the service.
        val outcome17 = service.stop()
        assertTrue(service.isActive.get() && !service.isComputing.get() && !outcome17)

        // Should not wait the service.
        val outcome18 = service.wait()
        assertTrue(service.isActive.get() && !service.isComputing.get() && !outcome18)

        // Should computeAsync the service.
        val outcome19 = service.computeAsync("World")
        assertTrue(service.isActive.get() && service.isComputing.get() && outcome19)

        // Should stop the service.
        val outcome20 = service.stop()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome20)

        // Should computeAsync again.
        val outcome21 = service.computeAsync("Hello")
        assertTrue(service.isActive.get() && service.isComputing.get() && outcome21)

        // should wait the service.
        val outcome22 = service.wait()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome22)

        // Should computeAsync again.
        val outcome23 = service.computeAsync("World")
        assertTrue(service.isActive.get() && service.isComputing.get() && outcome23)

        // Test callbacks
        val test = "CallbackTest"
        val sourceTag = "SourceTag"
        val callbackId = service.onResultCallback.add {assertTrue(it.data == test && it.sourceTag == sourceTag) }
        service.computeAsync(test, sourceTag = sourceTag)
        service.onResultCallback.remove(callbackId)

        // Should not deactivate the service.
        val outcome24 = service.deactivate()
        assertTrue(service.isActive.get() && service.isComputing.get() && !outcome24)

        // Should stop the service.
        val outcome25 = service.stop()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome25)

        // Should deactivate the service.
        val outcome26 = service.deactivate()
        assertTrue(!service.isActive.get() && !service.isComputing.get() && outcome26)

        // Should activate the service.
        val outcome27 = service.activate()
        assertTrue(service.isActive.get() && !service.isComputing.get() && outcome27)

        // Timeout test
        val testTimeoutId = "TestTimeoutId"
        val timeoutSpec = FrequentTimeout(12, 10) {
            id -> println("React to timeout! '$id'")
            assertTrue(id==testTimeoutId)
        }
        val computationTime = measureTime {
            service.computeAsync("Hello", timeoutSpec = timeoutSpec, sourceTag = testTimeoutId)
            service.wait()
        }
        println("Computation time $computationTime")
        assertTrue(computationTime.absoluteValue.inWholeMilliseconds <= 12 + 10) // Allow some milliseconds of delay

    }
}
