package com.rubicon.analytics.internal.util

import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UuidV7GeneratorTest {
    private val clock = FakeClock(start = 1_759_147_200_123L)
    private val generator = UuidV7Generator(clock, SeededRandom(1))

    // Lowercase hex of fixed width: string order is numeric order.
    private fun assertAscending(ids: List<String>) {
        ids.zipWithNext().forEach { (a, b) -> assertTrue(a < b, "$a should sort before $b") }
    }

    private fun timestampOf(id: String): Long = UUID.fromString(id).mostSignificantBits ushr 16

    @Nested
    inner class `bit layout` {
        @Test
        fun `is a version 7, RFC variant UUID that java util UUID parses`() {
            val id = generator.next()
            val uuid = UUID.fromString(id)

            assertEquals(7, uuid.version())
            assertEquals(2, uuid.variant())
            assertEquals(id, uuid.toString())
            assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
        }

        @Test
        fun `the timestamp field is the clock's time`() {
            assertEquals(clock.currentTimeMillis(), timestampOf(generator.next()))
            clock.advanceBy(5_000)
            assertEquals(clock.currentTimeMillis(), timestampOf(generator.next()))
        }

        @Test
        fun `the same seed and clock give the same ids`() {
            val a = UuidV7Generator(FakeClock(start = 0), SeededRandom(9))
            val b = UuidV7Generator(FakeClock(start = 0), SeededRandom(9))

            assertEquals(List(3) { a.next() }, List(3) { b.next() })
        }
    }

    @Nested
    inner class `sort order` {
        @Test
        fun `10 000 ids in one millisecond are unique and ordered`() {
            val ids = List(10_000) { generator.next() }

            assertEquals(10_000, ids.toSet().size)
            assertAscending(ids)
            assertTrue(ids.all { timestampOf(it) == clock.currentTimeMillis() })
        }

        @Test
        fun `ids of a later millisecond sort after`() {
            val first = List(100) { generator.next() }
            clock.advanceBy(1)
            val second = List(100) { generator.next() }

            assertAscending(first + second)
        }

        @Test
        fun `order holds when the clock moves back`() {
            val before = List(50) { generator.next() }
            clock.advanceBy(-86_400_000)
            val after = List(50) { generator.next() }

            assertAscending(before + after)
            assertEquals(timestampOf(before.last()), timestampOf(after.last()))
        }

        @Test
        fun `a full counter moves the timestamp one millisecond ahead`() {
            // A 3-bit counter: seeds 0 to 3, at most 7, so a few ids overflow it.
            val small = UuidV7Generator(clock, SeededRandom(1), counterMax = 7)
            val ids = List(40) { small.next() }

            assertAscending(ids)
            assertEquals(40, ids.toSet().size)
            assertTrue(timestampOf(ids.last()) > clock.currentTimeMillis())
        }
    }

    @Test
    fun `ids from many threads are unique and ordered per thread`() {
        val threads = 8
        val perThread = 2_000
        val all = Collections.synchronizedList(mutableListOf<String>())
        val perThreadOrdered = Collections.synchronizedList(mutableListOf<Boolean>())
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                val mine = List(perThread) { generator.next() }
                perThreadOrdered += mine.zipWithNext().all { (a, b) -> a < b }
                all += mine
                done.countDown()
            }.start()
        }

        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals(threads * perThread, all.toSet().size)
        assertEquals(List(threads) { true }, perThreadOrdered.toList())
    }
}
