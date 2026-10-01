package com.rubicon.analytics.internal.config

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.Consent
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconLogger

/**
 * The validated, immutable copy of an `AnalyticsConfig` that the SDK reads (Configuration 4). Taken
 * once by the factory; later changes to the config object never reach it. Numbers are within their
 * bounds, the host is normalized and reserved headers are removed.
 */
internal data class ConfigSnapshot(
    val apiKey: String,
    val host: String,
    val debug: Boolean,
    val flushAt: Int,
    val flushIntervalMs: Long,
    val immediateEvents: Set<String>,
    val sessionBackgroundTimeoutMs: Long,
    val autocapture: Set<Autocapture>,
    val disabledFields: Set<ContextField>,
    val defaultConsent: Consent,
    val persistConsent: Boolean,
    val logLevel: LogLevel,
    val logger: RubiconLogger?,
    val sendDiagnostics: Boolean,
    val headers: Map<String, String>,
) {
    val batchUrl: String get() = "$host/v1/batch"

    val configUrl: String get() = "$host/v1/config"

    // Never prints the API key or header values (Logging 4a).
    override fun toString(): String = "ConfigSnapshot(host=$host, debug=$debug)"
}
