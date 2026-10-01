package com.rubicon.analytics.internal

import com.rubicon.analytics.Analytics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NoOpAnalyticsTest {
    private val analytics: Analytics = NoOpAnalytics

    private fun callEverything(
        analytics: Analytics,
        i: Int,
    ) {
        analytics.track("Event $i", mapOf("i" to i))
        analytics.screen("screen_$i")
        analytics.identify("user-$i", mapOf("plan" to "pro"))
        analytics.register("key_$i", i)
        analytics.unregister("key_$i")
        analytics.flush()
        analytics.optOut()
        analytics.optIn()
        analytics.isOptedOut()
        analytics.deviceId
        analytics.clearLocalData()
        analytics.reset()
    }

    @Nested
    inner class `accepts every call` {
        @Test
        fun `every method returns without doing anything`() {
            callEverything(analytics, 0)

            assertTrue(analytics.isOptedOut())
            assertEquals("", analytics.deviceId)
        }

        @Test
        fun `input the real instance rejects does not throw`() {
            val unsupported = mapOf("at" to Instant.EPOCH, "nan" to Double.NaN, "self" to Any())

            analytics.track("", unsupported)
            analytics.screen(" ".repeat(300), unsupported)
            analytics.identify("", unsupported)
            analytics.register("", Any())
            analytics.unregister("")
        }

        @Test
        fun `calls from many threads at once never throw`() {
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            repeat(8) { thread ->
                pool.execute {
                    start.await()
                    try {
                        repeat(1000) { callEverything(analytics, thread * 1000 + it) }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }

            start.countDown()
            pool.shutdown()

            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
        }
    }

    @Nested
    inner class `nothing is sent` {
        @Test
        fun `it reports opted out, also after optIn`() {
            analytics.optIn()

            assertTrue(analytics.isOptedOut())
        }

        @Test
        fun `it has no installation id, also after clearLocalData`() {
            analytics.clearLocalData()

            assertEquals("", analytics.deviceId)
        }
    }

    @Nested
    inner class `the vocabulary` {
        @Test
        fun `Analytics has exactly the decided methods`() {
            val methods =
                Analytics::class.java.declaredMethods
                    .filterNot { it.isSynthetic }
                    .map { it.name }
                    .toSet()

            assertEquals(
                setOf(
                    "track",
                    "screen",
                    "identify",
                    "reset",
                    "register",
                    "unregister",
                    "flush",
                    "optOut",
                    "optIn",
                    "isOptedOut",
                    "clearLocalData",
                    "getDeviceId",
                ),
                methods,
            )
        }

        @Test
        fun `every method returns Unit except the two readers`() {
            Analytics::class.java.declaredMethods
                .filterNot { it.isSynthetic || it.name == "isOptedOut" || it.name == "getDeviceId" }
                .forEach { assertEquals(Void.TYPE, it.returnType, it.name) }
        }
    }
}
