package com.rubicon.analytics.internal.store

import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersonStoreTest {
    private val support = StoreTestSupport()
    private val memory = InMemoryKeyValueStore()

    private fun store(keyValueStore: KeyValueStore = memory) = PersonStore(keyValueStore, support.log)

    private val identified =
        PersonState(
            userId = "user-42",
            superProperties = mapOf("plan" to "pro", "beta" to true, "tags" to listOf("a", "b"), "gone" to null),
            session = SessionState("0192e49f-0b1c-7d2e-8f3a-4b5c6d7e8f90", 1_759_147_082_000L, 1_759_147_260_000L),
        )

    @Nested
    inner class `the file` {
        @Test
        fun `person json has exactly the decided keys`() {
            val store = store().apply { load() }
            store.update { identified }
            store.persist()

            val stored = support.stored(memory, PersonStore.FILE_NAME)

            assertEquals(setOf("v", "user_id", "super_properties", "session"), stored.keys)
            assertEquals(setOf("id", "started_at", "last_activity"), stored["session"]!!.jsonObject.keys)
        }

        @Test
        fun `an anonymous person is written with null user and session`() {
            store().load()

            val stored = support.stored(memory, PersonStore.FILE_NAME)

            assertEquals(JsonNull, stored["user_id"])
            assertEquals(JsonNull, stored["session"])
            assertEquals("1", stored["v"].toString())
        }

        @Test
        fun `the state reads back as written`() {
            store().apply { load() }.apply {
                update { identified }
                persist()
            }

            val reloaded = store().apply { load() }

            assertEquals(identified, reloaded.state)
        }
    }

    @Nested
    inner class `missing and unreadable` {
        @Test
        fun `a missing file starts anonymous and is written at once`() {
            val store = store().apply { load() }

            assertEquals(PersonState.ANONYMOUS, store.state)
            assertEquals(1, memory.writeCount)
        }

        @Test
        fun `an unreadable file is anonymous in memory and never written`() {
            memory.write(PersonStore.FILE_NAME, "{}")
            memory.markUnreadable(PersonStore.FILE_NAME)
            val writes = memory.writeCount

            val store = store().apply { load() }
            store.update { identified }
            store.persist()

            assertEquals(identified, store.state)
            assertEquals(writes, memory.writeCount)
            assertTrue(support.warned(Code.STORE_UNREADABLE))
        }
    }

    @Nested
    inner class `files left byte-identical` {
        @TempDir
        lateinit var root: File

        private fun assertUntouched(content: String) {
            val files = FileKeyValueStore(root)
            File(root, PersonStore.FILE_NAME).apply { parentFile.mkdirs() }.writeText(content)
            val before = File(root, PersonStore.FILE_NAME).readBytes()

            val store = store(files).apply { load() }
            store.update { identified }
            store.persist()

            assertEquals(identified, store.state)
            assertArrayEquals(before, File(root, PersonStore.FILE_NAME).readBytes())
            assertTrue(support.warned(Code.STORE_UNREADABLE))
        }

        @Test
        fun `corrupt JSON`() = assertUntouched("""{"v":1,"user_id":"user-42",""")

        @Test
        fun `a newer version`() = assertUntouched("""{"v":2,"user_id":"user-42","super_properties":{},"session":null}""")

        @Test
        fun `no version`() = assertUntouched("""{"user_id":"user-42","super_properties":{},"session":null}""")

        @Test
        fun `a field of the wrong type`() = assertUntouched("""{"v":1,"user_id":42,"super_properties":{},"session":null}""")

        @Test
        fun `a session without its start`() = assertUntouched("""{"v":1,"user_id":null,"super_properties":{},"session":{"id":"s"}}""")
    }

    @Nested
    inner class `writes` {
        @Test
        fun `many updates in a row produce one write`() {
            val store = store().apply { load() }
            val writes = memory.writeCount

            repeat(50) { i -> store.update { it.copy(superProperties = it.superProperties + ("n" to i)) } }
            store.persist()

            assertEquals(writes + 1, memory.writeCount)
            assertEquals(49, store().apply { load() }.state.superProperties["n"])
        }

        @Test
        fun `nothing is written when nothing changed`() {
            val store = store().apply { load() }
            val writes = memory.writeCount

            store.update { it }
            store.persist()

            assertEquals(writes, memory.writeCount)
        }

        @Test
        fun `a failed write logs an error and is retried by the next persist`() {
            val store = store().apply { load() }
            memory.failWrites = true
            store.update { identified }

            assertFalse(store.persist())
            assertTrue(support.errored(Code.STORAGE_WRITE_FAILED))

            memory.failWrites = false
            assertTrue(store.persist())
            assertEquals(identified, store().apply { load() }.state)
        }

        @Test
        fun `a change is visible in memory before it is written`() {
            val store = store().apply { load() }

            store.update { it.copy(userId = "user-7") }

            assertEquals("user-7", store.state.userId)
            assertEquals(JsonNull, support.stored(memory, PersonStore.FILE_NAME)["user_id"])
        }
    }
}
