package com.rubicon.analytics.stress

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.integration.FakeBackend
import com.rubicon.analytics.integration.IntegrationSupport
import com.rubicon.analytics.internal.DefaultAnalytics
import com.rubicon.analytics.internal.RubiconCore
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan section 5, concurrency: the real core under 16 threads of mixed calls, with real files, the
 * real transport and real dispatchers. Every accepted event reaches the server exactly once and in
 * per-thread order, and every event that does not is counted under its reason.
 */
class StressTest {
    @TempDir
    lateinit var root: File

    private val server = MockWebServer()

    @AfterEach
    fun stop() {
        RubiconCore.resetForTests()
        server.shutdown()
    }

    @Test
    fun `16 threads times 5000 mixed calls`() {
        RubiconCore.resetForTests()
        val backend = FakeBackend()
        server.dispatcher = backend
        server.start()
        val backpressure = ConcurrentLinkedQueue<String>()
        val config =
            IntegrationSupport.config(server) {
                flush.at = 100
                logging.eventObserver =
                    EventObserver { update ->
                        if (update is EventUpdate.Dropped && update.reason == DropReason.BACKPRESSURE) backpressure += update.messageIds
                    }
            }
        val analytics = RubiconCore.create(config, IntegrationSupport.platform(root)) as DefaultAnalytics
        val tracked = AtomicInteger()
        val failures = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(THREADS)

        repeat(THREADS) { thread ->
            pool.execute {
                start.await()
                try {
                    repeat(CALLS) { i ->
                        when {
                            i % 50 == 0 -> analytics.register("thread_$thread", i)
                            i % 1000 == 999 -> analytics.flush()
                            i % 7 == 0 -> analytics.screen("screen_$thread", mapOf("t" to thread, "i" to i))
                            else -> analytics.track("E", mapOf("t" to thread, "i" to i))
                        }
                        if (i % 50 != 0 && i % 1000 != 999) tracked.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        assertTrue(analytics.flushBlocking(120_000), "everything was sent")
        assertEquals(0, failures.get())

        val received = backend.uploads.flatMap { IntegrationSupport.events(it) }
        val ids = received.map { it["message_id"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size, "no event arrives twice")

        // Per-thread order: the i of each thread's events only grows.
        received
            .map { it["properties"]!!.jsonObject }
            .groupBy { it["t"]!!.jsonPrimitive.int }
            .forEach { (thread, events) ->
                val order = events.map { it["i"]!!.jsonPrimitive.int }
                assertEquals(order.sorted(), order, "order of thread $thread")
            }

        val device =
            DeviceStore(
                FileKeyValueStore(root),
                Log(LogLevel.OFF, null, RecordingLogger()),
                FakeClock(),
            ) { "unused" }.apply { load() }
        val dropped = device.state.diagnostics.dropped
        assertTrue(received.isNotEmpty(), "events reached the server")
        // Tight loops on 16 threads outrun any writer: most calls end as backpressure or queue_full,
        // which is the bounded behaviour Queue 1 and 5 decide. What matters is that nothing is lost
        // without being counted.
        assertEquals(backpressure.size, dropped["backpressure"] ?: 0, "backpressure counted exactly")
        assertEquals(tracked.get(), received.size + dropped.values.sum(), "every event was sent or counted: $dropped")
    }

    private companion object {
        const val THREADS = 16
        const val CALLS = 5000
    }
}
