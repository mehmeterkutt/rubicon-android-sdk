package com.rubicon.analytics.internal.remote

import com.rubicon.analytics.internal.util.Hashing

/**
 * Deterministic sampling by `device_id` (Configuration 1, Logging 5): a device is always in or always
 * out for a rate, so it does not flicker between launches. Each purpose has its own [Purpose.salt], so
 * a device out of one sample is not more likely to be out of another. A new `device_id`, after
 * `clearLocalData()`, draws again.
 */
internal object Sampling {
    enum class Purpose(
        val salt: String,
    ) {
        /** `sample_rate`: whether the device collects at all. */
        EVENTS("events"),

        /** `diagnostics_sample_rate`: whether the device sends `SDK Diagnostics`. */
        DIAGNOSTICS("diagnostics"),
    }

    /** Whether [deviceId] is inside a sample of [rate], 0 to 1. */
    fun isIn(
        deviceId: String,
        rate: Double,
        purpose: Purpose,
    ): Boolean =
        when {
            rate >= 1.0 -> true
            rate <= 0.0 -> false
            else -> bucket(deviceId, purpose) < rate
        }

    /** A uniform position in `[0, 1)` from the first 52 bits of SHA-256 of the salted id. */
    fun bucket(
        deviceId: String,
        purpose: Purpose,
    ): Double {
        val bits = Hashing.sha256Hex("${purpose.salt}:$deviceId").take(13).toLong(16)
        return bits.toDouble() / (1L shl 52).toDouble()
    }
}
