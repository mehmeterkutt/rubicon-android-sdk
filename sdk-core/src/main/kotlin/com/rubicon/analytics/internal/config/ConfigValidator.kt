package com.rubicon.analytics.internal.config

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import java.util.Collections
import java.util.EnumSet
import java.util.Locale

/**
 * Validates an `AnalyticsConfig` and takes its snapshot (Configuration 3). Validation itself never
 * throws: it returns the snapshot, or `null` for an unrecoverable configuration, and the problems
 * it found. [Result.report] then throws in debug or logs in release, and [freeze] freezes the
 * config object.
 */
internal object ConfigValidator {
    val FLUSH_AT_BOUNDS: IntRange = 1..100
    val FLUSH_INTERVAL_MS_BOUNDS: LongRange = 1_000L..600_000L
    val BACKGROUND_TIMEOUT_MS_BOUNDS: LongRange = 10_000L..7_200_000L

    /** Headers the SDK sets itself; names compare in any letter case (Configuration 5, HTTP 5). */
    private val RESERVED_HEADERS =
        setOf("authorization", "content-type", "content-encoding", "content-length", "user-agent", "host", "if-none-match")
    private const val RESERVED_PREFIX = "x-rubicon-"

    class Issue(
        val code: Code,
        val level: LogLevel,
        val throwsInDebug: Boolean,
        val message: String,
    )

    class Result(
        /** `null` when the SDK must not start: a blank key or an invalid host. */
        val snapshot: ConfigSnapshot?,
        val issues: List<Issue>,
    ) {
        /**
         * Logs the issues with their codes, or, when the config's `debug` is on, throws
         * [IllegalArgumentException] for the first one that throws in debug.
         */
        fun report(
            debug: Boolean,
            log: Log,
        ) {
            for (issue in issues) {
                if (debug && issue.throwsInDebug) throw IllegalArgumentException(issue.message)
                when (issue.level) {
                    LogLevel.ERROR -> log.error(issue.code) { issue.message }
                    else -> log.warn(issue.code) { issue.message }
                }
            }
        }
    }

    fun validate(config: AnalyticsConfig): Result {
        val issues = mutableListOf<Issue>()

        val apiKeyValid = config.apiKey.isNotBlank()
        if (!apiKeyValid) {
            issues += Issue(Code.INVALID_API_KEY, LogLevel.ERROR, true, "The API key is blank; the SDK does nothing.")
        }
        val host = Hosts.normalize(config.host)
        if (host == null) {
            issues +=
                Issue(
                    Code.INVALID_HOST,
                    LogLevel.ERROR,
                    true,
                    "host \"${config.host}\" is not an http or https base URL; the SDK does nothing.",
                )
        } else if (Hosts.isCleartext(host)) {
            issues += Issue(Code.CLEARTEXT_HOST, LogLevel.WARN, false, "host \"$host\" uses http://; events are sent unencrypted.")
        }

        val flushAt = clamp("flush.at", config.flush.at, FLUSH_AT_BOUNDS, issues)
        val flushIntervalMs = clamp("flush.intervalMs", config.flush.intervalMs, FLUSH_INTERVAL_MS_BOUNDS, issues)
        val backgroundTimeoutMs =
            clamp("session.backgroundTimeoutMs", config.session.backgroundTimeoutMs, BACKGROUND_TIMEOUT_MS_BOUNDS, issues)
        val headers = allowedHeaders(config.network.headers, issues)

        val snapshot =
            if (apiKeyValid && host != null) {
                ConfigSnapshot(
                    apiKey = config.apiKey,
                    host = host,
                    debug = config.debug,
                    beforeSend = Collections.unmodifiableList(ArrayList(config.beforeSend)),
                    flushAt = flushAt,
                    flushIntervalMs = flushIntervalMs,
                    immediateEvents = Collections.unmodifiableSet(LinkedHashSet(config.flush.immediateEvents)),
                    sessionBackgroundTimeoutMs = backgroundTimeoutMs,
                    autocapture = Collections.unmodifiableSet(enumSetOf(config.autocapture.enabled)),
                    disabledFields = Collections.unmodifiableSet(enumSetOf(config.context.disabledFields)),
                    defaultConsent = config.privacy.defaultConsent,
                    persistConsent = config.privacy.persistConsent,
                    logLevel = config.logging.level,
                    logger = config.logging.logger,
                    eventObserver = config.logging.eventObserver,
                    sendDiagnostics = config.logging.sendDiagnostics,
                    headers = headers,
                )
            } else {
                null
            }
        return Result(snapshot, issues)
    }

    /**
     * Freezes [config] (Configuration 4): from now on a write throws [IllegalStateException] in
     * debug, or logs `CONFIG_FROZEN` (once per process) and is ignored.
     */
    fun freeze(
        config: AnalyticsConfig,
        debug: Boolean,
        log: Log,
    ) {
        config.freezer.freeze { setting ->
            val message = "AnalyticsConfig.$setting was changed after the SDK was created; the change is ignored."
            if (debug) throw IllegalStateException(message)
            log.warn(Code.CONFIG_FROZEN) { message }
        }
    }

    private fun clamp(
        name: String,
        value: Int,
        bounds: IntRange,
        issues: MutableList<Issue>,
    ): Int = clamp(name, value.toLong(), bounds.first.toLong()..bounds.last.toLong(), issues).toInt()

    private fun clamp(
        name: String,
        value: Long,
        bounds: LongRange,
        issues: MutableList<Issue>,
    ): Long {
        if (value in bounds) return value
        val clamped = value.coerceIn(bounds)
        issues +=
            Issue(
                Code.SETTING_OUT_OF_BOUNDS,
                LogLevel.WARN,
                true,
                "$name is $value, outside ${bounds.first} to ${bounds.last}; $clamped is used.",
            )
        return clamped
    }

    // Header names are logged, never values (Logging 4a).
    private fun allowedHeaders(
        headers: Map<String, String>,
        issues: MutableList<Issue>,
    ): Map<String, String> {
        val allowed = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val lower = name.lowercase(Locale.ROOT)
            if (lower in RESERVED_HEADERS || lower.startsWith(RESERVED_PREFIX)) {
                issues +=
                    Issue(
                        Code.RESERVED_HEADER,
                        LogLevel.WARN,
                        true,
                        "network.headers: \"$name\" is set by the SDK and cannot be replaced; it is ignored.",
                    )
            } else {
                allowed[name] = value
            }
        }
        return Collections.unmodifiableMap(allowed)
    }

    private inline fun <reified E : Enum<E>> enumSetOf(values: Set<E>): EnumSet<E> = EnumSet.noneOf(E::class.java).apply { addAll(values) }
}
