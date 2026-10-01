package com.rubicon.analytics.internal.upload

import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PauseKind
import com.rubicon.analytics.internal.store.RetryState
import java.util.Locale
import kotlin.random.Random

/**
 * Decides what becomes of a batch after each upload attempt and when the next attempt may start
 * (Retry 1 to 5). One wait for the whole pipeline, since one batch is attempted at a time; failed
 * attempts per segment for the poison batch rule. The uploader owns the loop and the network check;
 * this class owns the rules.
 *
 * - Waits within a process run on the elapsed clock; the wall-clock end of a wait is kept in
 *   `DeviceStore` so a restart cannot bypass it, clamped when the clock was set back (Retry 5).
 * - Group B backs off along [BACKOFF_MS] with jitter x0.5..1.5 and resets on success (Retry 2).
 * - Group C honours `Retry-After`: at least 1 s, at most 1 h, jitter forward only x1.0..1.1, never
 *   past the hour; without the header it uses the table (Retry 3).
 * - Group E pauses one hour, jitter x0.8..1.2, and logs an ERROR once per code (Retry 1, 2).
 * - The network ending a wait only ends a group B wait caused by the network (Retry 2, 6).
 *
 * Called from the uploader only; not thread-safe.
 */
internal class RetryPolicy(
    private val deviceStore: DeviceStore,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
    private val random: Random,
    private val log: Log,
) {
    /** What the uploader does with the batch it just attempted. */
    enum class Decision {
        /** Accepted: delete it. */
        DELETE_SENT,

        /** Group D: delete it, counting its events as `rejected`. */
        DELETE_REJECTED,

        /** A poison batch: delete it, counting its events as `retry_exhausted`. */
        DELETE_EXHAUSTED,

        /** Keep it and wait for [waitMs]. */
        KEEP,
    }

    // The end of the current wait on the elapsed clock; 0 when there is none.
    private var deadline = 0L

    // Suspect segments that another batch overtook successfully in this process (Retry 3, step 3).
    private val proven = HashSet<String>()

    private val state: RetryState
        get() = deviceStore.state.retry

    /** Restores the stored wait at startup, after `DeviceStore.load` (Retry 5). */
    fun restore() {
        val stored = state
        val until = stored.pausedUntil ?: return
        val now = clock.currentTimeMillis()
        val longest = longestWait(stored.pauseKind)
        val remaining = (until - now).coerceAtMost(longest)
        if (until - now > longest) {
            // The clock was set back: the wait is cut to the longest of its kind, from now.
            update { it.copy(pausedUntil = now + longest) }
            log.debug { "A stored wait lay beyond the longest of its kind; it now ends in $longest ms" }
        }
        deadline = if (remaining > 0) elapsedClock.elapsedRealtimeMillis() + remaining else 0L
    }

    /**
     * Milliseconds until the next attempt may start; 0 when it may start now. A wait whose stored
     * state is gone, as after `clearLocalData()` (Privacy 3), has ended.
     */
    fun waitMs(): Long {
        if (deadline > 0L && state.pausedUntil == null) deadline = 0L
        return (deadline - elapsedClock.elapsedRealtimeMillis()).coerceAtLeast(0L)
    }

    /**
     * The order in which to try [segments] (oldest first): a segment that failed [SUSPECT_AFTER] times
     * in a row goes after the others, so the next batch is tried (Retry 3, step 1).
     */
    fun order(segments: List<String>): List<String> {
        val failures = state.batches
        val (suspects, healthy) = segments.partition { (failures[it] ?: 0) >= SUSPECT_AFTER }
        return healthy + suspects
    }

    /** Records the result of uploading [segment] and returns what to do with it. */
    fun onResult(
        segment: String,
        result: HttpResult,
    ): Decision =
        when (RetryGroup.of(result)) {
            RetryGroup.SUCCESS -> {
                // Every suspect that this batch overtook is now known to be the problem.
                proven += state.batches.filterValues { it >= SUSPECT_AFTER }.keys - segment
                deadline = 0L
                update { RetryState(null, null, 0, null, it.batches - segment) }
                Decision.DELETE_SENT
            }
            RetryGroup.BAD_BATCH -> {
                val code = (result as HttpResult.HttpError).code
                log.warn(Code.BATCH_REJECTED) { "The server rejected a batch with $code; it was deleted" }
                forget(segment)
                Decision.DELETE_REJECTED
            }
            RetryGroup.TRANSIENT -> transient(segment, result)
            RetryGroup.THROTTLE -> throttle(result as HttpResult.HttpError)
            RetryGroup.CONFIG -> config(result)
        }

    /** Ends a wait caused by the network when the network returns; the table position is kept (Retry 2). */
    fun onNetworkRegained() {
        val current = state
        if (current.pauseKind == PauseKind.TRANSIENT && current.lastFailure in NETWORK_FAILURES && waitMs() > 0) {
            deadline = 0L
            update { it.copy(pausedUntil = null) }
            log.debug { "The network returned; the wait after a network failure ends" }
        }
    }

    /** Forgets a segment deleted for any reason, so its attempt count does not outlive it. */
    fun forget(segment: String) {
        proven -= segment
        if (segment in state.batches) update { it.copy(batches = it.batches - segment) }
    }

    private fun transient(
        segment: String,
        result: HttpResult,
    ): Decision {
        val failures = (state.batches[segment] ?: 0) + 1
        if (failures >= EXHAUSTED_AFTER && segment in proven) {
            log.warn(Code.RETRY_EXHAUSTED) { "A batch failed $failures times while others were accepted; it was deleted" }
            forget(segment)
            // The server works, as other batches show: no wait before the next one.
            return Decision.DELETE_EXHAUSTED
        }
        val step = state.step + 1
        val wait = (BACKOFF_MS[(step - 1).coerceAtMost(BACKOFF_MS.size - 1)] * random.nextDouble(0.5, 1.5)).toLong()
        pause(wait, PauseKind.TRANSIENT, failureName(result)) { it.copy(step = step, batches = it.batches + (segment to failures)) }
        return Decision.KEEP
    }

    private fun throttle(result: HttpResult.HttpError): Decision {
        val retryAfter = result.retryAfterMs
        if (retryAfter == null) {
            val step = state.step + 1
            val wait = (BACKOFF_MS[(step - 1).coerceAtMost(BACKOFF_MS.size - 1)] * random.nextDouble(0.5, 1.5)).toLong()
            pause(wait, PauseKind.THROTTLE, failureName(result)) { it.copy(step = step) }
        } else {
            val asked = retryAfter.coerceIn(MIN_RETRY_AFTER_MS, MAX_WAIT_MS)
            // Never earlier than asked, never past the hour.
            val wait = (asked * random.nextDouble(1.0, 1.1)).toLong().coerceIn(asked, MAX_WAIT_MS)
            pause(wait, PauseKind.THROTTLE, failureName(result)) { it }
        }
        return Decision.KEEP
    }

    private fun config(result: HttpResult): Decision {
        logConfigError(result)
        val wait = (MAX_WAIT_MS * random.nextDouble(0.8, 1.2)).toLong()
        pause(wait, PauseKind.CONFIG, failureName(result)) { it }
        return Decision.KEEP
    }

    private inline fun pause(
        waitMs: Long,
        kind: PauseKind,
        failure: String,
        crossinline change: (RetryState) -> RetryState,
    ) {
        deadline = elapsedClock.elapsedRealtimeMillis() + waitMs
        val until = clock.currentTimeMillis() + waitMs
        update { change(it).copy(pausedUntil = until, pauseKind = kind, lastFailure = failure) }
        log.debug { "Upload failed ($failure); waiting $waitMs ms" }
    }

    // The transport already logged redirects (HTTP_REDIRECT), blocked hosts and unexpected errors.
    private fun logConfigError(result: HttpResult) {
        when (result) {
            is HttpResult.HttpError ->
                when (result.code) {
                    401, 403 ->
                        log.error(
                            Code.UPLOAD_UNAUTHORIZED,
                        ) { "The server rejected the API key (${result.code}); uploads pause for an hour" }
                    404, 410 ->
                        log.error(
                            Code.UPLOAD_ENDPOINT_MISSING,
                        ) { "The upload endpoint does not exist (${result.code}); check host" }
                    413 -> log.error(Code.UPLOAD_TOO_LARGE) { "A gateway rejected the request size (413); uploads pause for an hour" }
                    501, 505 -> log.error(Code.UPLOAD_NOT_SUPPORTED) { "The server does not support the request (${result.code})" }
                    else -> Unit
                }
            is HttpResult.NetworkFailure ->
                if (result.kind == FailureKind.TLS) {
                    log.error(Code.TLS_FAILED, result.cause) { "The TLS handshake failed; check the server certificate" }
                }
            is HttpResult.Success -> Unit
        }
    }

    private inline fun update(crossinline change: (RetryState) -> RetryState) {
        deviceStore.update { it.copy(retry = change(it.retry)) }
    }

    companion object {
        /** Waits of group B, by consecutive failure (Retry 2). */
        val BACKOFF_MS: LongArray = longArrayOf(2_000, 10_000, 30_000, 120_000, 300_000, 600_000)

        /** The longest wait of groups C and E, and the cap of `Retry-After` (Retry 2, 3). */
        const val MAX_WAIT_MS: Long = 3_600_000

        const val MIN_RETRY_AFTER_MS: Long = 1_000

        /** Consecutive group B failures before the next batch is tried first (Retry 3). */
        const val SUSPECT_AFTER: Int = 5

        /** Failures after which a batch others overtook is deleted (Retry 3). */
        const val EXHAUSTED_AFTER: Int = 10

        private val NETWORK_FAILURES = setOf("timeout", "connect", "io", "dns")

        /** The stored `last_failure`: a failure kind such as `timeout`, or `http_503`. */
        fun failureName(result: HttpResult): String =
            when (result) {
                is HttpResult.NetworkFailure -> result.kind.name.lowercase(Locale.ROOT)
                is HttpResult.HttpError -> "http_${result.code}"
                is HttpResult.Success -> "none"
            }

        /** The longest wait of a kind, for the clock check at restore (Retry 5). */
        fun longestWait(kind: PauseKind?): Long =
            when (kind) {
                PauseKind.THROTTLE, PauseKind.CONFIG -> MAX_WAIT_MS
                PauseKind.TRANSIENT, null -> BACKOFF_MS.last()
            }
    }
}
