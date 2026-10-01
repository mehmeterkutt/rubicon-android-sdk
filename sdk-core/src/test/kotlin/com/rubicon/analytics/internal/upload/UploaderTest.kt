package com.rubicon.analytics.internal.upload

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.config.ConfigValidator
import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.http.HttpTransport
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.remote.RemoteConfigClient
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.storage.DropCounter
import com.rubicon.analytics.internal.storage.FileEventStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeNetworkMonitor
import com.rubicon.analytics.testing.FakeTransport
import com.rubicon.analytics.testing.InMemoryEventStore
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class UploaderTest {
    private class Harness(
        testScope: TestScope,
    ) {
        val clock = FakeClock(1_759_147_200_000L)
        val updates = mutableListOf<EventUpdate>()
        val logger = RecordingLogger()
        val log = Log(LogLevel.DEBUG, null, logger)
        val eventStore = InMemoryEventStore(maxEventsPerSegment = 2)
        val transport = FakeTransport()
        val remoteTransport = FakeTransport(whenEmpty = HttpResult.HttpError(404, null, null))
        val network = FakeNetworkMonitor()
        val flags = RuntimeFlags()
        val deviceStore = DeviceStore(InMemoryKeyValueStore(), log, clock) { "device-1" }.apply { load() }
        val signals = Channel<Unit>(Channel.CONFLATED)
        val started = CompletableDeferred(Unit)
        var allowed = true
        var persists = 0
        val inFlight = AtomicInteger()
        var maxInFlight = 0
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        // Waits run on the test scheduler's virtual time, as the real elapsed clock runs with real time.
        val elapsed = ElapsedClock { testScope.testScheduler.currentTime }
        val policy = RetryPolicy(deviceStore, clock, elapsed, Random(1), log)
        val code = ConfigValidator.validate(AnalyticsConfig("rk_test")).snapshot!!
        val remote = RemoteConfigClient(code, deviceStore, remoteTransport, flags, log)

        private val counting =
            object : HttpTransport {
                override fun execute(request: HttpRequest): HttpResult {
                    maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
                    try {
                        return transport.execute(request)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }

        val uploader =
            Uploader(
                eventStore,
                counting,
                code.batchUrl,
                policy,
                remote,
                network,
                flags,
                deviceStore,
                clock,
                log,
                EventObserver { updates += it },
                signals,
                started,
                { persists++ },
            ) { allowed }

        private var seq = 0

        /** Appends [count] events and closes them into segments of two. */
        fun events(count: Int): List<String> {
            val ids = (1..count).map { "m-${++seq}" }
            ids.forEach {
                eventStore.append(
                    """{"type":"track","event":"E","message_id":"$it","timestamp":${clock.currentTimeMillis()}}""",
                )
            }
            eventStore.closeOpenSegment()
            return ids
        }

        fun start() = uploader.start(scope, dispatcher)

        fun signal() = signals.trySend(Unit)

        fun sentBatches(): List<List<String>> = transport.requests.map { batchIds(it) }

        fun batchIds(request: HttpRequest): List<String> =
            Json.parseToJsonElement(String(request.body!!)).jsonObject["batch"]!!.toString().let { batch ->
                Regex("\"message_id\":\"([^\"]+)\"").findAll(batch).map { it.groupValues[1] }.toList()
            }
    }

    private fun uploaderTest(block: suspend TestScope.(Harness) -> Unit) =
        runTest {
            val h = Harness(this)
            try {
                block(h)
            } finally {
                h.scope.cancel()
            }
        }

    @Nested
    inner class `sending` {
        @Test
        fun `batches go oldest first, are deleted after 2xx, and Sent lists their ids`() =
            uploaderTest { h ->
                val ids = h.events(5)
                h.start()
                h.signal()
                runCurrent()

                assertEquals(listOf(ids.subList(0, 2), ids.subList(2, 4), ids.subList(4, 5)), h.sentBatches())
                assertEquals(emptyList<Long>(), h.eventStore.closedSegments())
                assertEquals(ids.chunked(2), h.updates.filterIsInstance<EventUpdate.Sent>().map { it.messageIds })
            }

        @Test
        fun `the body is the envelope with sent_at, posted to the batch endpoint`() =
            uploaderTest { h ->
                h.events(1)
                h.start()
                h.signal()
                runCurrent()

                val request = h.transport.requests.single()
                assertEquals(HttpRequest.Method.POST, request.method)
                assertEquals("https://api.rubicon.example/v1/batch", request.url)
                val body = Json.parseToJsonElement(String(request.body!!)).jsonObject
                assertEquals(h.clock.currentTimeMillis(), body["sent_at"]!!.jsonPrimitive.long)
            }

        @Test
        fun `a batch is kept on failure, retried after the wait with a new sent_at, and deleted after 2xx`() =
            uploaderTest { h ->
                h.events(1)
                h.transport.enqueue(HttpResult.HttpError(503, null, null))
                h.start()
                h.signal()
                runCurrent()
                assertEquals(1, h.eventStore.closedSegments().size)

                h.clock.advanceBy(5_000)
                advanceTimeBy(5_000)
                runCurrent()

                assertEquals(2, h.transport.requests.size)
                val sentAts =
                    h.transport.requests.map {
                        Json
                            .parseToJsonElement(
                                String(it.body!!),
                            ).jsonObject["sent_at"]!!
                            .jsonPrimitive.long
                    }
                assertNotEquals(sentAts[0], sentAts[1])
                assertEquals(emptyList<Long>(), h.eventStore.closedSegments())
            }

        @Test
        fun `it stops at the first failure`() =
            uploaderTest { h ->
                h.events(6)
                h.transport.enqueue(HttpResult.Success(200), HttpResult.HttpError(500, null, null))
                h.start()
                h.signal()
                runCurrent()

                assertEquals(2, h.transport.requests.size)
                assertEquals(2, h.eventStore.closedSegments().size)
            }

        @Test
        fun `a rejected batch is deleted, counted and reported`() =
            uploaderTest { h ->
                val ids = h.events(2)
                h.transport.enqueue(HttpResult.HttpError(400, null, null))
                h.start()
                h.signal()
                runCurrent()

                assertEquals(emptyList<Long>(), h.eventStore.closedSegments())
                assertEquals(mapOf("rejected" to 2), h.deviceStore.state.diagnostics.dropped)
                val dropped = h.updates.filterIsInstance<EventUpdate.Dropped>().single()
                assertEquals(ids, dropped.messageIds)
                assertEquals(DropReason.REJECTED, dropped.reason)
            }

        @Test
        fun `a segment of only corrupt lines is deleted without a request`() =
            uploaderTest { h ->
                // The corruption rules live in FileEventStore, not in the in-memory fake.
                val root =
                    kotlin.io.path
                        .createTempDirectory("uploader")
                        .toFile()
                val files = FileEventStore(root).apply { open("rk_test", h.clock, h.log, DropCounter.NONE) }
                files.append("not json")
                files.closeOpenSegment()
                files.append("""{"type":"track","event":"E","message_id":"ok","timestamp":${h.clock.currentTimeMillis()}}""")
                files.closeOpenSegment()
                val uploader =
                    Uploader(
                        files,
                        h.transport,
                        h.code.batchUrl,
                        h.policy,
                        h.remote,
                        h.network,
                        h.flags,
                        h.deviceStore,
                        h.clock,
                        h.log,
                        null,
                        h.signals,
                        h.started,
                        {},
                    )
                uploader.start(h.scope, h.dispatcher)
                h.signal()
                runCurrent()

                assertEquals(listOf(listOf("ok")), h.sentBatches())
                assertEquals(emptyList<Long>(), files.closedSegments())
                root.deleteRecursively()
            }

        @Test
        fun `retry state changes are handed to the writer`() =
            uploaderTest { h ->
                h.events(1)
                h.transport.enqueue(HttpResult.HttpError(503, null, null))
                h.start()
                h.signal()
                runCurrent()

                assertTrue(h.persists >= 1)
                assertEquals(mapOf("0000000000" to 1), h.deviceStore.state.retry.batches)
            }
    }

    @Nested
    inner class `gates` {
        @Test
        fun `offline blocks and the network coming back resumes`() =
            uploaderTest { h ->
                h.network.setConnected(false)
                h.events(1)
                h.start()
                h.signal()
                runCurrent()
                assertEquals(0, h.transport.requests.size)

                h.network.setConnected(true)
                runCurrent()

                assertEquals(1, h.transport.requests.size)
            }

        @Test
        fun `the kill switch blocks uploads`() =
            uploaderTest { h ->
                h.events(1)
                h.flags.stopCollecting()
                h.start()
                h.signal()
                runCurrent()

                assertEquals(0, h.transport.requests.size)
            }

        @Test
        fun `a closed consent gate blocks until it opens and the next wake`() =
            uploaderTest { h ->
                h.events(1)
                h.allowed = false
                h.start()
                h.signal()
                runCurrent()
                assertEquals(0, h.transport.requests.size)

                h.allowed = true
                h.signal()
                runCurrent()

                assertEquals(1, h.transport.requests.size)
            }

        @Test
        fun `a wait blocks new attempts until it ends`() =
            uploaderTest { h ->
                h.events(1)
                h.transport.enqueue(HttpResult.HttpError(429, null, 60_000))
                h.start()
                h.signal()
                runCurrent()
                repeat(5) { h.signal() }
                advanceTimeBy(59_000)
                runCurrent()
                assertEquals(1, h.transport.requests.size)

                advanceTimeBy(7_000)
                runCurrent()

                assertEquals(2, h.transport.requests.size)
            }

        @Test
        fun `the network coming back ends a wait caused by the network`() =
            uploaderTest { h ->
                h.events(1)
                h.transport.enqueue(HttpResult.NetworkFailure(FailureKind.DNS, IOException("down")))
                h.transport.enqueue(HttpResult.NetworkFailure(FailureKind.DNS, IOException("down")))
                h.transport.enqueue(HttpResult.NetworkFailure(FailureKind.DNS, IOException("down")))
                h.start()
                h.signal()
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                val before = h.transport.requests.size

                h.network.setConnected(false)
                h.network.setConnected(true)
                runCurrent()

                assertEquals(before + 1, h.transport.requests.size)
            }
    }

    @Test
    fun `leftovers wait for a flush trigger, nothing is sent at startup`() =
        uploaderTest { h ->
            h.events(3)
            h.start()
            runCurrent()
            assertEquals(0, h.transport.requests.size)

            h.signal()
            runCurrent()

            assertEquals(2, h.transport.requests.size)
        }

    @Test
    fun `no two requests overlap under a burst of signals`() =
        uploaderTest { h ->
            h.events(8)
            h.start()
            repeat(50) { h.signal() }
            runCurrent()

            assertEquals(4, h.transport.requests.size)
            assertEquals(1, h.maxInFlight)
        }

    @Test
    fun `nothing starts before the writer has loaded the stores`() =
        runTest {
            val h = Harness(this)
            val gate = CompletableDeferred<Unit>()
            val uploader =
                Uploader(
                    h.eventStore,
                    h.transport,
                    h.code.batchUrl,
                    h.policy,
                    h.remote,
                    h.network,
                    h.flags,
                    h.deviceStore,
                    h.clock,
                    h.log,
                    null,
                    h.signals,
                    gate,
                    {},
                )
            h.events(1)
            uploader.start(h.scope, h.dispatcher)
            h.signal()
            runCurrent()
            assertEquals(0, h.transport.requests.size)

            gate.complete(Unit)
            runCurrent()

            assertEquals(1, h.transport.requests.size)
            h.scope.cancel()
        }

    @Nested
    inner class `remote configuration` {
        @Test
        fun `is fetched once per launch`() =
            uploaderTest { h ->
                h.start()
                h.signal()
                runCurrent()
                repeat(3) {
                    h.signal()
                    runCurrent()
                }

                assertEquals(1, h.remoteTransport.requests.size)
            }

        @Test
        fun `is fetched when the network first comes back, also with uploads gated`() =
            uploaderTest { h ->
                h.network.setConnected(false)
                h.allowed = false
                h.start()
                h.signal()
                runCurrent()
                assertEquals(0, h.remoteTransport.requests.size)

                h.network.setConnected(true)
                runCurrent()

                assertEquals(1, h.remoteTransport.requests.size)
            }
    }
}
