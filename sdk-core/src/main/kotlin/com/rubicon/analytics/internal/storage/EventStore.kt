package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import java.io.IOException
import java.io.OutputStream

/**
 * Pending events, as a log of segments where a closed segment is one batch (Disk 1). Only the writer
 * appends and only the uploader reads and deletes (Queue 1). Not replaceable by developers (Disk 6).
 *
 * The store enforces the capacity of Queue 5 and counts every event it drops through [DropCounter]:
 * `queue_full`, `expired`, `storage_error`, `corrupt` and `api_key_changed`. The uploader counts what
 * the server rejects.
 */
@RubiconInternal
public interface EventStore {
    /**
     * Startup work, the writer's first storage task and called once before anything else: deletes
     * other keys' events, recovers a leftover open segment, deletes unknown files, counts pending
     * events, expires old ones and enforces the capacity (Disk 2, 4, 5; Queue 5).
     */
    public fun open(
        apiKey: String,
        clock: Clock,
        log: Log,
        drops: DropCounter,
    )

    /** Events on disk, in closed segments and the open one. */
    public val pendingCount: Int

    /**
     * Appends one serialized event, without its line break, to the open segment, then drops the
     * oldest segments if the capacity is passed. Returns `false` when the write failed; the store has
     * counted `storage_error` and logged it, and the next event goes to a new segment (Disk 4).
     */
    public fun append(line: String): Boolean

    /** Closes the open segment so that it becomes a batch; does nothing when it is empty. */
    public fun closeOpenSegment()

    /** Makes the open segment durable, on `ON_STOP` (Disk 4). */
    public fun sync()

    /** Deletes closed segments whose events are all older than 3 days; before every upload (Queue 5). */
    public fun expire()

    /** Indexes of the closed segments, oldest first (Disk 2b). */
    public fun closedSegments(): List<Long>

    /**
     * Streams the request body of a closed segment into [out]: `{"sent_at":..,"batch":[..]}` with the
     * stored lines as they are, skipping corrupt and expired ones (Disk 3, 5; Serialization 5). The
     * segment is never loaded whole.
     *
     * A segment that cannot be read is retried on later calls and deleted after three consecutive
     * failures in the process (Disk 5); both cases are [BatchResult.Unreadable].
     */
    public fun writeBatch(
        index: Long,
        sentAt: Long,
        out: OutputStream,
    ): BatchResult

    /**
     * Deletes every pending event, closed segments and the open one, without counting them: for
     * `clearLocalData()` and for waiting events when a pending consent turns into an opt-out
     * (Privacy 3, 4). An upload already reading a segment may still complete.
     */
    public fun clear()

    /**
     * Deletes a closed segment after the server acknowledged it or when it is dropped. The corrupt and
     * expired lines its last [writeBatch] skipped are counted now, once.
     */
    public fun deleteSegment(index: Long)
}

/** What [EventStore.writeBatch] wrote. */
@RubiconInternal
public sealed interface BatchResult {
    /**
     * The body was written with [messageIds], in order. When it is empty every line was skipped:
     * the segment is deleted without an upload.
     */
    public class Written(
        public val messageIds: List<String>,
    ) : BatchResult

    /** The segment could not be read; [deleted] after the third consecutive failure. */
    public class Unreadable(
        public val deleted: Boolean,
        public val cause: IOException,
    ) : BatchResult
}

/** Counts dropped events by reason for `SDK Diagnostics` (Queue 5, Logging 5). */
@RubiconInternal
public fun interface DropCounter {
    public fun count(
        reason: DropReason,
        events: Int,
    )

    public companion object {
        public val NONE: DropCounter = DropCounter { _, _ -> }
    }
}
