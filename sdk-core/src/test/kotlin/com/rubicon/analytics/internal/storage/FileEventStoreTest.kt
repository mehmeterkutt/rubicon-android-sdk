package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.util.Hashing
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FaultyFileSystem
import com.rubicon.analytics.testing.FaultyFileSystem.Operation
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.random.Random

class FileEventStoreTest {
    @TempDir
    lateinit var root: File

    private val clock = FakeClock(NOW)
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val fs = FaultyFileSystem()
    private val dropped = mutableMapOf<DropReason, Int>()
    private val drops = DropCounter { reason, n -> dropped[reason] = (dropped[reason] ?: 0) + n }

    private val dir get() = File(root, "events/v1/${Hashing.sha256Hex(KEY).take(16)}")

    private fun store(key: String = KEY) = FileEventStore(root, fs).apply { open(key, clock, log, drops) }

    private fun line(
        id: String,
        timestamp: Long = NOW,
        padding: Int = 0,
    ) = """{"type":"track","event":"E","message_id":"$id","timestamp":$timestamp,"properties":{"p":"${"x".repeat(padding)}"}}"""

    private fun FileEventStore.body(index: Long): Pair<BatchResult.Written, String> {
        val out = ByteArrayOutputStream()
        val result = writeBatch(index, SENT_AT, out) as BatchResult.Written
        return result to out.toString("UTF-8")
    }

    private fun FileEventStore.ids(index: Long) = body(index).first.messageIds

    private fun files() = dir.list()!!.sorted()

    private fun logged(code: Code) = logger.messages.any { it.startsWith("${code.name}:") }

    @Nested
    inner class `segments` {
        @Test
        fun `a segment closes at 100 events and is renamed`() {
            val store = store()

            repeat(250) { store.append(line("e$it")) }

            assertEquals(listOf(0L, 1L), store.closedSegments())
            assertEquals(listOf("0000000000", "0000000001", "0000000002.tmp"), files())
            assertEquals(250, store.pendingCount)
            assertEquals((0 until 100).map { "e$it" }, store.ids(0))
        }

        @Test
        fun `a segment closes before a line that would pass 512 KB`() {
            val store = store()
            val padding = 30_000

            repeat(40) { store.append(line("e$it", padding = padding)) }
            store.closeOpenSegment()

            store.closedSegments().forEach { assertTrue(File(dir, "%010d".format(it)).length() <= FileEventStore.MAX_BYTES) }
            assertTrue(store.closedSegments().size >= 3)
            assertEquals((0 until 40).map { "e$it" }, store.closedSegments().flatMap { store.ids(it) })
        }

        @Test
        fun `closing on request makes a batch, and an empty open segment is not closed`() {
            val store = store()
            store.closeOpenSegment()

            store.append(line("a"))
            store.closeOpenSegment()
            store.closeOpenSegment()

            assertEquals(listOf(0L), store.closedSegments())
            assertEquals(listOf("0000000000"), files())
        }

        @Test
        fun `each line is one write of the line and its line break`() {
            val store = store()

            store.append(line("a"))
            store.append(line("b"))

            assertEquals(line("a") + "\n" + line("b") + "\n", File(dir, "0000000000.tmp").readText())
        }

        @Test
        fun `index order survives a restart and gaps`() {
            store().apply {
                repeat(3) {
                    append(line("e$it"))
                    closeOpenSegment()
                }
                deleteSegment(1)
            }

            val restarted = store()
            restarted.append(line("next"))
            restarted.closeOpenSegment()

            assertEquals(listOf(0L, 2L, 3L), restarted.closedSegments())
            assertEquals(listOf("next"), restarted.ids(3))
            assertEquals(3, restarted.pendingCount)
        }

        @Test
        fun `10 000 events are read back in order`() {
            val store = store()
            val read = ArrayList<String>()

            fun drain(store: FileEventStore) =
                store.closedSegments().forEach { index ->
                    read += store.ids(index)
                    store.deleteSegment(index)
                }

            repeat(10_000) {
                store.append(line("e$it"))
                if (it % 900 == 899) drain(store)
            }
            drain(store().also { it.closeOpenSegment() })

            assertEquals((0 until 10_000).map { "e$it" }, read)
            assertEquals(null, dropped[DropReason.QUEUE_FULL])
        }

        @Test
        fun `files are app-private`() {
            store().append(line("a"))

            val mode = Files.getPosixFilePermissions(File(dir, "0000000000.tmp").toPath())
            assertEquals("rw-------", PosixFilePermissions.toString(mode))
        }
    }

