package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.RubiconInternal
import java.io.IOException

/**
 * Pending events, as a log of segments where a closed segment is one batch (Disk 1). Only the writer
 * appends and only the uploader reads and deletes (Queue 1). Not replaceable by developers (Disk 6).
 */
@RubiconInternal
public interface EventStore {
    /** Events on disk, in closed segments and the open one. */
    public val pendingCount: Int

    /**
     * Appends one serialized event, without its line break, to the open segment. Returns `false`
     * when the write failed; the caller counts `storage_error` (Disk 4).
     */
    public fun append(line: String): Boolean

    /** Closes the open segment so that it becomes a batch; does nothing when it is empty. */
    public fun closeOpenSegment()

    /** Makes the open segment durable, on `ON_STOP` (Disk 4). */
    public fun sync()

    /** Indexes of the closed segments, oldest first (Disk 2b). */
    public fun closedSegments(): List<Long>

    /**
     * Streams the lines of a closed segment into [block]; the segment is never loaded whole.
     *
     * @throws IOException when the segment cannot be read (Disk 5).
     */
    public fun <T> readSegment(
        index: Long,
        block: (Sequence<String>) -> T,
    ): T

    /** Deletes a closed segment, after the server acknowledged it or when it is dropped. */
    public fun deleteSegment(index: Long)
}
