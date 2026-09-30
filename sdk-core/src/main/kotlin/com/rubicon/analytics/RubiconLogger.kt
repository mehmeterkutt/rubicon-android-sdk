package com.rubicon.analytics

/**
 * Receives the SDK's log lines, set with `logging.logger`. When no logger is set, Android writes to
 * Logcat with the tag `Rubicon`; a logger you set is never replaced.
 *
 * Only lines that pass `logging.level` reach the logger, so it needs no level logic. It is called
 * from the SDK's worker threads and sometimes from the thread that called the SDK: keep it fast and
 * thread-safe, and do not call the SDK from it (such calls log nothing). If it throws, the SDK
 * falls back to its default logger for the rest of the process.
 *
 * Messages never contain property or trait values, user or device ids, the API key, headers,
 * bodies or query strings.
 *
 * ```
 * logging.logger = RubiconLogger { level, message, throwable ->
 *     if (level == LogLevel.ERROR) crashReporter.log("Rubicon: $message")
 * }
 * ```
 */
public fun interface RubiconLogger {
    /** @param throwable the cause of an error line, if any. */
    public fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    )
}
