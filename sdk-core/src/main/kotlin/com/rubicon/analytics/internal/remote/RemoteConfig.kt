package com.rubicon.analytics.internal.remote

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.config.ConfigValidator
import com.rubicon.analytics.internal.json.Json
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import java.util.Locale

/**
 * The remote configuration after validation against the code's own values (Configuration 1): it can
 * only restrict. `null` and empty mean "no remote value". The contract is `docs/remote-config.md`.
 */
internal data class RemoteConfig(
    /** `false` is the kill switch; `true` changes nothing. */
    val enabled: Boolean = true,
    val autocaptureOff: Set<Autocapture> = emptySet(),
    val flushAt: Int? = null,
    val flushIntervalMs: Long? = null,
    /** Share of devices that collect at all, by `device_id`. */
    val sampleRate: Double? = null,
    /** Share of devices that send `SDK Diagnostics`, by `device_id` (Logging 5). */
    val diagnosticsSampleRate: Double? = null,
) {
    /** [code] restricted by these values; the snapshot the SDK runs with until the next launch. */
    fun restrict(code: ConfigSnapshot): ConfigSnapshot =
        code.copy(
            autocapture = code.autocapture - autocaptureOff,
            flushAt = flushAt ?: code.flushAt,
            flushIntervalMs = flushIntervalMs ?: code.flushIntervalMs,
        )

    companion object {
        val NONE = RemoteConfig()

        const val ENABLED = "enabled"
        const val AUTOCAPTURE_OFF = "autocapture_off"
        const val FLUSH_AT = "flush_at"
        const val FLUSH_INTERVAL_MS = "flush_interval_ms"
        const val SAMPLE_RATE = "sample_rate"
        const val DIAGNOSTICS_SAMPLE_RATE = "diagnostics_sample_rate"

        /** The response body as a map, or `null` when it is not one JSON object. */
        fun parseBody(body: String): Map<String, Any?>? = Json.readObject(body)?.toMap()

        /**
         * Validates [values], a stored or fetched response, against [code]. A known key with an
         * invalid value, or one that would widen what code allows, is ignored with a WARN; an unknown
         * key or autocapture type, perhaps for a newer SDK, is ignored at DEBUG.
         */
        fun from(
            values: Map<String, Any?>,
            code: ConfigSnapshot,
            log: Log,
        ): RemoteConfig {
            var config = NONE
            for ((key, value) in values) {
                config =
                    when (key) {
                        ENABLED -> (value as? Boolean)?.let { config.copy(enabled = it) }
                        AUTOCAPTURE_OFF -> autocaptureOff(value, log)?.let { config.copy(autocaptureOff = it) }
                        FLUSH_AT ->
                            (value as? Int)
                                ?.takeIf { it in ConfigValidator.FLUSH_AT_BOUNDS && it >= code.flushAt }
                                ?.let { config.copy(flushAt = it) }
                        FLUSH_INTERVAL_MS ->
                            wholeNumber(value)
                                ?.takeIf { it in ConfigValidator.FLUSH_INTERVAL_MS_BOUNDS && it >= code.flushIntervalMs }
                                ?.let { config.copy(flushIntervalMs = it) }
                        SAMPLE_RATE -> rate(value)?.let { config.copy(sampleRate = it) }
                        DIAGNOSTICS_SAMPLE_RATE ->
                            // With sendDiagnostics off in code, any rate above 0 would widen it.
                            rate(value)?.takeIf { code.sendDiagnostics || it == 0.0 }?.let { config.copy(diagnosticsSampleRate = it) }
                        else -> {
                            log.debug { "Remote configuration: ignored the unknown key \"$key\"" }
                            config
                        }
                    } ?: config.also { invalid(key, log) }
            }
            return config
        }

        private fun invalid(
            key: String,
            log: Log,
        ) {
            log.warn(Code.REMOTE_CONFIG_INVALID_VALUE, repeatKey = key) {
                "Remote configuration: \"$key\" is invalid or would widen what the app's code allows; it is ignored"
            }
        }

        private fun autocaptureOff(
            value: Any?,
            log: Log,
        ): Set<Autocapture>? {
            val names = value as? List<*> ?: return null
            if (names.any { it !is String }) return null
            val off = LinkedHashSet<Autocapture>()
            for (name in names) {
                val type = Autocapture.entries.find { it.name.lowercase(Locale.ROOT) == name }
                if (type == null) log.debug { "Remote configuration: ignored the unknown autocapture type \"$name\"" } else off += type
            }
            return off
        }

        private fun wholeNumber(value: Any?): Long? =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }

        private fun rate(value: Any?): Double? =
            when (value) {
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is Double -> value
                else -> null
            }?.takeIf { it in 0.0..1.0 }
    }
}
