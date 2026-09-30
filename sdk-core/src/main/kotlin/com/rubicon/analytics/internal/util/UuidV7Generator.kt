package com.rubicon.analytics.internal.util

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.platform.Clock
import kotlin.random.Random

/**
 * Makes UUIDv7 strings that sort in creation order within the process (Event Model 4), for
 * `message_id`, `device_id` and the session id. One generator per `Analytics` instance.
 *
 * Layout (RFC 9562, section 5.7): 48 bits of Unix time in milliseconds, the version `7`, then 74
 * bits that hold a 42-bit counter (the 12 bits of `rand_a` and the top 30 bits of `rand_b`, RFC 9562
 * section 6.2, method 1) followed by 32 random bits, with the variant `10` in between.
 *
 * - The counter starts at a random value with its top bit clear in each new millisecond and grows
 *   by one per id, so ids of the same millisecond are ordered and about 2^41 of them fit.
 * - If the clock moves back, the last timestamp is kept and the counter keeps growing, so order holds.
 * - If the counter runs out, the timestamp moves one millisecond ahead (RFC 9562, section 6.2).
 *
 * Thread-safe; [next] takes a lock for a few arithmetic operations and random draws.
 */
@RubiconInternal
public class UuidV7Generator internal constructor(
    private val clock: Clock,
    private val random: Random,
    // Tests lower it to reach the overflow path; ids then keep the 42-bit layout.
    private val counterMax: Long,
) {
    public constructor(clock: Clock, random: Random) : this(clock, random, COUNTER_MAX)

    // A fresh counter uses the lower half of the range, leaving at least half for increments.
    private val seedMask = counterMax ushr 1
    private val lock = Any()
    private var lastMillis = Long.MIN_VALUE
    private var counter = 0L

    public fun next(): String {
        val millis: Long
        val count: Long
        val tail: Long
        // The platform's Random is shared and not thread-safe, so every draw happens under the lock.
        synchronized(lock) {
            val now = clock.currentTimeMillis()
            if (now > lastMillis) {
                lastMillis = now
                counter = random.nextLong() and seedMask
            } else if (counter == counterMax) {
                lastMillis += 1
                counter = random.nextLong() and seedMask
            } else {
                counter += 1
            }
            millis = lastMillis
            count = counter
            tail = random.nextInt().toLong() and 0xFFFF_FFFFL
        }
        // unix_ts_ms (48) | ver (4) | counter high 12 bits
        val msb = ((millis and TIMESTAMP_MASK) shl 16) or (0x7L shl 12) or (count ushr 30)
        // var (2) | counter low 30 bits | 32 random bits
        val lsb = (0b10L shl 62) or ((count and 0x3FFF_FFFFL) shl 32) or tail
        return format(msb, lsb)
    }

    private companion object {
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val COUNTER_MAX = (1L shl 42) - 1

        private val HEX = "0123456789abcdef".toCharArray()

        fun format(
            msb: Long,
            lsb: Long,
        ): String {
            val out = CharArray(36)
            var pos = 0
            for (i in 0 until 32) {
                if (i == 8 || i == 12 || i == 16 || i == 20) out[pos++] = '-'
                val word = if (i < 16) msb else lsb
                val shift = (15 - i % 16) * 4
                out[pos++] = HEX[((word ushr shift) and 0xF).toInt()]
            }
            return String(out)
        }
    }
}
