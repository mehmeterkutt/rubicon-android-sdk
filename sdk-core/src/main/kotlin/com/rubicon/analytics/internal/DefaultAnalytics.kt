package com.rubicon.analytics.internal

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.identity.Identity
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.pipeline.CapturedEvent
import com.rubicon.analytics.internal.pipeline.Writer
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.privacy.ConsentController
import com.rubicon.analytics.internal.privacy.ConsentState
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.validation.PropertyNormalizer
import com.rubicon.analytics.internal.validation.PropertyProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * The real [Analytics] (Public API 2): the calling thread only validates, copies and snapshots, and
 * hands the rest to the writer (Public API 9).
 *
 * Every entry point catches what it does not expect, logs it and carries on: the SDK failing is an
 * analytics gap, not an app crash. With `debug` on, the developer mistakes the decisions name throw:
 * an invalid name, user id or property, a setting changed after creation.
 */
internal class DefaultAnalytics(
    private val code: ConfigSnapshot,
    private val platform: Platform,
    private val log: Log,
    private val identity: Identity,
    private val consent: ConsentController,
    private val flags: RuntimeFlags,
    private val writer: Writer,
    private val newId: () -> String,
    private val scope: CoroutineScope,
) : Analytics {
    override fun track(
        name: String,
        properties: Map<String, Any?>,
    ): Unit = entry { capture(CapturedEvent.Type.TRACK, name, properties) }

    override fun screen(
        name: String,
        properties: Map<String, Any?>,
    ): Unit = entry { capture(CapturedEvent.Type.SCREEN, name, properties) }

    override fun identify(
        userId: String,
        traits: Map<String, Any?>,
    ): Unit =
        entry {
            val ticket = identity.identify(userId, traits) ?: return@entry
            if (!creating) return@entry
            writer.enqueue(
                CapturedEvent(
                    CapturedEvent.Type.IDENTIFY,
                    null,
                    emptyMap(),
                    EventSource.APP,
                    newId(),
                    platform.clock.currentTimeMillis(),
                    identity.snapshot(),
                    platform.contextProvider.callTimeValues(code.disabledFields),
                    ticket = ticket,
                ),
            )
        }

    override fun reset(): Unit = entry { identity.reset() }

    override fun register(
        key: String,
        value: Any?,
    ): Unit = entry { identity.register(key, value) }

    override fun unregister(key: String): Unit = entry { identity.unregister(key) }

    override fun flush(): Unit = entry { writer.flush() }

    override fun optOut(): Unit = entry { consent.optOut() }

    override fun optIn(): Unit = entry { consent.optIn() }

    override fun isOptedOut(): Boolean =
        try {
            consent.isOptedOut()
        } catch (t: Throwable) {
            failed(t)
            false
        }

    override fun clearLocalData(): Unit =
        entry {
            identity.clearLocalData(newId())
            writer.clearLocalData()
        }

    override val deviceId: String
        get() =
            try {
                identity.deviceId
            } catch (t: Throwable) {
                failed(t)
                ""
            }

    /**
     * Asks for a flush and waits until nothing is pending or [timeoutMs] passed; `true` when
     * everything was sent. For tests and shutdown only, never public (Public API 9).
     */
    fun flushBlocking(timeoutMs: Long): Boolean {
        writer.flush()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (platform.eventStore.pendingCount == 0 && writer.isIdle) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /**
     * Stops the writer and the uploader and waits for them; for tests, to start another instance on
     * the same files. A request in flight finishes first (at most the 45 s watchdog, HTTP 3).
     */
    fun shutdown() {
        val job = scope.coroutineContext[Job] ?: return
        runBlocking { job.cancelAndJoin() }
    }

    // Whether new events may be created now: not while opted out or switched off (Privacy 1,
    // Configuration 1). Before the stored choice is known the writer decides.
    private val creating: Boolean
        get() = flags.collecting && consent.state != ConsentState.DENIED

    private fun capture(
        type: CapturedEvent.Type,
        name: String,
        properties: Map<String, Any?>,
    ) {
        PropertyNormalizer.checkName(name)?.let {
            if (code.debug) throw IllegalArgumentException(it.message)
            log.warn(Code.INVALID_EVENT_NAME) { it.message }
            return
        }
        if (!creating) return
        val normalized = PropertyNormalizer.normalize(properties)
        PropertyProblem.report(normalized.problems, code.debug, log)
        writer.enqueue(
            CapturedEvent(
                type,
                name,
                normalized.values,
                EventSource.APP,
                newId(),
                platform.clock.currentTimeMillis(),
                identity.snapshot(),
                platform.contextProvider.callTimeValues(code.disabledFields),
            ),
        )
    }

    private inline fun entry(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            if (code.debug) throw e
            failed(e)
        } catch (e: IllegalStateException) {
            if (code.debug) throw e
            failed(e)
        } catch (t: Throwable) {
            // Never crash the host app (CLAUDE.md, non-negotiable 1).
            failed(t)
        }
    }

    private fun failed(t: Throwable) {
        log.error(Code.WORKER_FAILED, t) { "A call failed on ${t.javaClass.name}; it was ignored" }
    }

    private companion object {
        const val POLL_MS = 10L
    }
}
