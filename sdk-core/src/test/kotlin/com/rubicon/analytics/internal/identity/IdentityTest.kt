package com.rubicon.analytics.internal.identity

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.identity.IdentifyTicket.Decision
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonState
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.SessionState
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdentityTest {
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val keyValueStore = InMemoryKeyValueStore()
    private var optedOut = false
    private var persistRequests = 0
    private val cleared = mutableListOf<IdentitySnapshot>()
    private var ids = 0

    private fun stores(kv: KeyValueStore = keyValueStore) = PersonStore(kv, log) to DeviceStore(kv, log, FakeClock()) { "device-${++ids}" }

    private fun identity(
        debug: Boolean = false,
        kv: KeyValueStore = keyValueStore,
        load: Boolean = true,
    ): Triple<Identity, PersonStore, DeviceStore> {
        val (person, device) = stores(kv)
        val identity = Identity(person, device, log, debug, { !optedOut }, { persistRequests++ }) { cleared += it }
        if (load) loadAll(identity, person, device)
        return Triple(identity, person, device)
    }

    // What the writer does first (Privacy 2), then the identity's replay.
    private fun loadAll(
        identity: Identity,
        person: PersonStore,
        device: DeviceStore,
    ) {
        device.load()
        person.load()
        identity.load()
    }

    private fun Identity.userId() = snapshot().value!!.userId

    private fun IdentifyTicket?.sent() = (this!!.decision as Decision.SendEvent)

    private fun warned(code: Code) = logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("${code.name}:") }

    @Nested
    inner class `identify rules` {
        @Test
        fun `anonymous - the id is set and an identify event is sent`() {
            val (identity, person) = identity()

            val event = identity.identify("user-42", mapOf("plan" to "pro")).sent()

            assertEquals("user-42", person.state.userId)
            assertEquals(mapOf("plan" to "pro"), event.traits)
            assertEquals("user-42", event.identity.userId)
            assertEquals("device-1", event.identity.deviceId)
        }

        @Test
        fun `anonymous without traits still sends the identify event`() {
            val (identity) = identity()

            assertEquals(emptyMap<String, Any?>(), identity.identify("user-42", emptyMap()).sent().traits)
        }

        @Test
        fun `the same id with traits sends an identify event`() {
            val (identity) = identity()
            identity.identify("user-42", emptyMap())

            assertEquals(mapOf("plan" to "free"), identity.identify("user-42", mapOf("plan" to "free")).sent().traits)
        }

        @Test
        fun `the same id without traits does nothing`() {
            val (identity) = identity()
            identity.identify("user-42", emptyMap())
            identity.register("plan", "pro")

            assertSame(Decision.NoEvent, identity.identify("user-42", emptyMap())!!.decision)
            assertEquals(mapOf("plan" to "pro"), identity.snapshot().value!!.superProperties)
            assertTrue(cleared.isEmpty())
        }

        @Test
        fun `a different id clears the person's state first, logs INFO without ids and sends`() {
            val (identity, person) = identity()
            identity.identify("user-42", emptyMap())
            identity.register("plan", "pro")
            person.update { it.copy(session = SessionState("s1", 1, 2)) }

            val event = identity.identify("user-77", emptyMap()).sent()

            assertEquals("user-77", person.state.userId)
            assertEquals(emptyMap<String, Any?>(), person.state.superProperties)
            assertNull(person.state.session)
            assertEquals("user-77", event.identity.userId)
            assertEquals("user-42", cleared.single().userId)
            assertEquals(mapOf("plan" to "pro"), cleared.single().superProperties)
            val info = logger.lines.single { it.level == LogLevel.INFO }
            assertFalse(info.message.contains("user-42") || info.message.contains("user-77"))
        }

        @Test
        fun `while opted out the id changes but no event is sent`() {
            val (identity, person) = identity()
            optedOut = true

            assertSame(Decision.NoEvent, identity.identify("user-42", mapOf("plan" to "pro"))!!.decision)
            assertEquals("user-42", person.state.userId)
        }

        @Test
        fun `traits are normalized and never stored`() {
            val (identity, person) = identity()

            val event = identity.identify("user-42", mapOf("trait_plan" to "trait-value", "signup" to Instant.EPOCH)).sent()
            person.persist()

            assertEquals(mapOf("trait_plan" to "trait-value"), event.traits)
            assertTrue(warned(Code.PROPERTY_DROPPED))
            val stored = (keyValueStore.read(PersonStore.FILE_NAME) as KeyValueStore.Read.Present).content
            assertTrue(stored.contains("user-42"))
            assertFalse(stored.contains("trait_plan") || stored.contains("trait-value"), stored)
        }
    }

    @Nested
    inner class `invalid user ids` {
        @Test
        fun `blank and 256-character ids are ignored with a warning in release`() {
            val (identity, person) = identity(debug = false)

            assertNull(identity.identify("", emptyMap()))
            assertNull(identity.identify("   ", emptyMap()))
            assertNull(identity.identify("x".repeat(256), emptyMap()))

            assertNull(person.state.userId)
            assertTrue(warned(Code.INVALID_USER_ID))
            assertFalse(logger.messages.any { it.contains("x".repeat(20)) })
        }

        @Test
        fun `blank and 256-character ids throw in debug`() {
            val (identity, person) = identity(debug = true)

            assertThrows<IllegalArgumentException> { identity.identify(" ", emptyMap()) }
            assertThrows<IllegalArgumentException> { identity.identify("x".repeat(256), emptyMap()) }
            assertNull(person.state.userId)
        }

        @Test
        fun `255 characters are fine and ids are never trimmed`() {
            val (identity, person) = identity()

            identity.identify("x".repeat(255), emptyMap())
            assertEquals(255, person.state.userId!!.length)
            identity.identify(" user-42 ", emptyMap())
            assertEquals(" user-42 ", person.state.userId)
        }
    }

    @Nested
    inner class `reset and super properties` {
        @Test
        fun `reset clears user and all super properties, keeps the device id and sends nothing`() {
            val (identity, person, device) = identity()
            identity.identify("user-42", emptyMap())
            identity.register("theme", "dark")

            identity.reset()

            val after = identity.snapshot().value!!
            assertNull(after.userId)
            assertEquals(emptyMap<String, Any?>(), after.superProperties)
            assertEquals("device-1", after.deviceId)
            assertEquals("device-1", device.state.deviceId)
            assertEquals(1, ids)
            assertEquals(PersonState.ANONYMOUS, person.state)
            assertEquals("user-42", cleared.single().userId)
        }

        @Test
        fun `a null super property is stored and sent as null`() {
            val (identity, person) = identity()

            assertTrue(identity.register("coupon", null))
            person.persist()

            assertEquals(mapOf("coupon" to null), identity.snapshot().value!!.superProperties)
            val stored = (keyValueStore.read(PersonStore.FILE_NAME) as KeyValueStore.Read.Present).content
            assertTrue(stored.contains("\"coupon\":null"), stored)
        }

        @Test
        fun `super properties are validated at register`() {
            val (identity) = identity(debug = false)

            assertFalse(identity.register("at", Instant.EPOCH))
            assertFalse(identity.register("", "x"))
            assertTrue(identity.register("long", "y".repeat(2_000)))

            assertEquals(
                setOf("long"),
                identity
                    .snapshot()
                    .value!!
                    .superProperties.keys,
            )
            assertEquals(1024, (identity.snapshot().value!!.superProperties["long"] as String).length)
            assertThrows<IllegalArgumentException> { identity(debug = true).first.register("at", Instant.EPOCH) }
        }

        @Test
        fun `unregister removes a key`() {
            val (identity) = identity()
            identity.register("a", 1)
            identity.register("b", 2)

            identity.unregister("a")
            identity.unregister("missing")

            assertEquals(mapOf("b" to 2), identity.snapshot().value!!.superProperties)
        }

        @Test
        fun `every change asks the writer to persist`() {
            val (identity) = identity()
            val before = persistRequests

            identity.identify("u", emptyMap())
            identity.register("a", 1)
            identity.unregister("a")
            identity.reset()

            assertEquals(before + 4, persistRequests)
        }
    }

    @Nested
    inner class `ordering` {
        @Test
        fun `identify then a snapshot on the same thread carries the new id`() {
            val (identity) = identity()

            identity.identify("user-42", emptyMap())

            assertEquals("user-42", identity.userId())
        }

        @Test
        fun `calls before the stores are loaded apply at load, in call order`() {
            val (identity, person, device) = identity(load = false)

            val first = identity.snapshot()
            val ticket = identity.identify("user-42", emptyMap())!!
            identity.register("plan", "pro")
            val second = identity.snapshot()
            identity.reset()
            val third = identity.snapshot()

            assertNull(first.value)
            assertNull(ticket.decision)
            loadAll(identity, person, device)

            assertNull(first.value!!.userId)
            assertEquals("user-42", (ticket.decision as Decision.SendEvent).identity.userId)
            assertEquals("user-42", second.value!!.userId)
            assertEquals(mapOf("plan" to "pro"), second.value!!.superProperties)
            assertNull(third.value!!.userId)
            assertEquals(emptyMap<String, Any?>(), third.value!!.superProperties)
        }

        @Test
        fun `an identify before load uses the stored id to decide`() {
            val (earlier, earlierPerson) = identity()
            earlier.identify("user-42", emptyMap())
            earlierPerson.persist()
            val (identity, person, device) = identity(load = false)

            val same = identity.identify("user-42", emptyMap())!!
            loadAll(identity, person, device)

            assertSame(Decision.NoEvent, same.decision)
        }

        @Test
        fun `invalid input is rejected on the calling thread, before load`() {
            val (identity) = identity(debug = true, load = false)

            assertThrows<IllegalArgumentException> { identity.identify("", emptyMap()) }
        }

        @Test
        fun `snapshots from many threads never see a half-made change`() {
            val (identity) = identity()
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val failures = AtomicInteger()
            repeat(8) { thread ->
                pool.execute {
                    start.await()
                    repeat(500) {
                        try {
                            identity.register("k$thread", it)
                            val properties = identity.snapshot().value!!.superProperties
                            properties.forEach { _ -> }
                        } catch (t: Throwable) {
                            failures.incrementAndGet()
                        }
                    }
                }
            }

            start.countDown()
            pool.shutdown()

            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
            assertEquals(
                8,
                identity
                    .snapshot()
                    .value!!
                    .superProperties.size,
            )
        }
    }

    @Nested
    inner class `device id` {
        @TempDir
        lateinit var root: File

        @Test
        fun `reading it before the writer loads reads the file once and writes nothing`() {
            val (identity, person, device) = identity(load = false)

            val early = identity.deviceId

            assertEquals("device-1", early)
            assertInstanceOf(KeyValueStore.Read.Missing::class.java, keyValueStore.read(DeviceStore.FILE_NAME))
            loadAll(identity, person, device)
            assertEquals("device-1", identity.deviceId)
            assertEquals(1, ids)
            assertInstanceOf(KeyValueStore.Read.Present::class.java, keyValueStore.read(DeviceStore.FILE_NAME))
        }

        @Test
        fun `an unreadable device file gives a temporary id and stays untouched`() {
            val files = FileKeyValueStore(root)
            File(root, DeviceStore.FILE_NAME).apply { parentFile.mkdirs() }.writeText("""{"v":9,"device_id":"kept"}""")
            val before = File(root, DeviceStore.FILE_NAME).readBytes()

            val (identity, _, device) = identity(kv = files)
            identity.identify("user-42", emptyMap())
            device.persist()

            assertEquals("device-1", identity.deviceId)
            assertArrayEquals(before, File(root, DeviceStore.FILE_NAME).readBytes())
            assertTrue(warned(Code.STORE_UNREADABLE))
        }
    }
}
