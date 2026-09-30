package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal

/**
 * Wall-clock time in epoch milliseconds. Event timestamps and persisted times (Event Model 5) come
 * from here, never from `System.currentTimeMillis()` directly, so tests control them.
 */
@RubiconInternal
public fun interface Clock {
    public fun currentTimeMillis(): Long

    public companion object {
        public val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

/**
 * Monotonic time in milliseconds, unaffected by changes to the wall clock. Measures waits and time
 * in the background within one process (Session 4, Retry 2).
 */
@RubiconInternal
public fun interface ElapsedClock {
    public fun elapsedRealtimeMillis(): Long

    public companion object {
        /** The JVM's monotonic clock; Android replaces it with `SystemClock.elapsedRealtime()`. */
        public val SYSTEM: ElapsedClock = ElapsedClock { System.nanoTime() / 1_000_000 }
    }
}
