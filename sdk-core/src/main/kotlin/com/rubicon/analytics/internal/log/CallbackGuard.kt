package com.rubicon.analytics.internal.log

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.RubiconLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Calls the developer's logger and event observer so that neither can break the SDK (Logging 4b):
 *
 * - a logger that throws is replaced by [fallback] for the rest of the process, and
 *   [onLoggerFailed] is told once;
 * - an observer that throws is switched off for the rest of the process, and [onObserverFailed] is
 *   told once;
 * - a log line or observer update caused on the same thread by one of these callbacks, for example
 *   by the logger calling the SDK, is ignored, which prevents endless loops.
 */
@RubiconInternal
public class CallbackGuard(
    logger: RubiconLogger,
    private val fallback: RubiconLogger,
) {
    /** Called once, after the logger threw and was replaced; its log lines go to [fallback]. */
    public var onLoggerFailed: (Throwable) -> Unit = {}

    /** Called once, after the observer threw and was switched off. */
    public var onObserverFailed: (Throwable) -> Unit = {}

    @Volatile
    private var logger: RubiconLogger = logger
    private val loggerReplaced = AtomicBoolean(false)
    private val observerFailed = AtomicBoolean(false)

    // Set while this thread runs a callback. ThreadLocal.withInitial needs API 26, so null means no.
    private val inCallback = ThreadLocal<Boolean>()

    /** Whether the current thread is inside the logger or the observer. */
    public val isInCallback: Boolean
        get() = inCallback.get() == true

    public val isObserverEnabled: Boolean
        get() = !observerFailed.get()

    public fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        val current = logger
        val failure = runCallback { current.log(level, message, throwable) } ?: return
        // Only the first failure of the developer's logger switches over; a failing fallback has
        // nowhere left to write.
        if (current !== fallback && loggerReplaced.compareAndSet(false, true)) {
            logger = fallback
            onLoggerFailed(failure)
            log(level, message, throwable)
        }
    }

    /** Runs [update], which calls the observer, unless the observer was switched off. */
    public fun notifyObserver(update: () -> Unit) {
        if (observerFailed.get()) return
        val failure = runCallback(update) ?: return
        if (observerFailed.compareAndSet(false, true)) onObserverFailed(failure)
    }

    // Returns what the callback threw, or null. Re-entrant calls are skipped and return null.
    private fun runCallback(callback: () -> Unit): Throwable? {
        if (isInCallback) return null
        inCallback.set(true)
        return try {
            callback()
            null
        } catch (t: Throwable) {
            t
        } finally {
            inCallback.remove()
        }
    }
}
