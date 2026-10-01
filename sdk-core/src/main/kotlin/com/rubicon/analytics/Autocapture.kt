package com.rubicon.analytics

/**
 * Event types the SDK captures by itself, switched with `autocapture.enabled` (Lifecycle 6). All are
 * on by default except [DEEP_LINKS]. Remote configuration can switch a type off but never on.
 *
 * ```
 * AnalyticsConfig(key).apply {
 *     autocapture.enabled -= Autocapture.NETWORK_REQUESTS
 * }
 * ```
 */
public enum class Autocapture {
    /** `Application Opened` and `Application Backgrounded`. */
    APP_LIFECYCLE,

    /** `Application Installed` and `Application Updated`. */
    INSTALL_UPDATE,

    /** `Session Started` and `Session Ended`. */
    SESSIONS,

    /**
     * A screen view for each activity shown. Remove it in a single-activity app, for example one
     * built with Compose, and call `screen` yourself.
     */
    SCREEN_VIEWS,

    /**
     * `Deep Link Opened`, off by default. It sends the scheme, host, path, the five `utm_*`
     * parameters and the referrer's package or host. Mask tokens in the path by giving that property
     * a new value in `beforeSend`.
     */
    DEEP_LINKS,

    /**
     * `Network Request` for the app's own HTTP calls; it takes effect only when the app adds
     * `RubiconNetworkInterceptor` to its `OkHttpClient`. A remote switch-off applies at once.
     */
    NETWORK_REQUESTS,
}
