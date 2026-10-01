package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.internal.store.DiagnosticsState
import com.rubicon.analytics.internal.store.isEmpty
import com.rubicon.analytics.internal.util.IsoDates

/**
 * The `SDK Diagnostics` event (Logging 5): the SDK's health as counters, at most once an hour and
 * only when there is something to report. No text, values or stack traces.
 *
 * ```
 * { "period_start": "2026-10-13T08:00:00.000Z",
 *   "dropped": { "total": 1240, "queue_full": 1200, "expired": 37, "before_send": 3 },
 *   "issues":  { "HOST_BLOCKED": 12 } }
 * ```
 */
internal object Diagnostics {
    const val EVENT_NAME: String = "SDK Diagnostics"

    /** At most one event per hour (Logging 5). */
    const val INTERVAL_MS: Long = 60 * 60 * 1000L

    /**
     * Whether the event is due at [now]: something was counted and an hour has passed since the
     * last one. A last time in the future, after the clock was set back, counts as now.
     */
    fun isDue(
        state: DiagnosticsState,
        now: Long,
    ): Boolean {
        if (state.isEmpty) return false
        val last = state.lastSentAt ?: return true
        return now - minOf(last, now) >= INTERVAL_MS
    }

    /** The event's properties for [state], counted since [DiagnosticsState.periodStart]. */
    fun properties(
        state: DiagnosticsState,
        now: Long,
    ): Map<String, Any?> {
        val dropped = LinkedHashMap<String, Any?>()
        dropped["total"] = state.dropped.values.fold(0L) { sum, n -> sum + n }
        dropped.putAll(state.dropped.toSortedMap())
        return linkedMapOf(
            "period_start" to IsoDates.format(state.periodStart ?: now),
            "dropped" to dropped,
            "issues" to state.issues.toSortedMap().toMap(),
        )
    }
}
