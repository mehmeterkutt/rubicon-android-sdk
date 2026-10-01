package com.rubicon.analytics.internal.session

import com.rubicon.analytics.SessionContext
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.SessionState

/** An automatic session event for the pipeline to create (Session 5). */
internal sealed interface SessionEvent {
    /** `Session Started`: timestamp [session]'s `startedAt`, no properties. */
    class Started(
        val session: SessionState,
    ) : SessionEvent

    /**
     * `Session Ended`: backdated to [session]'s `lastActivity`, with `duration_ms`, in the ended
     * session. [identity] is the person it belonged to when a reset or a user switch ended it, so
     * it carries the previous `user_id` (Session 6); otherwise the current identity applies.
     */
    class Ended(
        val session: SessionState,
        val identity: IdentitySnapshot? = null,
    ) : SessionEvent {
        val durationMs: Long get() = (session.lastActivity - session.startedAt).coerceAtLeast(0)
    }
}

/** The session of an event and the session events to create right before it. */
internal class SessionOutcome(
    /** `null` for an event in the background with no valid session (Session 3). */
    val session: SessionContext?,
    val before: List<SessionEvent>,
)

/**
 * Sessions (Session 1 to 6): the foreground keeps a session alive, the background starts a
 * countdown of [backgroundTimeoutMs], and a session lasts at most [MAX_DURATION_MS].
 *
 * - No timers: a session's end is found when the app returns to the foreground, an event arrives,
 *   or the process starts (Session 4).
 * - A session starts with the first event in the foreground, and `Session Started` comes right
 *   before that event. Background events use a valid session but never extend it; after it ended
 *   they carry none and never start one.
 * - Time in the background is measured on the elapsed clock; across a restart only the wall clock
 *   is available, and a clock set back ends the session (Session 4, 6).
 * - The session lives in `PersonStore`: its id and start when it starts, its last activity on
 *   backgrounding and at most every [HEARTBEAT_MS] in the foreground, with each event (Session 6).
 *
 * Runs on the writer only: lifecycle changes and person changes reach it as writer messages, in
 * order with the events (Queue 1), so it needs no lock.
 *
 * @param emitEvents whether `Autocapture.SESSIONS` is on; session ids are kept either way.
 * @param requestPersist asks the writer to write `PersonStore`.
 */
