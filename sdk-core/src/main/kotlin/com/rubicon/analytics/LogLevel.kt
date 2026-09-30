package com.rubicon.analytics

/**
 * How much the SDK logs, set with `logging.level` (default [WARN]). A level includes every level
 * after it: [WARN] also logs [ERROR]. The meaning of each level is a contract:
 *
 * ```
 * AnalyticsConfig(key).apply {
 *     if (BuildConfig.DEBUG) logging.level = LogLevel.DEBUG
 * }
 * ```
 */
public enum class LogLevel {
    /** Every step: an event queued, a flush started, a segment closed, a wait after a failure. */
    DEBUG,

    /** A few lifecycle lines, such as the host in use or a consent change. */
    INFO,

    /**
     * A misconfiguration, or data that was dropped or changed, such as a missing permission, a
     * dropped property or a full queue. A healthy app logs nothing at this level.
     */
    WARN,

    /** Data is lost or the SDK cannot work, such as a failed write or an invalid API key. */
    ERROR,

    /** Nothing is logged. */
    OFF,
}
