package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.json.Json
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.util.Hashing
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.TreeMap

/**
 * The [EventStore] of Disk 1 to 5: a segmented append log under `{root}/events/v1/{keyHash}/`.
 *
 * - The open segment is `{index}.tmp`, written with one `write()` per line and kept open. It closes
 *   at [MAX_EVENTS] events, before a line that would take it past [MAX_BYTES], or on request: synced,
 *   then renamed to `{index}`. A closed segment is never modified and is one batch.
 * - Indexes are ten digits, so name order is numeric order; the next one is the largest in the
 *   directory plus one (Disk 2b).
 * - Only `events/v1/` is touched: a later format in `events/v2/` survives a downgrade.
 * - The writer appends and closes; the uploader expires, reads and deletes. In-memory bookkeeping is
 *   guarded by a lock; file work on a closed segment runs outside it, since only the uploader reads
 *   and deletes closed segments.
 */
@RubiconInternal
public class FileEventStore(
    private val root: File,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : EventStore {
    private val lock = Any()

    private lateinit var dir: File
    private lateinit var clock: Clock
    private lateinit var log: Log
    private var drops: DropCounter = DropCounter.NONE

    // Guarded by lock: closed segment index to its line count, oldest first.
    private val closed = TreeMap<Long, Int>()
    private var total = 0
    private var nextIndex = 0L

    // The segment the uploader is reading, which trimming leaves alone; guarded by lock.
    private var reading: Long? = null

    // Writer only.
    private var openIndex = -1L
    private var openSink: FileSink? = null
    private var openCount = 0
    private var openBytes = 0L

    // Uploader only: consecutive read failures, and what the last read of a segment skipped.
    private val readFailures = HashMap<Long, Int>()
    private val skipped = HashMap<Long, Skipped>()
    private val corruptReported = HashSet<Long>()

    private class Skipped(
        val corrupt: Int,
        val expired: Int,
    )

    override val pendingCount: Int
        get() = synchronized(lock) { total }

    override fun open(
        apiKey: String,
        clock: Clock,
        log: Log,
        drops: DropCounter,
    ) {
        this.clock = clock
        this.log = log
        this.drops = drops
        val keyHash = Hashing.sha256Hex(apiKey).take(KEY_HASH_LENGTH)
        val formatDir = File(File(root, EVENTS_DIR), FORMAT_DIR)
        dir = File(formatDir, keyHash)
        try {
            fileSystem.mkdirs(dir)
            deleteOtherKeys(formatDir, keyHash)
            scanOwnDirectory()
        } catch (e: IOException) {
            log.error(Code.STORAGE_WRITE_FAILED, e) { "Could not prepare the event directory" }
        }
        expire()
        trimToCapacity()
    }

    override fun append(line: String): Boolean {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (openSink != null && openBytes + bytes.size > MAX_BYTES) closeOpenSegment()
        try {
            val sink = openSink ?: startSegment()
            sink.write(bytes)
        } catch (e: IOException) {
            abandonOpenSegment()
            drops.count(DropReason.STORAGE_ERROR, 1)
            log.error(Code.STORAGE_WRITE_FAILED, e) { "Could not write an event; it was dropped" }
            return false
        }
        openCount++
        openBytes += bytes.size
        synchronized(lock) { total++ }
        if (openCount >= MAX_EVENTS) closeOpenSegment()
        trimToCapacity()
        return true
    }

    override fun closeOpenSegment() {
        val sink = openSink ?: return
        val index = openIndex
        val count = openCount
        resetOpen()
        try {
            sink.use { it.sync() }
            if (!fileSystem.rename(tmpFile(index), segmentFile(index))) throw IOException("Could not rename ${name(index)}.tmp")
            synchronized(lock) { closed[index] = count }
            log.debug { "Closed segment ${name(index)} with $count events" }
        } catch (e: IOException) {
            // The .tmp stays and is recovered by the next process; its events leave this one's count.
            synchronized(lock) { total -= count }
            log.error(Code.STORAGE_WRITE_FAILED, e) { "Could not close segment ${name(index)}; it is recovered at the next start" }
        }
    }

    override fun sync() {
        try {
            openSink?.sync()
        } catch (e: IOException) {
            log.debug { "Could not sync the open segment: ${e.javaClass.simpleName}" }
        }
    }

    override fun expire() {
        val now = clock.currentTimeMillis()
        for (index in closedSegments()) {
            if (synchronized(lock) { index == reading }) continue
            val scan =
                try {
                    scan(index, now)
                } catch (e: IOException) {
                    continue // writeBatch retries and deletes unreadable segments
                }
            if (scan.live == 0 && scan.expired > 0) {
                if (scan.corrupt > 0 && corruptReported.add(index)) {
                    log.error(Code.CORRUPT_LINE) { "Skipped ${scan.corrupt} corrupt lines of expired segment ${name(index)}" }
                }
                skipped[index] = Skipped(scan.corrupt, scan.expired)
                deleteSegment(index)
            }
        }
    }

    override fun closedSegments(): List<Long> = synchronized(lock) { closed.keys.toList() }

    override fun writeBatch(
        index: Long,
        sentAt: Long,
        out: OutputStream,
    ): BatchResult {
        synchronized(lock) {
            if (index !in closed) return BatchResult.Written(emptyList())
            reading = index
        }
        try {
            val now = clock.currentTimeMillis()
            val ids = ArrayList<String>()
            var corrupt = 0
            var expired = 0
            val reportCorrupt = corruptReported.add(index)
            out.write("{\"sent_at\":$sentAt,\"batch\":[".toByteArray(Charsets.UTF_8))
            forEachLine(segmentFile(index)) { number, line ->
                when (val parsed = parse(line)) {
                    null -> {
                        corrupt++
                        if (reportCorrupt) reportCorruptLine(index, number, line)
                    }
                    else ->
                        if (isExpired(parsed.timestamp, now)) {
                            expired++
                        } else {
                            if (ids.isNotEmpty()) out.write(COMMA)
                            out.write(line.toByteArray(Charsets.UTF_8))
                            ids += parsed.messageId
                        }
                }
            }
            out.write("]}".toByteArray(Charsets.UTF_8))
            readFailures.remove(index)
            skipped[index] = Skipped(corrupt, expired)
            return BatchResult.Written(ids)
        } catch (e: IOException) {
            corruptReported.remove(index)
            val failures = (readFailures[index] ?: 0) + 1
            readFailures[index] = failures
            if (failures < MAX_READ_FAILURES) {
                log.debug { "Could not read segment ${name(index)} (attempt $failures): ${e.javaClass.simpleName}" }
                return BatchResult.Unreadable(deleted = false, cause = e)
            }
            log.error(Code.UNREADABLE_SEGMENT, e) { "Segment ${name(index)} could not be read $failures times; it was deleted" }
            skipped.remove(index)
            deleteSegment(index)
            return BatchResult.Unreadable(deleted = true, cause = e)
        } finally {
            synchronized(lock) { reading = null }
        }
    }

    override fun deleteSegment(index: Long) {
        val count =
            synchronized(lock) {
                val count = closed.remove(index) ?: return
                total -= count
                count
            }
        readFailures.remove(index)
        corruptReported.remove(index)
        deleteQuietly(segmentFile(index))
        skipped.remove(index)?.let {
            if (it.corrupt > 0) drops.count(DropReason.CORRUPT, it.corrupt)
            if (it.expired > 0) {
                drops.count(DropReason.EXPIRED, it.expired)
                log.warn(Code.EVENTS_EXPIRED) { "Deleted ${it.expired} events older than 3 days without sending them" }
            }
        }
        log.debug { "Deleted segment ${name(index)} ($count lines)" }
    }

    // Startup

    private fun deleteOtherKeys(
        formatDir: File,
        keyHash: String,
    ) {
        var events = 0
        for (other in fileSystem.list(formatDir)) {
            if (other.name == keyHash) continue
            events += deleteTree(other)
        }
        if (events > 0) {
            drops.count(DropReason.API_KEY_CHANGED, events)
            log.warn(Code.API_KEY_CHANGED) { "Deleted $events events tracked with another API key" }
        }
    }

    /** Deletes [file] and everything under it; returns the event lines it held, as far as readable. */
    private fun deleteTree(file: File): Int {
        val children = fileSystem.list(file)
        var events = 0
        if (children.isEmpty()) {
            events = countLinesQuietly(file)
        } else {
            for (child in children) events += deleteTree(child)
        }
        deleteQuietly(file)
        return events
    }

    private fun scanOwnDirectory() {
        val leftovers = ArrayList<Long>()
        var maxIndex = -1L
        for (file in fileSystem.list(dir)) {
            val name = file.name
            when {
                SEGMENT.matches(name) -> {
                    val index = name.toLong()
                    maxIndex = maxOf(maxIndex, index)
                    val count = countLinesQuietly(file)
                    synchronized(lock) {
                        closed[index] = count
                        total += count
                    }
                }
                OPEN_SEGMENT.matches(name) -> {
                    val index = name.removeSuffix(TMP).toLong()
                    maxIndex = maxOf(maxIndex, index)
                    leftovers += index
                }
                else -> {
                    deleteTree(file)
                    log.warn(Code.UNKNOWN_FILE) { "Deleted an unknown file in the events directory: $name" }
                }
            }
        }
        nextIndex = maxIndex + 1
        for (index in leftovers.sorted()) recoverLeftover(index)
    }

    /** Keeps the complete lines of a `.tmp` left by an earlier process and closes it (Disk 4). */
    private fun recoverLeftover(index: Long) {
        val tmp = tmpFile(index)
        try {
            val scan = scanBytes(tmp)
            if (scan.halfLine) {
                drops.count(DropReason.CORRUPT, 1)
                log.error(Code.CORRUPT_LINE) { "Dropped a half-written last line of segment ${name(index)}" }
            }
            if (scan.lines == 0) {
                fileSystem.delete(tmp)
                return
            }
            if (scan.halfLine) fileSystem.truncate(tmp, scan.completeBytes)
            fileSystem.openAppend(tmp).use { it.sync() }
            // A closed segment of the same index cannot normally exist; never overwrite one.
            val target = if (fileSystem.exists(segmentFile(index))) nextIndex++ else index
            if (!fileSystem.rename(tmp, segmentFile(target))) throw IOException("Could not rename ${name(index)}.tmp")
            synchronized(lock) {
                closed[target] = scan.lines
                total += scan.lines
            }
            log.debug { "Recovered segment ${name(index)} with ${scan.lines} events" }
        } catch (e: IOException) {
            log.error(Code.STORAGE_WRITE_FAILED, e) { "Could not recover segment ${name(index)}; it is tried again at the next start" }
        }
    }

    // Capacity

    private fun trimToCapacity() {
        var dropped = 0
        synchronized(lock) {
            if (total <= MAX_PENDING) return
            val victims = ArrayList<Long>()
            var remaining = total
            for ((index, count) in closed) {
                if (remaining <= TRIM_TO) break
                if (index == reading) continue
                victims += index
                remaining -= count
            }
            for (index in victims) {
                val count = closed.remove(index) ?: continue
                total -= count
                dropped += count
                deleteQuietly(segmentFile(index))
            }
        }
        if (dropped > 0) {
            drops.count(DropReason.QUEUE_FULL, dropped)
            log.warn(Code.QUEUE_FULL) { "More than $MAX_PENDING events were pending; dropped the oldest $dropped" }
        }
    }

    // Writing

    private fun startSegment(): FileSink {
        val index = synchronized(lock) { nextIndex++ }
        val sink = fileSystem.openAppend(tmpFile(index))
        openIndex = index
        openSink = sink
        openCount = 0
        openBytes = 0
        return sink
    }

    /** After a failed write: keeps the lines written before it as a closed segment if possible. */
    private fun abandonOpenSegment() {
        val sink = openSink ?: return
        val index = openIndex
        val count = openCount
        val bytes = openBytes
        resetOpen()
        try {
            sink.close()
            if (count == 0) {
                fileSystem.delete(tmpFile(index))
                return
            }
            fileSystem.truncate(tmpFile(index), bytes)
            fileSystem.openAppend(tmpFile(index)).use { it.sync() }
            if (!fileSystem.rename(tmpFile(index), segmentFile(index))) throw IOException("Could not rename ${name(index)}.tmp")
            synchronized(lock) { closed[index] = count }
        } catch (e: IOException) {
            // The .tmp stays and is recovered by the next process.
            synchronized(lock) { total -= count }
        }
    }

    private fun resetOpen() {
        openSink = null
        openIndex = -1
        openCount = 0
        openBytes = 0
    }

    // Reading

    private class Parsed(
        val messageId: String,
        val timestamp: Long,
    )

    /** A line that is a JSON object with `type`, `message_id` and a whole-number `timestamp` (Disk 5). */
    private fun parse(line: String): Parsed? {
        val fields = Json.readObject(line) ?: return null
        if (fields.string("type") == null) return null
        val messageId = fields.string("message_id") ?: return null
        val timestamp = fields.long("timestamp") ?: return null
        return Parsed(messageId, timestamp)
    }

    private fun isExpired(
        timestamp: Long,
        now: Long,
    ) = now - timestamp > MAX_AGE_MS

    private class Scan(
        val live: Int,
        val expired: Int,
        val corrupt: Int,
    )

    private fun scan(
        index: Long,
        now: Long,
    ): Scan {
        var live = 0
        var expired = 0
        var corrupt = 0
        forEachLine(segmentFile(index)) { _, line ->
            val parsed = parse(line)
            when {
                parsed == null -> corrupt++
                isExpired(parsed.timestamp, now) -> expired++
                else -> live++
            }
        }
        return Scan(live, expired, corrupt)
    }

    private fun reportCorruptLine(
        index: Long,
        number: Int,
        line: String,
    ) {
        // Only the message id, never the content (Disk 5, Logging 4a).
        val id =
            Json
                .readObject(line)
                ?.string("message_id")
                ?.let { ", message_id $it" }
                .orEmpty()
        log.error(Code.CORRUPT_LINE) { "Skipped corrupt line $number of segment ${name(index)}$id" }
    }

    private inline fun forEachLine(
        file: File,
        action: (number: Int, line: String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(fileSystem.openRead(file), Charsets.UTF_8), READ_BUFFER).use { reader ->
            var number = 0
            while (true) {
                val line = reader.readLine() ?: break
                action(++number, line)
            }
        }
    }

    private class ByteScan(
        val lines: Int,
        val completeBytes: Long,
        val halfLine: Boolean,
    )

    private fun scanBytes(file: File): ByteScan {
        var lines = 0
        var position = 0L
        var lastLineEnd = 0L
        fileSystem.openRead(file).use { input ->
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (i in 0 until read) {
                    if (buffer[i] == NEWLINE) {
                        lines++
                        lastLineEnd = position + i + 1
                    }
                }
                position += read
            }
        }
        return ByteScan(lines, lastLineEnd, position > lastLineEnd)
    }

    private fun countLinesQuietly(file: File): Int =
        try {
            scanBytes(file).lines
        } catch (e: IOException) {
            0
        }

    private fun deleteQuietly(file: File) {
        try {
            fileSystem.delete(file)
        } catch (e: IOException) {
            log.debug { "Could not delete ${file.name}: ${e.javaClass.simpleName}" }
        }
    }

    private fun name(index: Long): String = index.toString().padStart(INDEX_WIDTH, '0')

    private fun segmentFile(index: Long) = File(dir, name(index))

    private fun tmpFile(index: Long) = File(dir, name(index) + TMP)

    public companion object {
        /** Events per segment, and so per batch (Queue 4). */
        public const val MAX_EVENTS: Int = 100

        /** Bytes per segment, lines and line breaks before compression (Queue 4). */
        public const val MAX_BYTES: Long = 512L * 1024

        /** Pending events allowed, and the count trimming goes down to (Queue 5). */
        public const val MAX_PENDING: Int = 1000
        public const val TRIM_TO: Int = 900

        /** Events older than this are deleted unsent (Queue 5). */
        public const val MAX_AGE_MS: Long = 3L * 24 * 60 * 60 * 1000

        /** Consecutive read failures before a segment is deleted (Disk 5). */
        public const val MAX_READ_FAILURES: Int = 3

        private const val EVENTS_DIR = "events"
        private const val FORMAT_DIR = "v1"
        private const val KEY_HASH_LENGTH = 16
        private const val INDEX_WIDTH = 10
        private const val TMP = ".tmp"
        private const val READ_BUFFER = 8 * 1024
        private const val NEWLINE = '\n'.code.toByte()
        private val COMMA = byteArrayOf(','.code.toByte())
        private val SEGMENT = Regex("\\d{10}")
        private val OPEN_SEGMENT = Regex("\\d{10}\\.tmp")
    }
}
