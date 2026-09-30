package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.storage.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class InMemoryStoresTest {
    @Nested
    inner class `event store` {
        private fun InMemoryEventStore.lines(index: Long) = readSegment(index) { it.toList() }

        @Test
        fun `closes a segment at the event limit`() {
            val store = InMemoryEventStore(maxEventsPerSegment = 3)

            (1..7).forEach { store.append("e$it") }

            assertEquals(listOf(0L, 1L), store.closedSegments())
            assertEquals(listOf("e1", "e2", "e3"), store.lines(0))
            assertEquals(listOf("e4", "e5", "e6"), store.lines(1))
            assertEquals(listOf("e7"), store.openSegment)
            assertEquals(7, store.pendingCount)
        }

        @Test
        fun `closes a segment before an event that would pass the byte limit`() {
            // Each line counts its UTF-8 bytes plus the line break: "aaaa" is 5 bytes.
            val store = InMemoryEventStore(maxBytesPerSegment = 10)

            store.append("aaaa")
            store.append("bbbb")
            store.append("cccc")

            assertEquals(listOf("aaaa", "bbbb"), store.lines(0))
            assertEquals(listOf("cccc"), store.openSegment)
        }

        @Test
        fun `a line larger than the byte limit still gets its own segment`() {
            val store = InMemoryEventStore(maxBytesPerSegment = 4)

            store.append("too long")

            assertEquals(listOf("too long"), store.openSegment)
        }

        @Test
        fun `counts bytes as UTF-8`() {
            // "ş" is two bytes: "şş" plus the line break is 5 bytes.
            val store = InMemoryEventStore(maxBytesPerSegment = 9)

            store.append("şş")
            store.append("şş")

            assertEquals(listOf("şş"), store.lines(0))
        }

        @Test
        fun `closing on request keeps order and ignores an empty open segment`() {
            val store = InMemoryEventStore()

            store.closeOpenSegment()
            store.append("a")
            store.closeOpenSegment()
            store.closeOpenSegment()
            store.append("b")
            store.closeOpenSegment()

            assertEquals(listOf(0L, 1L), store.closedSegments())
            assertEquals(listOf("b"), store.lines(1))
        }

        @Test
        fun `deleting a segment removes its events`() {
            val store = InMemoryEventStore(maxEventsPerSegment = 1)
            store.append("a")
            store.append("b")

            store.deleteSegment(0)

            assertEquals(listOf(1L), store.closedSegments())
            assertEquals(1, store.pendingCount)
        }

        @Test
        fun `an unreadable or missing segment throws IOException`() {
            val store = InMemoryEventStore(maxEventsPerSegment = 1)
            store.append("a")
            store.markUnreadable(0)

            assertThrows<IOException> { store.lines(0) }
            assertThrows<IOException> { store.lines(5) }
            store.deleteSegment(0)
            assertEquals(emptyList<Long>(), store.closedSegments())
        }

        @Test
        fun `a failing append stores nothing`() {
            val store = InMemoryEventStore()
            store.failAppends = true

            assertFalse(store.append("a"))
            assertEquals(0, store.pendingCount)

            store.failAppends = false
            assertTrue(store.append("a"))
        }

        @Test
        fun `counts syncs`() {
            val store = InMemoryEventStore()

            store.sync()
            store.sync()

            assertEquals(2, store.syncCount)
        }
    }

    @Nested
    inner class `key-value store` {
        private val store = InMemoryKeyValueStore()

        @Test
        fun `missing, present and deleted`() {
            assertSame(KeyValueStore.Read.Missing, store.read("device"))

            assertTrue(store.write("device", "{\"v\":1}"))
            assertEquals("{\"v\":1}", (store.read("device") as KeyValueStore.Read.Present).content)

            assertTrue(store.delete("device"))
            assertSame(KeyValueStore.Read.Missing, store.read("device"))
        }

        @Test
        fun `unreadable until written again`() {
            store.write("person", "old")
            store.markUnreadable("person")

            assertInstanceOf(KeyValueStore.Read.Unreadable::class.java, store.read("person"))

            store.write("person", "new")
            assertEquals("new", (store.read("person") as KeyValueStore.Read.Present).content)
        }

        @Test
        fun `deleting clears unreadable`() {
            store.markUnreadable("person")

            store.delete("person")

            assertSame(KeyValueStore.Read.Missing, store.read("person"))
        }

        @Test
        fun `failing writes change nothing`() {
            store.write("device", "kept")
            store.failWrites = true

            assertFalse(store.write("device", "lost"))
            assertFalse(store.delete("device"))
            assertEquals("kept", (store.read("device") as KeyValueStore.Read.Present).content)
            assertEquals(1, store.writeCount)
        }
    }
}
