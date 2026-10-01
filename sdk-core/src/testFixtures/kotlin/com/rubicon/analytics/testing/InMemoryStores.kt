package com.rubicon.analytics.testing

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.internal.json.Json
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.storage.BatchResult
import com.rubicon.analytics.internal.storage.DropCounter
import com.rubicon.analytics.internal.storage.EventStore
import com.rubicon.analytics.internal.storage.KeyValueStore
import java.io.IOException
import java.io.OutputStream
import java.util.TreeMap

/**
 * An [EventStore] in memory with the segment rules of Disk 1: the open segment closes at
 * [maxEventsPerSegment] events, or before an event that would take it past [maxBytesPerSegment]
 * (line plus line break, UTF-8), or when asked to. [writeBatch] writes the body of Disk 3 from the
 * stored lines as they are.
 *
 * The capacity, age and corruption rules are not repeated here: they belong to `FileEventStore`, and
 * a test that needs them uses it with a temporary directory. A failed append counts `storage_error`
 * through the [DropCounter] given to [open].
 */
public class InMemoryEventStore(
    private val maxEventsPerSegment: Int = 100,
    private val maxBytesPerSegment: Int = 512 * 1024,
) : EventStore {
    private val lock = Any()
    private val closed = TreeMap<Long, List<String>>()
    private val open = mutableListOf<String>()
    private var openBytes = 0
    private var nextIndex = 0L
    private val unreadable = mutableSetOf<Long>()
    private var drops: DropCounter = DropCounter.NONE

    /** When `true`, [append] fails as a full disk would. */
    @Volatile
    public var failAppends: Boolean = false

    /** How many times [sync] was called. */
    @Volatile
    public var syncCount: Int = 0
        private set

    /** The lines of the open segment, for assertions. */
    public val openSegment: List<String>
        get() = synchronized(lock) { open.toList() }

    /** The lines of a closed segment, for assertions; empty when there is none. */
    public fun segmentLines(index: Long): List<String> = synchronized(lock) { closed[index].orEmpty() }

    override fun open(
        apiKey: String,
        clock: Clock,
        log: Log,
        drops: DropCounter,
    ) {
        this.drops = drops
    }

    override val pendingCount: Int
        get() = synchronized(lock) { closed.values.sumOf { it.size } + open.size }

    override fun append(line: String): Boolean {
        synchronized(lock) {
            if (!failAppends) {
                val bytes = line.toByteArray(Charsets.UTF_8).size + 1
                if (open.isNotEmpty() && openBytes + bytes > maxBytesPerSegment) closeLocked()
                open += line
                openBytes += bytes
                if (open.size >= maxEventsPerSegment) closeLocked()
                return true
            }
        }
        drops.count(DropReason.STORAGE_ERROR, 1)
        return false
    }

    override fun closeOpenSegment() {
        synchronized(lock) { closeLocked() }
    }

    override fun sync() {
        syncCount++
    }

    override fun expire(): Unit = Unit

    override fun closedSegments(): List<Long> = synchronized(lock) { closed.keys.toList() }

    override fun writeBatch(
        index: Long,
        sentAt: Long,
        out: OutputStream,
    ): BatchResult {
        val lines =
            synchronized(lock) {
                if (index in unreadable) return BatchResult.Unreadable(deleted = false, cause = IOException("Segment $index is unreadable"))
                closed[index] ?: return BatchResult.Written(emptyList())
            }
        out.write(("{\"sent_at\":$sentAt,\"batch\":[" + lines.joinToString(",") + "]}").toByteArray(Charsets.UTF_8))
        val ids = lines.map { Json.readObject(it)?.string("message_id") ?: it }
        return BatchResult.Written(ids)
    }

    override fun clear() {
        synchronized(lock) {
            closed.clear()
            open.clear()
            openBytes = 0
            unreadable.clear()
        }
    }

    override fun deleteSegment(index: Long) {
        synchronized(lock) {
            closed.remove(index)
            unreadable.remove(index)
        }
    }

    /** Makes [writeBatch] of [index] report it unreadable, as an I/O error would (Disk 5). */
    public fun markUnreadable(index: Long) {
        synchronized(lock) { unreadable += index }
    }

    private fun closeLocked() {
        if (open.isEmpty()) return
        closed[nextIndex++] = open.toList()
        open.clear()
        openBytes = 0
    }
}

/** A [KeyValueStore] in memory, with switches for the unreadable and failing cases. */
public class InMemoryKeyValueStore : KeyValueStore {
    private val lock = Any()
    private val values = mutableMapOf<String, String>()
    private val unreadable = mutableSetOf<String>()

    /** When `true`, [write] and [delete] fail as a full or read-only disk would. */
    @Volatile
    public var failWrites: Boolean = false

    /** How many writes succeeded, to check that changes are coalesced. */
    @Volatile
    public var writeCount: Int = 0
        private set

    override fun read(name: String): KeyValueStore.Read =
        synchronized(lock) {
            when {
                name in unreadable -> KeyValueStore.Read.Unreadable(IOException("$name is unreadable"))
                else -> values[name]?.let { KeyValueStore.Read.Present(it) } ?: KeyValueStore.Read.Missing
            }
        }

    override fun write(
        name: String,
        content: String,
    ): Boolean =
        synchronized(lock) {
            if (failWrites) return false
            values[name] = content
            unreadable -= name
            writeCount++
            true
        }

    override fun delete(name: String): Boolean =
        synchronized(lock) {
            if (failWrites) return false
            values.remove(name)
            unreadable -= name
            true
        }

    /** Makes [read] of [name] report it unreadable until it is written again. */
    public fun markUnreadable(name: String) {
        synchronized(lock) { unreadable += name }
    }
}
