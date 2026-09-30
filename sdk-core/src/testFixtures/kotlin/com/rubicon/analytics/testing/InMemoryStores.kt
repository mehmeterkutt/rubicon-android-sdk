package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.storage.EventStore
import com.rubicon.analytics.internal.storage.KeyValueStore
import java.io.IOException
import java.util.TreeMap

/**
 * An [EventStore] in memory with the segment rules of Disk 1: the open segment closes at
 * [maxEventsPerSegment] events, or before an event that would take it past [maxBytesPerSegment]
 * (line plus line break, UTF-8), or when asked to.
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

    override val pendingCount: Int
        get() = synchronized(lock) { closed.values.sumOf { it.size } + open.size }

    override fun append(line: String): Boolean =
        synchronized(lock) {
            if (failAppends) return false
            val bytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (open.isNotEmpty() && openBytes + bytes > maxBytesPerSegment) closeLocked()
            open += line
            openBytes += bytes
            if (open.size >= maxEventsPerSegment) closeLocked()
            true
        }

    override fun closeOpenSegment() {
        synchronized(lock) { closeLocked() }
    }

    override fun sync() {
        syncCount++
    }

    override fun closedSegments(): List<Long> = synchronized(lock) { closed.keys.toList() }

    override fun <T> readSegment(
        index: Long,
        block: (Sequence<String>) -> T,
    ): T {
        val lines =
            synchronized(lock) {
                if (index in unreadable) throw IOException("Segment $index is unreadable")
                closed[index] ?: throw IOException("No segment $index")
            }
        return block(lines.asSequence())
    }

    override fun deleteSegment(index: Long) {
        synchronized(lock) {
            closed.remove(index)
            unreadable.remove(index)
        }
    }

    /** Makes [readSegment] of [index] throw, as an I/O error would (Disk 5). */
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
