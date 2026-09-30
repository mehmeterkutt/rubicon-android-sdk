package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PlatformDefaultsTest {
    @Nested
    inner class `system clocks` {
        @Test
        fun `the system clock is the JVM's wall clock`() {
            val before = System.currentTimeMillis()
            val now = Clock.SYSTEM.currentTimeMillis()
            val after = System.currentTimeMillis()

            assertTrue(now in before..after)
        }

        @Test
        fun `the system elapsed clock never goes back`() {
            val readings = List(1_000) { ElapsedClock.SYSTEM.elapsedRealtimeMillis() }

            assertEquals(readings.sorted(), readings)
        }
    }

    @Nested
    inner class `default dispatchers` {
        @Test
        fun `each consumer has its own dispatcher`() {
            val dispatchers = SdkDispatchers.default()

            assertNotSame(dispatchers.writer, dispatchers.uploader)
            assertNotSame(dispatchers.writer, dispatchers.watchdog)
            assertNotSame(dispatchers.uploader, dispatchers.watchdog)
        }

        @Test
        fun `each dispatcher runs one task at a time`() {
            val dispatchers = SdkDispatchers.default()

            listOf(dispatchers.writer, dispatchers.uploader, dispatchers.watchdog).forEach {
                assertEquals(1, maxConcurrency(it))
            }
        }

        // Real threads, bounded by a timeout. Each task does some work without suspending, so tasks
        // overlap only if the dispatcher runs them on several threads at once.
        private fun maxConcurrency(dispatcher: CoroutineDispatcher): Int {
            val running = AtomicInteger()
            val max = AtomicInteger()
            runBlocking {
                withTimeout(10_000) {
                    List(50) {
                        launch(dispatcher) {
                            max.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                            busyWork()
                            running.decrementAndGet()
                        }
                    }.joinAll()
                }
            }
            return max.get()
        }

        private fun busyWork() {
            var x = 0.0
            repeat(20_000) { x += Math.sqrt(it.toDouble()) }
            check(x > 0)
        }
    }

    @Nested
    inner class `http types` {
        @Test
        fun `a request's description names method, url and body size, never the body`() {
            val post = HttpRequest(HttpRequest.Method.POST, "https://h/v1/batch", body = "{\"secret\":1}".toByteArray())
            val get = HttpRequest(HttpRequest.Method.GET, "https://h/v1/config")

            assertEquals("HttpRequest(POST https://h/v1/batch, body=12 bytes)", post.toString())
            assertEquals("HttpRequest(GET https://h/v1/config, body=0 bytes)", get.toString())
            assertEquals(emptyMap<String, String>(), get.headers)
        }

        @Test
        fun `results compare by value`() {
            val cause = IOException("reset")

            assertEquals(HttpResult.Success(200), HttpResult.Success(200))
            assertEquals(HttpResult.HttpError(429, null, 120_000), HttpResult.HttpError(429, null, 120_000))
            assertEquals(HttpResult.NetworkFailure(FailureKind.IO, cause), HttpResult.NetworkFailure(FailureKind.IO, cause))
            assertEquals(7, FailureKind.entries.size)
        }
    }
}
