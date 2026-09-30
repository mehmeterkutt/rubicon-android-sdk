package com.rubicon.analytics.internal.log

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.RubiconLogger
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * The only way SDK code logs (Logging 1 to 4). One per `Analytics` instance.
 *
 * - The level is checked before the message lambda runs; the functions are `inline`, so a disabled
 *   line costs one comparison and allocates nothing (Logging 3).
 * - Every WARN and ERROR has a [Code]. Each occurrence increments the code's counter in [issues],
 *   whatever the level; only the first occurrence in the process is logged at its own level, later
 *   ones at DEBUG (Logging 2). A `repeatKey` narrows that to the first occurrence per key, for the
 *   lines a decision logs "once per key" (Serialization 3).
 * - Lines go to the developer's [logger], or to [platformLogger] when there is none, through a
 *   [CallbackGuard] (Logging 4b).
 *
 * Messages name keys, types, counts and codes, never values or ids (Logging 4a):
 *
 * ```
 * log.warn(Code.PROPERTY_DROPPED) { "Dropped property \"$key\": unsupported type ${type.simpleName}" }
 * ```
 *
 * @param platformLogger Logcat on Android, nothing on the JVM; also the fallback for a logger that
 *   throws.
 */
@RubiconInternal
public class Log(
    public val level: LogLevel,
    logger: RubiconLogger?,
    platformLogger: RubiconLogger,
    private val issues: IssueCounter = IssueCounter.NONE,
) {
    public val callbacks: CallbackGuard = CallbackGuard(logger ?: platformLogger, platformLogger)

    private val reported = AtomicIntegerArray(Code.entries.size)

    // ConcurrentHashMap.newKeySet needs API 24.
    private val reportedKeys: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        callbacks.onLoggerFailed = { t ->
            warn(Code.LOGGER_THREW, t) { "Custom logger threw; falling back to the default logger" }
        }
        callbacks.onObserverFailed = { t ->
            error(Code.OBSERVER_THREW, t) { "Event observer threw; it is switched off" }
        }
    }

    public fun isEnabled(level: LogLevel): Boolean = level != LogLevel.OFF && level >= this.level

    public inline fun debug(message: () -> String) {
        if (isEnabled(LogLevel.DEBUG)) write(LogLevel.DEBUG, message(), null)
    }

    public inline fun info(message: () -> String) {
        if (isEnabled(LogLevel.INFO)) write(LogLevel.INFO, message(), null)
    }

    public inline fun warn(
        code: Code,
        throwable: Throwable? = null,
        repeatKey: String? = null,
        message: () -> String,
    ) {
        val effective = report(code, LogLevel.WARN, repeatKey)
        if (effective != null) write(effective, "${code.name}: ${message()}", throwable)
    }

    public inline fun error(
        code: Code,
        throwable: Throwable? = null,
        repeatKey: String? = null,
        message: () -> String,
    ) {
        val effective = report(code, LogLevel.ERROR, repeatKey)
        if (effective != null) write(effective, "${code.name}: ${message()}", throwable)
    }

    /**
     * Counts [code] and returns the level to log it at: [level] the first time in this process (per
     * [repeatKey], when given), [LogLevel.DEBUG] afterwards; `null` when that level is disabled.
     */
    public fun report(
        code: Code,
        level: LogLevel,
        repeatKey: String? = null,
    ): LogLevel? {
        issues.increment(code)
        val first =
            if (repeatKey == null) {
                reported.compareAndSet(code.ordinal, 0, 1)
            } else {
                reportedKeys.add("${code.name}:$repeatKey")
            }
        val effective = if (first) level else LogLevel.DEBUG
        return effective.takeIf { isEnabled(it) }
    }

    public fun write(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        callbacks.log(level, message, throwable)
    }
}

/** Counts every WARN and ERROR by code for `SDK Diagnostics` (Logging 5); stored from step 4.1. */
@RubiconInternal
public fun interface IssueCounter {
    public fun increment(code: Code)

    public companion object {
        public val NONE: IssueCounter = IssueCounter { }
    }
}

/** The core's default logger: logs nothing, so JVM code never touches Android (Logging 1). */
@RubiconInternal
public object NoOpLogger : RubiconLogger {
    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    ): Unit = Unit
}
