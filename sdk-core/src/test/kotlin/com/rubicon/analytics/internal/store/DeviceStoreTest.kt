package com.rubicon.analytics.internal.store

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeviceStoreTest {
    private val support = StoreTestSupport()
    private val memory = InMemoryKeyValueStore()
    private val clock = FakeClock(1_759_147_000_000L)

    private fun store(keyValueStore: KeyValueStore = memory) = DeviceStore(keyValueStore, support.log, clock, support.newDeviceId)

    private val full =
        DeviceState(
            deviceId = "0191d0c2-1a2b-7c3d-9e4f-5a6b7c8d9e0f",
            consent = StoredConsent.DENIED,
            lastVersion = "1.4.0",
            lastBuild = "140",
            retry = RetryState(1_759_150_860_000L, PauseKind.THROTTLE, 3, "timeout", mapOf("0000000041" to 6)),
            diagnostics = DiagnosticsState(1_759_100_000_000L, 1_759_000_000_000L, mapOf("queue_full" to 12), mapOf("HOST_BLOCKED" to 2)),
        )

    @Nested
    inner class `the files` {
        @Test
        fun `device json has exactly the decided keys`() {
            store().apply { load() }.apply {
                update { full }
                persist()
            }

            val stored = support.stored(memory, DeviceStore.FILE_NAME)

            assertEquals(setOf("v", "device_id", "consent", "last_version", "last_build", "retry", "diagnostics"), stored.keys)
            assertEquals(setOf("paused_until", "pause_kind", "step", "last_failure", "batches"), stored["retry"]!!.jsonObject.keys)
            assertEquals(setOf("period_start", "last_sent_at", "dropped", "issues"), stored["diagnostics"]!!.jsonObject.keys)
        }

        @Test
        fun `remote config json has exactly the decided keys`() {
            store().apply { load() }.apply {
                updateRemoteConfig(RemoteConfigCache("\"abc\"", mapOf("flush_at" to 10)))
                persist()
            }

            assertEquals(setOf("v", "etag", "values"), support.stored(memory, DeviceStore.REMOTE_CONFIG_FILE_NAME).keys)
        }

        @Test
        fun `the state and the cache read back as written`() {
            val cache = RemoteConfigCache("\"abc\"", mapOf("enabled" to true, "flush_at" to 10))
            store().apply { load() }.apply {
                update { full }
                updateRemoteConfig(cache)
                persist()
            }

            val reloaded = store().apply { load() }

            assertEquals(full, reloaded.state)
            assertEquals(cache, reloaded.remoteConfig)
        }

        @Test
        fun `consent is null, granted or denied`() {
            val store = store().apply { load() }
            assertNull(store.state.consent)

            store.update { it.copy(consent = StoredConsent.GRANTED) }
            store.persist()

            assertEquals("\"granted\"", support.stored(memory, DeviceStore.FILE_NAME)["consent"].toString())
        }

        @Test
        fun `a deleted remote config cache removes the file`() {
            val store = store().apply { load() }
            store.updateRemoteConfig(RemoteConfigCache(null, emptyMap()))
            store.persist()

            store.updateRemoteConfig(null)
            store.persist()

            assertEquals(KeyValueStore.Read.Missing, memory.read(DeviceStore.REMOTE_CONFIG_FILE_NAME))
        }
    }

    @Nested
    inner class `missing and unreadable` {
        @Test
        fun `a missing device file gets a new id, written at once`() {
            val store = store().apply { load() }

            assertEquals("device-1", store.state.deviceId)
            assertEquals("device-1", store().apply { load() }.state.deviceId)
            assertTrue(store.isDeviceFileWritable)
        }

        @Test
        fun `a missing remote config writes nothing`() {
            val store = store().apply { load() }

            assertNull(store.remoteConfig)
            assertEquals(KeyValueStore.Read.Missing, memory.read(DeviceStore.REMOTE_CONFIG_FILE_NAME))
        }

        @Test
        fun `an unreadable device file uses a temporary id and is never written`() {
            memory.write(DeviceStore.FILE_NAME, "{}")
            memory.markUnreadable(DeviceStore.FILE_NAME)
            val writes = memory.writeCount

            val store = store().apply { load() }
            store.update { it.copy(consent = StoredConsent.GRANTED) }
            store.persist()

            assertEquals("device-1", store.state.deviceId)
            assertFalse(store.isDeviceFileWritable)
            assertEquals(writes, memory.writeCount)
            assertTrue(support.warned(Code.STORE_UNREADABLE))
        }

        @Test
        fun `the next process reads the file again`() {
            memory.write(DeviceStore.FILE_NAME, "{}")
            memory.markUnreadable(DeviceStore.FILE_NAME)
            store().load()

            memory.write(DeviceStore.FILE_NAME, """{"v":1,"device_id":"kept"}""")

            assertEquals("kept", store().apply { load() }.state.deviceId)
        }
    }

    @Nested
    inner class `files left byte-identical` {
        @TempDir
        lateinit var root: File

        private fun assertUntouched(
            name: String,
            content: String,
        ) {
            File(root, name).apply { parentFile.mkdirs() }.writeText(content)
            val before = File(root, name).readBytes()

            val store = store(FileKeyValueStore(root)).apply { load() }
            store.update { it.copy(lastBuild = "141") }
            store.updateRemoteConfig(RemoteConfigCache("\"new\"", emptyMap()))
            store.persist()

            assertArrayEquals(before, File(root, name).readBytes())
            assertTrue(support.warned(Code.STORE_UNREADABLE))
        }

        @Test
        fun `corrupt device json`() = assertUntouched(DeviceStore.FILE_NAME, "not json")

        @Test
        fun `device json of a newer version`() = assertUntouched(DeviceStore.FILE_NAME, """{"v":3,"device_id":"0191"}""")

        @Test
        fun `device json without its id`() = assertUntouched(DeviceStore.FILE_NAME, """{"v":1,"consent":"granted"}""")

        @Test
        fun `device json with an unknown consent value`() =
            assertUntouched(DeviceStore.FILE_NAME, """{"v":1,"device_id":"x","consent":"maybe"}""")

        @Test
        fun `device json with a counter that is not a number`() =
            assertUntouched(DeviceStore.FILE_NAME, """{"v":1,"device_id":"x","diagnostics":{"dropped":{"corrupt":"3"}}}""")

        @Test
        fun `remote config of a newer version`() = assertUntouched(DeviceStore.REMOTE_CONFIG_FILE_NAME, """{"v":2,"values":{}}""")
    }

    @Nested
    inner class `diagnostics counters` {
        @Test
        fun `drops and issues add up and start the period`() {
            val store = store().apply { load() }

            store.countDropped(DropReason.QUEUE_FULL, 3)
            store.countDropped(DropReason.QUEUE_FULL)
            store.issueCounter.increment(Code.HOST_BLOCKED)
            clock.advanceBy(5_000)
            store.issueCounter.increment(Code.HOST_BLOCKED)

            val diagnostics = store.state.diagnostics
            assertEquals(mapOf("queue_full" to 4), diagnostics.dropped)
            assertEquals(mapOf("HOST_BLOCKED" to 2), diagnostics.issues)
            assertEquals(1_759_147_000_000L, diagnostics.periodStart)
        }

        @Test
        fun `counts made before load are kept`() {
            memory.write(DeviceStore.FILE_NAME, "{}")
            memory.markUnreadable(DeviceStore.FILE_NAME)
            lateinit var store: DeviceStore
            // The log of the instance counts through the store, as in production.
            val log = Log(LogLevel.WARN, null, support.logger) { store.issueCounter.increment(it) }
            store = DeviceStore(memory, log, clock, support.newDeviceId)

            store.countDropped(DropReason.BACKPRESSURE, 2)
            store.load()

            assertEquals(mapOf("backpressure" to 2), store.state.diagnostics.dropped)
            assertEquals(mapOf("STORE_UNREADABLE" to 1), store.state.diagnostics.issues)
        }

        @Test
        fun `counts are persisted with the next write`() {
            val store = store().apply { load() }

            store.countDropped(DropReason.CORRUPT, 2)
            store.persist()

            assertEquals(
                mapOf("corrupt" to 2),
                store()
                    .apply { load() }
                    .state.diagnostics.dropped,
            )
        }

        @Test
        fun `a count never wraps around`() {
            val store = store().apply { load() }
            store.update { it.copy(diagnostics = it.diagnostics.copy(dropped = mapOf("expired" to Int.MAX_VALUE - 1))) }

            store.countDropped(DropReason.EXPIRED, 5)

            assertEquals(Int.MAX_VALUE, store.state.diagnostics.dropped["expired"])
        }
    }

    @Test
    fun `many updates in a row produce one write`() {
        val store = store().apply { load() }
        val writes = memory.writeCount

        repeat(100) { store.countDropped(DropReason.QUEUE_FULL) }
        store.update { it.copy(lastVersion = "1.5.0") }
        store.persist()

        assertEquals(writes + 1, memory.writeCount)
    }
}
