package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.BeforeSend
import com.rubicon.analytics.Consent
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.identity.IdentityRef
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.session.SessionManager
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.DiagnosticsState
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.RemoteConfigCache
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeContextProvider
import com.rubicon.analytics.testing.FakeElapsedClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

class DiagnosticsTest {
    private val hour = Diagnostics.INTERVAL_MS

    private fun counted(lastSentAt: Long? = null) = DiagnosticsState(NOW - hour, lastSentAt, mapOf("expired" to 2), emptyMap())

    @Nested
    inner class `when it is due` {
        @Test
        fun `never when nothing was counted`() {
            assertFalse(Diagnostics.isDue(DiagnosticsState.EMPTY, NOW))
        }

        @Test
        fun `at once the first time, then once an hour`() {
            assertTrue(Diagnostics.isDue(counted(), NOW))
            assertFalse(Diagnostics.isDue(counted(lastSentAt = NOW - hour + 1), NOW))
            assertTrue(Diagnostics.isDue(counted(lastSentAt = NOW - hour), NOW))
        }

        @Test
        fun `a last time in the future counts as now`() {
            assertFalse(Diagnostics.isDue(counted(lastSentAt = NOW + 10 * hour), NOW))
        }
    }

    @Nested
    inner class `the properties` {
        @Test
        fun `match the golden file as a whole event`() {
            val state =
                DiagnosticsState(
                    periodStart = NOW - 4 * hour,
                    lastSentAt = null,
                    dropped = mapOf("queue_full" to 1200, "expired" to 37, "before_send" to 3),
                    issues = mapOf("STORAGE_WRITE_FAILED" to 3, "HOST_BLOCKED" to 12),
                )
            val log = Log(LogLevel.OFF, null, RecordingLogger())
            val person = PersonStore(InMemoryKeyValueStore(), log).apply { load() }
            val sessions = SessionManager(person, FakeClock(NOW), FakeElapsedClock(), 300_000, true, { "s" }, log) {}
            val provider = FakeContextProvider(StaticValues(osName = "Android", osVersion = "16", osApiLevel = 36))
            val pipeline = EventPipeline(listOf(BeforeSend { null }), emptySet(), false, provider, sessions, { "unused" }, log)
            val identity = IdentityRef().apply { value = IdentitySnapshot(null, "0191d0c2-1a2b-7c3d-9e4f-5a6b7c8d9e0f", emptyMap()) }
            val event =
                CapturedEvent(
                    CapturedEvent.Type.TRACK,
                    Diagnostics.EVENT_NAME,
                    Diagnostics.properties(state, NOW),
                    EventSource.AUTO,
                    "0192e4a2-0000-7000-8000-000000000001",
                    NOW,
                    identity,
                    CallTimeValues.NONE,
                    skipsHooks = true,
                )

            val line = (pipeline.process(event).single() as PipelineResult.Accepted).line

            assertEquals(
                Json.parseToJsonElement(File("src/test/resources/golden/sdk-diagnostics.json").readText()),
                Json.parseToJsonElement(line),
            )
        }

        @Test
        fun `carry an empty issues object and a zero total when only one side counted`() {
            val properties = Diagnostics.properties(DiagnosticsState(null, null, emptyMap(), mapOf("TLS_FAILED" to 1)), NOW)

            assertEquals(mapOf("total" to 0L), properties["dropped"])
            assertEquals(mapOf("TLS_FAILED" to 1), properties["issues"])
            assertEquals("2025-09-29T12:00:00.000Z", properties["period_start"])
        }
    }

    @Nested
    inner class `in the writer` {
        private fun diagnosticsTest(
            configure: AnalyticsConfig.() -> Unit = {},
            keyValueStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
            block: suspend TestScope.(WriterHarness) -> Unit,
        ) = runTest {
            val h = WriterHarness(this, configure, keyValueStore = keyValueStore)
            try {
                block(h)
            } finally {
                h.stop()
            }
        }

        private fun WriterHarness.diagnosticsLines() = lines().filter { field(it, "event") == Diagnostics.EVENT_NAME }

        @Test
        fun `a silent device sends nothing`() =
            diagnosticsTest { h ->
                h.writer.start()
                h.track()
                h.writer.flush()
                runCurrent()

                assertTrue(h.diagnosticsLines().isEmpty())
            }

        @Test
        fun `comes right before a flush and resets the counters once written`() =
            diagnosticsTest { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED, 2)
                h.log.warn(Code.HOST_BLOCKED) { "blocked" }
                h.track("Before")
                h.writer.flush()
                runCurrent()

                assertEquals(listOf("Before", Diagnostics.EVENT_NAME), h.names())
                assertEquals(2, h.eventStore.segmentLines(h.eventStore.closedSegments().single()).size)
                val diagnostics = h.deviceStore.state.diagnostics
                assertEquals(emptyMap<String, Int>(), diagnostics.dropped)
                assertEquals(emptyMap<String, Int>(), diagnostics.issues)
                assertNull(diagnostics.periodStart)
                assertEquals(h.test.clock.currentTimeMillis(), diagnostics.lastSentAt)
            }

