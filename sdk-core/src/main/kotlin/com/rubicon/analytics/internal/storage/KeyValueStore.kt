package com.rubicon.analytics.internal.storage

import com.rubicon.analytics.RubiconInternal

/**
 * The small whole-value files behind `PersonStore`, `DeviceStore` and the remote configuration cache
 * (Disk 7), addressed by [name]. Only the writer writes (Queue 1). Not replaceable by developers
 * (Disk 6).
 */
@RubiconInternal
public interface KeyValueStore {
    /** "Missing" and "unreadable" are different results (Identity 1). */
    public fun read(name: String): Read

    /** Replaces the whole value atomically. Returns `false` when the write failed. */
    public fun write(
        name: String,
        content: String,
    ): Boolean

    /** Returns `false` when the value existed and could not be deleted. */
    public fun delete(name: String): Boolean

    public sealed interface Read {
        /** Never written, or deleted. */
        public object Missing : Read

        /** Present but not readable (corrupt, Direct Boot, a newer format); never overwrite it. */
        public class Unreadable(
            public val cause: Exception,
        ) : Read

        public class Present(
            public val content: String,
        ) : Read
    }
}
