package com.rubicon.analytics.internal.privacy

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.Consent
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.identity.IdentifyTicket
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.pipeline.WriterHarness
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.RemoteConfigCache
import com.rubicon.analytics.internal.store.StoredConsent
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PrivacyTest {
    private fun privacyTest(
        configure: AnalyticsConfig.() -> Unit = {},
        stored: StoredConsent? = null,
        block: suspend TestScope.(WriterHarness) -> Unit,
    ) = runTest {
        val kv = InMemoryKeyValueStore()
        if (stored != null) {
            DeviceStore(kv, Log(LogLevel.OFF, null, RecordingLogger()), FakeClock()) { "device-0" }.apply {
                load()
                update { it.copy(consent = stored) }
                persist()
            }
        }
        val h = WriterHarness(this, configure, keyValueStore = kv)
        try {
            block(h)
        } finally {
            h.stop()
        }
    }

    private fun WriterHarness.storedConsent(): StoredConsent? {
        writer.requestPersist()
        return deviceStore.state.consent
    }

    @Nested
    inner class `the starting state` {
        @Test
        fun `GRANTED by default collects and is not opted out`() =
            privacyTest { h ->
                h.writer.start()
                runCurrent()

                assertFalse(h.consent.isOptedOut())
                assertEquals(ConsentState.GRANTED, h.consent.state)
            }

        @Test
        fun `PENDING is opted out, meaning nothing is sent now`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                runCurrent()

                assertTrue(h.consent.isOptedOut())
                assertEquals(ConsentState.PENDING, h.consent.state)
            }

        @Test
        fun `a stored choice beats the default`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }, stored = StoredConsent.GRANTED) { h ->
                h.writer.start()
                runCurrent()

                assertEquals(ConsentState.GRANTED, h.consent.state)
            }

        @Test
        fun `a stored opt-out survives a restart`() =
            privacyTest(stored = StoredConsent.DENIED) { h ->
                h.writer.start()
                runCurrent()

                assertEquals(ConsentState.DENIED, h.consent.state)
            }

        @Test
        fun `without persistConsent the stored value is neither read nor written`() =
            privacyTest({ privacy.persistConsent = false }, stored = StoredConsent.DENIED) { h ->
                h.writer.start()
                runCurrent()
                assertEquals(ConsentState.GRANTED, h.consent.state)

                h.consent.optIn()
                runCurrent()

                assertEquals(StoredConsent.DENIED, h.storedConsent())
            }

        @Test
        fun `isOptedOut before the writer has loaded reads device json once`() =
            privacyTest(stored = StoredConsent.DENIED) { h ->
                assertTrue(h.consent.isOptedOut())
                h.writer.start()
                runCurrent()
                assertTrue(h.consent.isOptedOut())
            }

        @Test
        fun `calls before the stored choice is known apply in order once it is`() =
            privacyTest(stored = StoredConsent.DENIED) { h ->
                h.consent.optIn()
                h.consent.optOut()
                h.consent.optIn()
                h.writer.start()
                runCurrent()

                assertEquals(ConsentState.GRANTED, h.consent.state)
                assertEquals(StoredConsent.GRANTED, h.storedConsent())
            }
    }

    @Nested
    inner class `opting out` {
        @Test
        fun `startup events of an opted-out user never reach disk and are not counted`() =
            privacyTest(stored = StoredConsent.DENIED) { h ->
                h.track("Early")
                h.writer.start()
                runCurrent()

                assertTrue(h.lines().isEmpty())
                assertEquals(emptyMap<String, Int>(), h.deviceStore.state.diagnostics.dropped)
            }

        @Test
        fun `nothing new is created, and events queued before still go`() =
            privacyTest { h ->
                h.writer.start()
                h.track("Before")
                runCurrent()

                h.consent.optOut()
                h.track("After")
                h.writer.flush()
                runCurrent()

                assertEquals(listOf("Before"), h.names())
                assertEquals(1, h.signals())
                assertEquals(StoredConsent.DENIED, h.storedConsent())
            }

        @Test
        fun `the open session ends without Session Ended, and no session events follow`() =
            privacyTest({ autocapture.enabled = setOf(Autocapture.SESSIONS) }) { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                h.track("Opened")
                runCurrent()

                h.consent.optOut()
                h.test.lifecycleSource.moveToBackground()
                h.test.clock.advanceBy(3_600_000)
                h.test.elapsedClock.advanceBy(3_600_000)
                h.test.lifecycleSource.moveToForeground()
                h.track("Ignored")
                runCurrent()

                assertEquals(listOf("Session Started", "Opened"), h.names())
                assertNull(h.personStore.state.session)
            }

        @Test
        fun `identify, register and reset still update local state, without events`() =
            privacyTest { h ->
                h.writer.start()
                runCurrent()
                h.consent.optOut()

                val ticket = h.identity.identify("user-42", mapOf("plan" to "pro"))
                h.identity.register("theme", "dark")

                assertSame(IdentifyTicket.Decision.NoEvent, ticket!!.decision)
                assertEquals("user-42", h.personStore.state.userId)
                assertEquals(mapOf("theme" to "dark"), h.personStore.state.superProperties)
            }

        @Test
        fun `opting in produces no event and the next event starts a new session`() =
            privacyTest({ autocapture.enabled = setOf(Autocapture.SESSIONS) }) { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()
                h.consent.optOut()
                h.consent.optIn()
                h.track("Back")
                runCurrent()

                assertEquals(listOf("Session Started", "Back"), h.names())
                assertTrue(h.logger.lines.any { it.level == LogLevel.INFO && it.message.contains("opted in") })
            }
    }

    @Nested
    inner class `pending consent` {
        @Test
        fun `events are written but the uploader gate stays closed`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                h.track("Waiting")
                runCurrent()

                assertEquals(listOf("Waiting"), h.names())
                assertEquals(ConsentState.PENDING, h.consent.state)
            }

        @Test
        fun `only pending closes the upload gate`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                runCurrent()
                assertFalse(h.consent.allowsUploads)

                h.consent.optIn()
                assertTrue(h.consent.allowsUploads)
                h.consent.optOut()
                assertTrue(h.consent.allowsUploads)
            }

        @Test
        fun `optIn uploads the waiting events`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                h.track("Waiting")
                runCurrent()

                h.consent.optIn()
                runCurrent()

                assertEquals(1, h.signals())
                assertEquals(1, h.eventStore.closedSegments().size)
            }

        @Test
        fun `optOut deletes the waiting events without counting them`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                repeat(3) { h.track("Waiting") }
                runCurrent()

                h.consent.optOut()
                runCurrent()

                assertTrue(h.lines().isEmpty())
                assertEquals(0, h.eventStore.pendingCount)
                assertEquals(emptyMap<String, Int>(), h.deviceStore.state.diagnostics.dropped)
                assertTrue(h.updates.none { it is com.rubicon.analytics.EventUpdate.Dropped })
            }

        @Test
        fun `with no stored decision, pending continues after a restart`() =
            privacyTest({ privacy.defaultConsent = Consent.PENDING }) { h ->
                h.writer.start()
                runCurrent()

                assertNull(h.deviceStore.state.consent)
                assertEquals(ConsentState.PENDING, h.consent.state)
            }
    }

    @Nested
    inner class `clearing local data` {
        @Test
        fun `deletes events and the person, renews the device id, keeps consent, version and remote config`() =
            privacyTest { h ->
                h.writer.start()
                runCurrent()
                h.identity.identify("user-42", emptyMap())
                h.identity.register("plan", "pro")
                h.consent.optIn()
                h.deviceStore.update { it.copy(lastVersion = "1.4.0", lastBuild = "140") }
                h.deviceStore.updateRemoteConfig(RemoteConfigCache("\"e\"", mapOf("flush_at" to 50)))
                h.deviceStore.countDropped(DropReason.EXPIRED, 2)
                repeat(3) { h.track("Before") }
                runCurrent()
                val oldDeviceId = h.identity.deviceId

                h.identity.clearLocalData(h.ids.next())
                h.writer.clearLocalData()
                h.track("After")
                runCurrent()

                val device = h.deviceStore.state
                assertEquals(listOf("After"), h.names())
                assertNotEquals(oldDeviceId, device.deviceId)
                assertEquals(device.deviceId, h.field(h.lines().single(), "device_id"))
                assertNull(h.field(h.lines().single(), "user_id"))
                assertNull(h.personStore.state.userId)
                assertEquals(emptyMap<String, Any?>(), h.personStore.state.superProperties)
                assertEquals(StoredConsent.GRANTED, device.consent)
                assertEquals("1.4.0", device.lastVersion)
                assertEquals("140", device.lastBuild)
                assertEquals(RemoteConfigCache("\"e\"", mapOf("flush_at" to 50)), h.deviceStore.remoteConfig)
                assertEquals(emptyMap<String, Int>(), device.diagnostics.dropped)
            }

        @Test
        fun `produces no Session Ended and counts nothing`() =
            privacyTest { h ->
                h.writer.start()
                h.test.lifecycleSource.moveToForeground()
                h.track("Opened")
                runCurrent()

                h.identity.clearLocalData(h.ids.next())
                h.writer.clearLocalData()
                runCurrent()

                assertTrue(h.lines().isEmpty())
                assertTrue(h.updates.none { it is com.rubicon.analytics.EventUpdate.Dropped })
                assertEquals(emptyMap<String, Int>(), h.deviceStore.state.diagnostics.dropped)
            }

        @Test
        fun `the files are written by the writer`() =
            privacyTest { h ->
                h.writer.start()
                runCurrent()
                val before = h.identity.deviceId

                h.identity.clearLocalData(h.ids.next())
                runCurrent()

                val stored = (h.keyValueStore.read(DeviceStore.FILE_NAME) as KeyValueStore.Read.Present).content
                assertFalse(stored.contains(before))
                assertTrue(stored.contains(h.deviceStore.state.deviceId))
            }
    }
}