        @Test
        fun `at most once an hour, also across restarts`() {
            val kv = InMemoryKeyValueStore()
            diagnosticsTest(keyValueStore = kv) { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()

                assertEquals(1, h.diagnosticsLines().size)
                h.writer.requestPersist()
                runCurrent()
            }
            diagnosticsTest(keyValueStore = kv) { h ->
                h.writer.start()
                h.writer.flush()
                runCurrent()
                assertTrue(h.diagnosticsLines().isEmpty())

                h.test.clock.advanceBy(hour)
                h.writer.flush()
                runCurrent()
                assertEquals(1, h.diagnosticsLines().size)
            }
        }

        @Test
        fun `counters stay when the event could not be written`() =
            diagnosticsTest { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED, 2)
                h.eventStore.failAppends = true
                h.writer.flush()
                runCurrent()

                assertEquals(2, h.deviceStore.state.diagnostics.dropped["expired"])
                assertNull(h.deviceStore.state.diagnostics.lastSentAt)
            }

        @Test
        fun `counts made while it is written are kept for the next period`() {
            val store =
                DeviceStore(InMemoryKeyValueStore(), Log(LogLevel.OFF, null, RecordingLogger()), FakeClock(NOW)) { "device-1" }.apply {
                    load()
                    countDropped(DropReason.EXPIRED, 2)
                }
            val reported = store.state.diagnostics
            store.countDropped(DropReason.EXPIRED, 3)

            store.diagnosticsReported(reported, NOW)

            assertEquals(mapOf("expired" to 3), store.state.diagnostics.dropped)
            assertEquals(NOW, store.state.diagnostics.periodStart)
        }

        @Test
        fun `opted out keeps counting and reports after optIn`() =
            diagnosticsTest { h ->
                h.writer.start()
                runCurrent()
                h.consent.optOut()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()
                assertTrue(h.diagnosticsLines().isEmpty())
                assertEquals(1, h.deviceStore.state.diagnostics.dropped["expired"])

                h.consent.optIn()
                h.writer.flush()
                runCurrent()

                assertEquals(1, h.diagnosticsLines().size)
            }

        @Test
        fun `never while consent is pending`() =
            diagnosticsTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()

                assertTrue(h.diagnosticsLines().isEmpty())
            }

        @Test
        fun `never when switched off in code or by a remote rate of zero`() {
            diagnosticsTest({ logging.sendDiagnostics = false }) { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()
                assertTrue(h.diagnosticsLines().isEmpty())
            }
            diagnosticsTest { h ->
                h.deviceStore.load()
                h.deviceStore.updateRemoteConfig(RemoteConfigCache(null, mapOf("diagnostics_sample_rate" to 0)))
                h.deviceStore.persist()
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()
                assertTrue(h.diagnosticsLines().isEmpty())
            }
        }

        @Test
        fun `never with the kill switch on`() =
            diagnosticsTest { h ->
                h.writer.start()
                runCurrent()
                h.flags.stopCollecting()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()

                assertTrue(h.diagnosticsLines().isEmpty())
            }

        @Test
        fun `never passes through the hooks`() =
            diagnosticsTest({ beforeSend = listOf(BeforeSend { null }) }) { h ->
                h.writer.start()
                runCurrent()
                h.deviceStore.countDropped(DropReason.EXPIRED)
                h.writer.flush()
                runCurrent()

                assertEquals(1, h.diagnosticsLines().size)
            }
    }

    private companion object {
        // 2025-09-29T12:00:00.000Z
        const val NOW = 1_759_147_200_000L
    }
}