internal class SessionManager(
    private val personStore: PersonStore,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
    private val backgroundTimeoutMs: Long,
    private val emitEvents: Boolean,
    private val newSessionId: () -> String,
    private val log: Log,
    private val requestPersist: () -> Unit,
) {
    private var active: SessionState? = null

    // On the elapsed clock: when the active session started, when the app went to the background
    // (null in the foreground), and when the last activity was last written.
    private var startedElapsed = 0L
    private var backgroundSince: Long? = 0L
    private var lastWrittenElapsed = Long.MIN_VALUE

    /** The process starts in the background until the lifecycle says otherwise (Session 3). */
    private val foreground: Boolean get() = backgroundSince == null

    /** Evaluates the saved session at process start, after `PersonStore.load` (Session 6). */
    fun start(): List<SessionEvent> {
        val saved = personStore.state.session ?: return emptyList()
        val now = clock.currentTimeMillis()
        val nowElapsed = elapsedClock.elapsedRealtimeMillis()
        val reason =
            when {
                now < saved.lastActivity -> "the clock was set back"
                now - saved.lastActivity >= backgroundTimeoutMs -> "the background timeout passed"
                now - saved.startedAt >= MAX_DURATION_MS -> "the maximum duration passed"
                else -> null
            }
        if (reason == null) {
            active = saved
            startedElapsed = nowElapsed - (now - saved.startedAt)
            backgroundSince = nowElapsed - (now - saved.lastActivity)
            log.debug { "Continuing the session of the previous process" }
            return emptyList()
        }
        log.debug { "The session of the previous process ended: $reason" }
        clearStored(saved)
        return ended(saved)
    }

    fun onForeground(): List<SessionEvent> {
        val nowElapsed = elapsedClock.elapsedRealtimeMillis()
        val events = endIfExpired(nowElapsed)
        backgroundSince = null
        return events
    }

    /** `ON_STOP`: the countdown starts and the last activity is written (Session 6). */
    fun onBackground() {
        if (!foreground) return
        backgroundSince = elapsedClock.elapsedRealtimeMillis()
        val session = active ?: return
        touch(session, clock.currentTimeMillis(), force = true)
    }

    /** The session for an event created at [timestamp], ending or starting one first if needed. */
    fun onEvent(timestamp: Long): SessionOutcome {
        val nowElapsed = elapsedClock.elapsedRealtimeMillis()
        val before = ArrayList<SessionEvent>(endIfExpired(nowElapsed))
        var session = active
        if (session == null && foreground) {
            session = SessionState(newSessionId(), timestamp, timestamp)
            active = session
            startedElapsed = nowElapsed
            lastWrittenElapsed = nowElapsed
            personStore.update { it.copy(session = session) }
            requestPersist()
            log.debug { "A new session started" }
            if (emitEvents) before += SessionEvent.Started(session)
        } else if (session != null && foreground) {
            touch(session, timestamp, force = false)
        }
        val current = active
        return SessionOutcome(current?.let { SessionContext(it.id, it.startedAt) }, before)
    }

    /**
     * `reset()` or a different user: the open session ends first, as the previous person's
     * (Session 6). The next event in the foreground starts a new one.
     */
    fun onPersonCleared(previous: IdentitySnapshot): List<SessionEvent> {
        val session = active ?: return emptyList()
        val last = if (foreground) maxOf(session.lastActivity, clock.currentTimeMillis()) else session.lastActivity
        active = null
        clearStored(session)
        return if (emitEvents) listOf(SessionEvent.Ended(session.copy(lastActivity = last), previous)) else emptyList()
    }

    /** Ends the open session without an event, for `optOut()` (Privacy 1). */
    fun endSilently() {
        val session = active ?: return
        active = null
        clearStored(session)
    }

    private fun endIfExpired(nowElapsed: Long): List<SessionEvent> {
        val session = active ?: return emptyList()
        val background = backgroundSince
        val timedOut = background != null && nowElapsed - background >= backgroundTimeoutMs
        val tooLong = nowElapsed - startedElapsed >= MAX_DURATION_MS
        if (!timedOut && !tooLong) return emptyList()
        active = null
        clearStored(session)
        return ended(session)
    }

    private fun ended(session: SessionState): List<SessionEvent> = if (emitEvents) listOf(SessionEvent.Ended(session)) else emptyList()

    // Moves the last activity forward; written at once on backgrounding, else at most every 30 s.
    private fun touch(
        session: SessionState,
        now: Long,
        force: Boolean,
    ) {
        val touched = session.copy(lastActivity = maxOf(session.lastActivity, now))
        active = touched
        val nowElapsed = elapsedClock.elapsedRealtimeMillis()
        if (force || nowElapsed - lastWrittenElapsed >= HEARTBEAT_MS) {
            lastWrittenElapsed = nowElapsed
            // Only the session this manager owns; a person cleared meanwhile keeps theirs cleared.
            personStore.update { if (it.session?.id == touched.id) it.copy(session = touched) else it }
            requestPersist()
        }
    }

    private fun clearStored(session: SessionState) {
        personStore.update { if (it.session?.id == session.id) it.copy(session = null) else it }
        requestPersist()
    }

    companion object {
        /** Session 4: internal, not configurable. */
        const val MAX_DURATION_MS: Long = 12 * 60 * 60 * 1000L

        /** Session 6: the last activity is written at most this often in the foreground. */
        const val HEARTBEAT_MS: Long = 30_000L
    }
}
