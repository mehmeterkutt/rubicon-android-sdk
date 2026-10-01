package com.rubicon.analytics

/**
 * Why events were dropped (Queue 5), as reported by [EventUpdate.Dropped] and counted in the
 * automatic `SDK Diagnostics` event. Values may be added in minor versions; a `when` over this enum
 * should keep an `else` branch.
 */
public enum class DropReason {
    /** More than 1000 events were waiting; the oldest were dropped. */
    QUEUE_FULL,

    /** The event was older than three days when it could be sent. */
    EXPIRED,

    /** Events were tracked faster than they could be stored. */
    BACKPRESSURE,

    /** A `beforeSend` hook returned `null` or threw. */
    BEFORE_SEND,

    /** The event could not be written, for example on a full disk. */
    STORAGE_ERROR,

    /** A stored event was damaged and could not be read. */
    CORRUPT,

    /** The API key changed; events stored for the previous key were deleted. */
    API_KEY_CHANGED,

    /** The event was larger than 32 KB. */
    OVERSIZE,

    /** The server rejected the batch as malformed. */
    REJECTED,

    /** The batch kept failing while others were accepted, and was given up. */
    RETRY_EXHAUSTED,
}
