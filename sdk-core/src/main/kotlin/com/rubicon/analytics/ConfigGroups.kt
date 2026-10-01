package com.rubicon.analytics

import com.rubicon.analytics.internal.config.Freezer

/**
 * When queued events are uploaded (Queue 2, 3, 7). Held by [AnalyticsConfig.flush].
 *
 * The defaults are a starting point and may be tuned in minor releases. Remote configuration can
 * only raise [at] and [intervalMs], within their bounds.
 */
public class FlushConfig internal constructor(
    freezer: Freezer,
) {
    /**
     * Upload when this many events are waiting on the device. Default 30; from 1 to 100. A value out
     * of bounds throws when `debug` is on, and is clamped otherwise.
     */
    public var at: Int by freezer.setting(30)

    /**
     * Upload this long after the first waiting event, in milliseconds. Default 60 000 (one minute);
     * from 1 000 (one second) to 600 000 (ten minutes). A value out of bounds throws when `debug` is
     * on, and is clamped otherwise.
     */
    public var intervalMs: Long by freezer.setting(60_000L)

    /**
     * Event names that are uploaded right after they are stored, together with everything waiting
     * before them. Matched exactly, after the `beforeSend` hooks. Empty by default. Without a network
     * the event waits like any other.
     *
     * ```
     * flush.immediateEvents = setOf("Purchase Completed")
     * ```
     */
    public var immediateEvents: Set<String> by freezer.setting(emptySet())
}

/** How sessions are measured (Session 4). Held by [AnalyticsConfig.session]. */
public class SessionConfig internal constructor(
    freezer: Freezer,
) {
    /**
     * A session continues if the app returns to the foreground within this time, in milliseconds.
     * Default 300 000 (five minutes); from 10 000 (ten seconds) to 7 200 000 (two hours). A value out
     * of bounds throws when `debug` is on, and is clamped otherwise.
     */
    public var backgroundTimeoutMs: Long by freezer.setting(300_000L)
}

/** Which events the SDK captures by itself (Lifecycle 6). Held by [AnalyticsConfig.autocapture]. */
public class AutocaptureConfig internal constructor(
    freezer: Freezer,
) {
    /**
     * The enabled types. Default: every [Autocapture] type except [Autocapture.DEEP_LINKS].
     *
     * ```
     * autocapture.enabled -= Autocapture.SCREEN_VIEWS
     * ```
     */
    public var enabled: Set<Autocapture> by freezer.setting(
        setOf(
            Autocapture.APP_LIFECYCLE,
            Autocapture.INSTALL_UPDATE,
            Autocapture.SESSIONS,
            Autocapture.SCREEN_VIEWS,
            Autocapture.NETWORK_REQUESTS,
        ),
    )
}

/** What goes into each event's `context` (Context 6). Held by [AnalyticsConfig.context]. */
public class ContextConfig internal constructor(
    freezer: Freezer,
) {
    /** Fields that are never read and never sent. Empty by default. */
    public var disabledFields: Set<ContextField> by freezer.setting(emptySet())
}

/** Consent (Privacy 2, 4). Held by [AnalyticsConfig.privacy]; change it at runtime with `optIn` and `optOut`. */
public class PrivacyConfig internal constructor(
    freezer: Freezer,
) {
    /** The state while no choice is stored. Default [Consent.GRANTED]. */
    public var defaultConsent: Consent by freezer.setting(Consent.GRANTED)

    /**
     * Whether the SDK stores the user's `optIn` or `optOut` choice on the device, where it wins over
     * [defaultConsent] at the next launch. Default `true`. Set it to `false` when the app's own
     * consent system keeps the choice and passes it to the SDK on every launch.
     */
    public var persistConsent: Boolean by freezer.setting(true)
}

/** The SDK's own log lines and health reports (Logging). Held by [AnalyticsConfig.logging]. */
public class LoggingConfig internal constructor(
    freezer: Freezer,
) {
    /**
     * How much the SDK logs. Default [LogLevel.WARN], which is silent in a healthy app. `debug` does
     * not change it.
     *
     * ```
     * if (BuildConfig.DEBUG) logging.level = LogLevel.DEBUG
     * ```
     */
    public var level: LogLevel by freezer.setting(LogLevel.WARN)

    /** Where log lines go. Default `null`: Logcat with the tag `Rubicon` on Android. */
    public var logger: RubiconLogger? by freezer.setting(null)

    /**
     * Whether the SDK sends the automatic `SDK Diagnostics` event, at most once an hour and only
     * when it dropped events or met problems. Default `true`.
     */
    public var sendDiagnostics: Boolean by freezer.setting(true)
}

/** Requests the SDK sends (Configuration 5). Held by [AnalyticsConfig.network]. */
public class NetworkConfig internal constructor(
    freezer: Freezer,
) {
    /**
     * Headers added to every SDK request, for example a gateway token of an on-prem deployment.
     * Empty by default. A header the SDK sets itself cannot be replaced: `Authorization`,
     * `Content-Type`, `Content-Encoding`, `Content-Length`, `User-Agent`, `Host`, `If-None-Match`
     * and any name starting with `X-Rubicon-`, in any letter case. Such a header throws when `debug`
     * is on, and is ignored otherwise.
     *
     * ```
     * network.headers = mapOf("X-Gateway-Token" to BuildConfig.GATEWAY_TOKEN)
     * ```
     */
    public var headers: Map<String, String> by freezer.setting(emptyMap())
}