    @Nested
    inner class `the batch body` {
        @Test
        fun `is the stored lines joined, byte for byte`() {
            val store = store()
            val lines = listOf(line("a"), line("ö-💳"), """{"type":"screen","name":"s","message_id":"c","timestamp":$NOW,"x":"\/"}""")
            lines.forEach { store.append(it) }
            store.closeOpenSegment()

            val (result, body) = store.body(0)

            assertEquals("""{"sent_at":$SENT_AT,"batch":[${lines.joinToString(",")}]}""", body)
            assertEquals(listOf("a", "ö-💳", "c"), result.messageIds)
            val parsed = Json.parseToJsonElement(body).jsonObject
            assertEquals(SENT_AT, parsed["sent_at"]!!.jsonPrimitive.long)
            assertEquals(3, parsed["batch"]!!.jsonArray.size)
        }

        @Test
        fun `of an empty segment is an empty batch`() {
            val store = store()
            val (result, body) = store.body(42)

            assertEquals(emptyList<String>(), result.messageIds)
            assertEquals("", body)
        }
    }

    @Nested
    inner class `corrupt lines` {
        private fun storeWithLines(vararg lines: String): FileEventStore {
            dir.mkdirs()
            File(dir, "0000000000").writeText(lines.joinToString("") { "$it\n" })
            return store()
        }

        @Test
        fun `are skipped, logged without content and counted once when the segment is deleted`() {
            val store =
                storeWithLines(
                    line("a"),
                    "not json",
                    "[1,2]",
                    """{"message_id":"no-type","timestamp":1}""",
                    """{"type":"track","timestamp":1}""",
                    """{"type":"track","message_id":"secret-id","timestamp":"1"}""",
                    """{"type":"track","message_id":"x","timestamp":1.5}""",
                    line("b"),
                )

            val (result, body) = store.body(0)
            store.body(0)
            store.deleteSegment(0)

            assertEquals(listOf("a", "b"), result.messageIds)
            assertEquals("""{"sent_at":$SENT_AT,"batch":[${line("a")},${line("b")}]}""", body)
            assertEquals(6, dropped[DropReason.CORRUPT])
            val corrupt = logger.lines.filter { it.message.startsWith("CORRUPT_LINE") }
            assertEquals(LogLevel.ERROR, corrupt.first().level)
            assertEquals("CORRUPT_LINE: Skipped corrupt line 2 of segment 0000000000", corrupt.first().message)
            assertTrue(corrupt.any { it.message.endsWith("line 6 of segment 0000000000, message_id secret-id") })
            assertTrue(corrupt.drop(1).all { it.level == LogLevel.DEBUG })
            assertEquals(6, corrupt.size)
            assertFalse(logger.messages.any { it.contains("not json") })
        }

        @Test
        fun `a segment of corrupt lines only gives an empty batch`() {
            val store = storeWithLines("x", "y")

            assertEquals(emptyList<String>(), store.ids(0))
        }
    }

    @Nested
    inner class `leftover open segment` {
        @Test
        fun `complete lines are kept and the segment is closed`() {
            store().apply {
                append(line("a"))
                append(line("b"))
            }

            val restarted = store()

            assertEquals(listOf(0L), restarted.closedSegments())
            assertEquals(listOf("a", "b"), restarted.ids(0))
            assertEquals(2, restarted.pendingCount)
            assertEquals(listOf("0000000000"), files())
        }

        @Test
        fun `a half-written last line is dropped as corrupt`() {
            dir.mkdirs()
            File(dir, "0000000004.tmp").writeText(line("a") + "\n" + line("b").take(20))

            val store = store()

            assertEquals(listOf("a"), store.ids(4))
            assertEquals(1, dropped[DropReason.CORRUPT])
            assertTrue(logged(Code.CORRUPT_LINE))
            assertEquals(line("a") + "\n", File(dir, "0000000004").readText())
        }

        @Test
        fun `a leftover with no complete line is deleted`() {
            dir.mkdirs()
            File(dir, "0000000000.tmp").writeText("{\"type\":")

            val store = store()

            assertEquals(emptyList<Long>(), store.closedSegments())
            assertEquals(emptyList<String>(), files())
            assertEquals(1, dropped[DropReason.CORRUPT])
        }

        @Test
        fun `a truncation at every byte of the last line recovers the lines before it`() {
            val first = line("first")
            val last = line("last-ö")
            val lastBytes = (last + "\n").toByteArray(Charsets.UTF_8)
            val random = Random(42)
            for (cut in 0..lastBytes.size) {
                val caseRoot = File(root, "cut-$cut").apply { mkdirs() }
                val caseDir = File(caseRoot, "events/v1/${Hashing.sha256Hex(KEY).take(16)}").apply { mkdirs() }
                val index = random.nextLong(0, 9_999_999_999)
                File(caseDir, "%010d.tmp".format(index)).writeBytes((first + "\n").toByteArray() + lastBytes.copyOf(cut))
                val counted = mutableMapOf<DropReason, Int>()

                val store = FileEventStore(caseRoot).apply { open(KEY, clock, log) { r, n -> counted[r] = n } }
                val ids =
                    store.closedSegments().flatMap { idx ->
                        ByteArrayOutputStream().let { (store.writeBatch(idx, 1, it) as BatchResult.Written).messageIds }
                    }

                val complete = cut == lastBytes.size
                assertEquals(if (complete) listOf("first", "last-ö") else listOf("first"), ids, "cut at $cut")
                assertEquals(if (complete || cut == 0) null else 1, counted[DropReason.CORRUPT], "cut at $cut")
            }
        }
    }

