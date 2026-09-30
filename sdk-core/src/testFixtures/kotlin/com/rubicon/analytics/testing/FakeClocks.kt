package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** A wall clock that only moves when the test moves it, forwards or backwards. */
public class FakeClock(
    start: Long = DEFAULT_START,
) : Clock {
    private val now = AtomicLong(start)

    override fun currentTimeMillis(): Long = now.get()

    public fun set(millis: Long) {
        now.set(millis)
    }

    /** A negative [millis] moves the clock back, as a user changing the device time does. */
    public fun advanceBy(millis: Long) {
        now.addAndGet(millis)
    }

    public companion object {
        /** 2025-09-29T12:00:00.000Z. */
        public const val DEFAULT_START: Long = 1_759_147_200_000L
    }
}

/** A monotonic clock that only moves forwards, when the test moves it. */
public class FakeElapsedClock(
    start: Long = DEFAULT_START,
) : ElapsedClock {
    private val now = AtomicLong(start)

    override fun elapsedRealtimeMillis(): Long = now.get()

    public fun advanceBy(millis: Long) {
        require(millis >= 0) { "An elapsed clock never moves back" }
        now.addAndGet(millis)
    }

    public companion object {
        /** An arbitrary uptime; a real device's is rarely zero. */
        public const val DEFAULT_START: Long = 1_000_000L
    }
}

/** A [Random] that produces the same sequence for the same [seed], for reproducible tests. */
public class SeededRandom(
    public val seed: Long = 42L,
) : Random() {
    private val delegate = Random(seed)

    override fun nextBits(bitCount: Int): Int = delegate.nextBits(bitCount)
}
