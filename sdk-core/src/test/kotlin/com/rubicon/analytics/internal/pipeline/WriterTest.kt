package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.BeforeSend
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.TrackEvent
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.identity.IdentityRef
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.RemoteConfigCache
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class WriterTest {
    private fun writerTest(
        configure: AnalyticsConfig.() -> Unit = {},
        block: suspend TestScope.(WriterHarness) -> Unit,
    ) = runTest {
        val harness = WriterHarness(this, configure)
        try {
            block(harness)
        } finally {
            harness.stop()
        }
    }

    @Nested
    inner class `order and startup` {
        @Test
        fun `events from one thread are stored in call order`() =
            writerTest({ flush.at = 100 }) { h ->
                h.writer.start()
                repeat(50) { h.track("E$it") }
                runCurrent()

                assertEquals((0 until 50).map { "E$it" }, h.names())
            }

        @Test
        fun `calls made before the writer started keep their order and identity`() =
            writerTest { h ->
                h.identity.identify("user-42", emptyMap())
                h.track()
                h.writer.start()
                runCurrent()

                val line = Json.parseToJsonElement(h.lines().single()).jsonObject
                assertEquals("user-42", line["user_id"]!!.jsonPrimitive.content)
            }

        @Test
        fun `the stores are loaded and written by the writer`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()
                h.identity.register("plan", "pro")
                runCurrent()

                assertTrue(h.keyValueStore.read(DeviceStore.FILE_NAME) is KeyValueStore.Read.Present)
                val person = (h.keyValueStore.read(PersonStore.FILE_NAME) as KeyValueStore.Read.Present).content
                assertTrue(person.contains("\"plan\""))
            }

        @Test
        fun `nothing is flushed at startup`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()

                assertEquals(0, h.signals())
            }
    }

    @Nested
    inner class `context cache` {
        @Test
        fun `is refreshed after the network monitor starts, before the first event and before each message`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()

                assertEquals(1, h.test.networkMonitor.starts)
                assertEquals(1, h.test.contextProvider.refreshes)

                h.track()
                h.track()
                runCurrent()

                assertEquals(3, h.test.contextProvider.refreshes)
            }

        @Test
        fun `an event captured before the cache was ready gets the values when it is written`() =
            writerTest { h ->
                h.test.contextProvider.pendingUntilRefresh = true
                h.test.contextProvider.callTimeValues = CallTimeValues(locale = "tr-TR", timezone = "Europe/Istanbul")
                val captured = h.test.contextProvider.callTimeValues(emptySet())
                assertTrue(captured.pending)

                h.track(callTime = captured)
                h.writer.start()
                runCurrent()

                val context = Json.parseToJsonElement(h.lines().last()).jsonObject["context"]!!.jsonObject
                assertEquals("tr-TR", context["locale"]!!.jsonPrimitive.content)
                assertEquals("Europe/Istanbul", context["timezone"]!!.jsonPrimitive.content)
            }

        @Test
        fun `values captured from a ready cache are kept, not replaced at write time`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()
                h.test.contextProvider.callTimeValues = CallTimeValues(locale = "de-DE")

                h.track(callTime = CallTimeValues(locale = "tr-TR"))
                runCurrent()

                val context = Json.parseToJsonElement(h.lines().last()).jsonObject["context"]!!.jsonObject
                assertEquals("tr-TR", context["locale"]!!.jsonPrimitive.content)
            }
    }

    @Nested
    inner class `the observer and drops` {
        @Test
        fun `sees Queued for exactly the stored events`() =
            writerTest { h ->
                h.writer.start()
                h.track("A")
                runCurrent()
                h.eventStore.failAppends = true
                h.track("B")
                runCurrent()

                val queued = h.updates.filterIsInstance<EventUpdate.Queued>().map { it.event.messageId }
                assertEquals(listOf("m-1"), queued)
            }

        @Test
        fun `a pipeline drop is counted and reported`() =
            writerTest({ beforeSend = listOf(BeforeSend { null }) }) { h ->
                h.writer.start()
                h.track()
                runCurrent()

                val dropped = h.updates.filterIsInstance<EventUpdate.Dropped>().single()
                assertEquals(DropReason.BEFORE_SEND, dropped.reason)
                assertEquals(mapOf("before_send" to 1), h.deviceStore.state.diagnostics.dropped)
                assertTrue(h.lines().isEmpty())
            }

        @Test
        fun `a full channel drops as backpressure without blocking`() =
            writerTest { h ->
                // Not started: nothing drains the channel.
                val accepted = (1..1001).map { h.track() }

                assertEquals(1000, accepted.count { it })
                assertFalse(accepted.last())
                assertEquals(DropReason.BACKPRESSURE, (h.updates.single() as EventUpdate.Dropped).reason)
                h.writer.start()
                runCurrent()
                // The count reaches SDK Diagnostics at the first flush (Logging 5).
                val report = h.lines().first { h.field(it, "event") == Diagnostics.EVENT_NAME }
                assertTrue(report.contains("\"backpressure\":1"), report)
            }

        @Test
        fun `nothing is created once collecting is off`() =
            writerTest { h ->
                h.writer.start()
                h.track("Before")
                runCurrent()
                h.flags.stopCollecting()
                h.track("After")
                runCurrent()

                assertEquals(listOf("Before"), h.names())
            }

        @Test
        fun `a remote kill switch saved earlier stops collection from the start`() =
            writerTest { h ->
                h.deviceStore.load()
                h.deviceStore.updateRemoteConfig(RemoteConfigCache(null, mapOf("enabled" to false)))
                h.deviceStore.persist()
                h.writer.start()
                h.track()
                runCurrent()

                assertTrue(h.lines().isEmpty())
            }
    }

    @Nested
    inner class `flush triggers` {
        @Test
        fun `the count fires once at flush_at events`() =
            writerTest { h ->
                h.writer.start()
                repeat(4) { h.track() }
                runCurrent()
                assertEquals(0, h.signals())

                h.track()
                runCurrent()
                assertEquals(1, h.signals())
                assertEquals(1, h.eventStore.closedSegments().size)

                repeat(4) { h.track() }
                runCurrent()
                assertEquals(0, h.signals())
            }

        @Test
        fun `events left by a previous process count, once`() =
            writerTest { h ->
                repeat(7) { h.eventStore.append("""{"type":"track","message_id":"old$it","timestamp":1}""") }
                h.writer.start()
                runCurrent()
                assertEquals(0, h.signals())

                h.track()
                runCurrent()
                assertEquals(1, h.signals())
                h.track()
                runCurrent()
                assertEquals(0, h.signals())
            }

        @Test
        fun `the interval runs from the first pending event and only while events are pending`() =
            writerTest { h ->
                h.writer.start()
                advanceTimeBy(600_000)
                assertEquals(0, h.signals())

                h.track()
                advanceTimeBy(59_000)
                assertEquals(0, h.signals())
                h.track()
                advanceTimeBy(1_001)
                assertEquals(1, h.signals())

                advanceTimeBy(600_000)
                assertEquals(0, h.signals())
            }

        @Test
        fun `the interval starts at process start when events were left over`() =
            writerTest { h ->
                h.eventStore.append("""{"type":"track","message_id":"old","timestamp":1}""")
                h.writer.start()
                advanceTimeBy(60_001)

                assertEquals(1, h.signals())
            }

        @Test
        fun `any flush cancels the interval`() =
            writerTest { h ->
                h.writer.start()
                h.track()
                advanceTimeBy(30_000)
                h.writer.flush()
                advanceTimeBy(1)
                assertEquals(1, h.signals())

                advanceTimeBy(40_000)
                assertEquals(0, h.signals())
            }

        @Test
        fun `backgrounding flushes and syncs`() =
            writerTest { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                h.track()
                runCurrent()

                h.test.lifecycleSource.moveToBackground()
                runCurrent()

                assertEquals(1, h.signals())
                assertTrue(h.eventStore.syncCount >= 1)
            }

        @Test
        fun `the network coming back flushes only while events are pending`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()
                h.test.networkMonitor.setConnected(false)
                h.test.networkMonitor.setConnected(true)
                runCurrent()
                assertEquals(0, h.signals())

                h.track()
                runCurrent()
                h.test.networkMonitor.setConnected(false)
                h.test.networkMonitor.setConnected(true)
                runCurrent()
                assertEquals(1, h.signals())
            }

        @Test
        fun `an immediate event flushes after it is stored, matched exactly`() =
            writerTest({ flush.immediateEvents = setOf("Purchase Completed") }) { h ->
                h.writer.start()
                h.track("purchase completed")
                runCurrent()
                assertEquals(0, h.signals())

                h.track("Purchase Completed")
                runCurrent()

                assertEquals(1, h.signals())
                assertEquals(
                    listOf(
                        "purchase completed",
                        "Purchase Completed",
                    ),
                    h.eventStore.segmentLines(h.eventStore.closedSegments().single()).map {
                        Json
                            .parseToJsonElement(it)
                            .jsonObject["event"]!!
                            .jsonPrimitive.content
                    },
                )
                assertTrue(h.logger.messages.any { it.contains("Immediate event \"Purchase Completed\"") })
            }

        @Test
        fun `the name that counts is the final one, after the hooks`() =
            writerTest({
                flush.immediateEvents = setOf("Order Placed")
                beforeSend = listOf(BeforeSend { (it as TrackEvent).copy(name = "Order Placed") })
            }) { h ->
                h.writer.start()
                h.track("Checkout")
                runCurrent()

                assertEquals(1, h.signals())
            }

        @Test
        fun `flush includes every earlier event`() =
            writerTest({ flush.at = 100 }) { h ->
                h.writer.start()
                repeat(3) { h.track("E$it") }
                h.writer.flush()
                h.track("After")
                runCurrent()

                assertEquals(3, h.eventStore.segmentLines(h.eventStore.closedSegments().single()).size)
                assertEquals(1, h.eventStore.openSegment.size)
            }

        @Test
        fun `many flushes during an upload collapse into one signal`() =
            writerTest { h ->
                h.writer.start()
                repeat(10) { h.writer.flush() }
                runCurrent()

                assertEquals(1, h.signals())
            }
    }

    @Nested
    inner class `session events` {
        @Test
        fun `foreground, the first event and a long background produce the session events in order`() =
            writerTest { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                h.track("Opened")
                runCurrent()
                h.test.lifecycleSource.moveToBackground()
                runCurrent()
                h.test.clock.advanceBy(600_000)
                h.test.elapsedClock.advanceBy(600_000)
                h.test.lifecycleSource.moveToForeground()
                runCurrent()

                assertEquals(listOf("Session Started", "Opened", "Session Ended"), h.names())
            }

        @Test
        fun `a reset ends the session as the previous user, after earlier events`() =
            writerTest { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()
                h.identity.identify("user-42", emptyMap())
                h.track("Before")
                h.identity.reset()
                h.track("After")
                runCurrent()

                val lines = h.lines().map { Json.parseToJsonElement(it).jsonObject }
                assertEquals(
                    listOf("Session Started", "Before", "Session Ended", "Session Started", "After"),
                    lines.map {
                        it["event"]!!.jsonPrimitive.content
                    },
                )
                assertEquals("user-42", lines[2]["user_id"]!!.jsonPrimitive.content)
            }
    }

    @Test
    fun `an unexpected failure is logged and the writer goes on`() =
        writerTest { h ->
            h.writer.start()
            runCurrent()
            // An identity that was never resolved cannot happen in the SDK; here it makes the step fail.
            h.writer.enqueue(
                CapturedEvent(CapturedEvent.Type.TRACK, "Broken", emptyMap(), EventSource.APP, "x", 0, IdentityRef(), CallTimeValues.NONE),
            )
            h.track("Fine")
            runCurrent()

            assertEquals(listOf("Fine"), h.names())
            assertTrue(h.logger.lines.any { it.level == LogLevel.ERROR && it.message.startsWith("WORKER_FAILED") })
        }
}