    @Nested
    inner class `startup cleanup` {
        @Test
        fun `events of another API key are deleted and counted`() {
            store("old-key").apply {
                repeat(150) { append(line("e$it")) }
            }

            val store = store()

            assertEquals(150, dropped[DropReason.API_KEY_CHANGED])
            assertTrue(logged(Code.API_KEY_CHANGED))
            assertEquals(listOf(dir.name), File(root, "events/v1").list()!!.toList())
            assertEquals(0, store.pendingCount)
        }

        @Test
        fun `a later format directory is never touched`() {
            val v2 = File(root, "events/v2/abc").apply { mkdirs() }
            File(v2, "0000000000").writeText("future\n")

            store()

            assertEquals("future\n", File(v2, "0000000000").readText())
        }

        @Test
        fun `unknown files are deleted with a warning`() {
            dir.mkdirs()
            File(dir, "notes.txt").writeText("x")
            File(dir, "123").writeText("x")
            File(dir, "sub").mkdirs()
            File(dir, "0000000001").writeText(line("a") + "\n")

            val store = store()

            assertEquals(listOf("0000000001"), files())
            assertTrue(logged(Code.UNKNOWN_FILE))
            assertEquals(1, store.pendingCount)
            assertEquals(null, dropped[DropReason.CORRUPT])
        }

        @Test
        fun `the pending count comes from the lines on disk`() {
            store().apply { repeat(130) { append(line("e$it")) } }

            assertEquals(130, store().pendingCount)
        }
    }

    @Nested
    inner class `age limit` {
        private val threeDays = FileEventStore.MAX_AGE_MS

        @Test
        fun `an event exactly 3 days old is kept, one millisecond older is not`() {
            val store = store()
            store.append(line("kept", timestamp = NOW - threeDays))
            store.append(line("gone", timestamp = NOW - threeDays - 1))
            store.closeOpenSegment()

            assertEquals(listOf("kept"), store.ids(0))
        }

        @Test
        fun `a segment of expired events is deleted at startup and counted`() {
            store().apply {
                repeat(5) { append(line("old$it", timestamp = NOW - threeDays - 1)) }
                closeOpenSegment()
                append(line("new"))
                closeOpenSegment()
            }

            val restarted = store()

            assertEquals(listOf(1L), restarted.closedSegments())
            assertEquals(5, dropped[DropReason.EXPIRED])
            assertTrue(logged(Code.EVENTS_EXPIRED))
            assertEquals(1, restarted.pendingCount)
        }

        @Test
        fun `expire before an upload deletes segments that aged since startup`() {
            val store = store()
            store.append(line("a"))
            store.closeOpenSegment()

            clock.advanceBy(threeDays + 1)
            store.expire()

            assertEquals(emptyList<Long>(), store.closedSegments())
            assertEquals(1, dropped[DropReason.EXPIRED])
        }

        @Test
        fun `expired lines of a partly expired segment are skipped and counted on deletion`() {
            val store = store()
            store.append(line("old", timestamp = NOW - threeDays - 1))
            store.append(line("new"))
            store.closeOpenSegment()
            store.expire()

            assertEquals(listOf("new"), store.ids(0))
            assertEquals(null, dropped[DropReason.EXPIRED])
            store.deleteSegment(0)
            assertEquals(1, dropped[DropReason.EXPIRED])
        }

        @Test
        fun `events from a clock that was ahead are not expired`() {
            val store = store()
            store.append(line("future", timestamp = NOW + threeDays * 10))
            store.closeOpenSegment()
            store.expire()

            assertEquals(listOf("future"), store.ids(0))
        }
    }

    @Nested
    inner class `capacity` {
        @Test
        fun `passing 1000 events drops the oldest segments down to 900 and counts them`() {
            val store = store()

            repeat(1000) { store.append(line("e$it")) }
            assertEquals(1000, store.pendingCount)
            assertEquals(null, dropped[DropReason.QUEUE_FULL])

            store.append(line("e1000"))

            assertEquals(901 - 100, store.pendingCount)
            assertEquals(200, dropped[DropReason.QUEUE_FULL])
            assertTrue(logged(Code.QUEUE_FULL))
            assertEquals("e200", store.ids(store.closedSegments().first()).first())
        }

        @Test
        fun `small segments are trimmed one by one until at most 900 remain`() {
            val store = store()
            repeat(1001) {
                store.append(line("e$it"))
                store.closeOpenSegment()
            }

            assertEquals(900, store.pendingCount)
            assertEquals(101, dropped[DropReason.QUEUE_FULL])
            assertEquals("e101", store.ids(store.closedSegments().first()).single())
        }

        @Test
        fun `a batch on its way to the server is never trimmed`() {
            val store = store()
            repeat(1000) { store.append(line("e$it")) }
            // The uploader has read the oldest batch and is sending it.
            store.writeBatch(0, SENT_AT, ByteArrayOutputStream())

            store.append(line("e1000"))

            assertTrue(0L in store.closedSegments())
            assertEquals(listOf(0L, 3L), store.closedSegments().take(2))
            store.deleteSegment(0)
            store.append(line("e1001"))
            assertFalse(0L in store.closedSegments())
        }

        @Test
        fun `the capacity is enforced at startup too`() {
            dir.mkdirs()
            repeat(12) { segment ->
                File(dir, "%010d".format(segment)).writeText((0 until 100).joinToString("") { line("s$segment-$it") + "\n" })
            }

            val store = store()

            assertEquals(900, store.pendingCount)
            assertEquals(300, dropped[DropReason.QUEUE_FULL])
        }
    }

    @Nested
    inner class `clearing` {
        @Test
        fun `deletes closed and open segments without counting, and indexes keep growing`() {
            val store = store()
            repeat(150) { store.append(line("e$it")) }

            store.clear()

            assertEquals(emptyList<String>(), files())
            assertEquals(0, store.pendingCount)
            assertEquals(emptyList<Long>(), store.closedSegments())
            assertTrue(dropped.isEmpty())
            store.append(line("after"))
            store.closeOpenSegment()
            assertEquals(listOf(2L), store.closedSegments())
            assertEquals(listOf("after"), store.ids(2))
        }
    }

    @Nested
    inner class `failures` {
        @Test
        fun `a failed write counts storage_error, logs an error and the next event goes to a new segment`() {
            val store = store()
            store.append(line("a"))
            fs.failOnce(Operation.WRITE)

            assertFalse(store.append(line("lost")))
            assertTrue(store.append(line("b")))
            store.closeOpenSegment()

            assertEquals(1, dropped[DropReason.STORAGE_ERROR])
            assertTrue(logger.lines.any { it.level == LogLevel.ERROR && it.message.startsWith("STORAGE_WRITE_FAILED") })
            assertEquals(listOf(0L, 1L), store.closedSegments())
            assertEquals(listOf("a"), store.ids(0))
            assertEquals(listOf("b"), store.ids(1))
            assertEquals(2, store.pendingCount)
        }

        @Test
        fun `a disk that cannot be written keeps the SDK running`() {
            val store = store()
            fs.failAlways(Operation.OPEN_APPEND)

            repeat(5) { assertFalse(store.append(line("e$it"))) }

            assertEquals(5, dropped[DropReason.STORAGE_ERROR])
            assertEquals(0, store.pendingCount)
        }

        @Test
        fun `an unreadable segment is retried and deleted after three consecutive failures`() {
            val store = store()
            store.append(line("a"))
            store.closeOpenSegment()
            fs.failAlways(Operation.OPEN_READ)

            val results = (1..3).map { store.writeBatch(0, SENT_AT, ByteArrayOutputStream()) as BatchResult.Unreadable }

            assertEquals(listOf(false, false, true), results.map { it.deleted })
            assertEquals(emptyList<Long>(), store.closedSegments())
            assertTrue(logged(Code.UNREADABLE_SEGMENT))
            assertEquals(0, store.pendingCount)
        }

        @Test
        fun `a successful read resets the failure count`() {
            val store = store()
            store.append(line("a"))
            store.closeOpenSegment()

            repeat(2) {
                fs.failOnce(Operation.OPEN_READ)
                store.writeBatch(0, SENT_AT, ByteArrayOutputStream())
            }
            store.body(0)
            repeat(2) {
                fs.failOnce(Operation.OPEN_READ)
                store.writeBatch(0, SENT_AT, ByteArrayOutputStream())
            }

            assertEquals(listOf(0L), store.closedSegments())
        }

        @Test
        fun `sync makes the open segment durable and never throws`() {
            val store = store()
            store.append(line("a"))
            store.sync()
            fs.failOnce(Operation.SYNC)

            store.sync()

            assertArrayEquals((line("a") + "\n").toByteArray(), File(dir, "0000000000.tmp").readBytes())
        }
    }

    private companion object {
        const val KEY = "api-key"
        const val NOW = 1_759_147_200_000L
        const val SENT_AT = 1_759_147_260_000L
    }
}
