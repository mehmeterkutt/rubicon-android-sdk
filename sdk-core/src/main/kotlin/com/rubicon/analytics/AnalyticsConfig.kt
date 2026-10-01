package com.rubicon.analytics

import com.rubicon.analytics.internal.config.Freezer

/**
 * Everything the SDK needs to start, handed to the factory once (Configuration 2). Only [apiKey]
 * is required; every other setting has a default. Settings live in small groups held in `val`
 * properties, so a group's values can be set but the group cannot be replaced.
 *
 * The factory validates the configuration, keeps a copy and freezes this object: a later write
 * throws `IllegalStateException` when [debug] is on, and is ignored with one warning otherwise.
 * Behaviour that changes at runtime goes through `Analytics`, never through the configuration.
 *
 * ```
 * // Kotlin
 * val config = AnalyticsConfig(BuildConfig.RUBICON_KEY).apply {
 *     debug = BuildConfig.DEBUG
 *     flush.immediateEvents = setOf("Purchase Completed")
 *     autocapture.enabled -= Autocapture.NETWORK_REQUESTS
 * }
 *
 * // Java
 * AnalyticsConfig config = new AnalyticsConfig(BuildConfig.RUBICON_KEY);
 * config.setDebug(BuildConfig.DEBUG);
 * config.getFlush().setAt(20);
 * ```
 *
 * @param apiKey the project's write key. A blank key throws when [debug] is on; otherwise the SDK
 *   does nothing and logs an error.
 */
public class AnalyticsConfig(
    public val apiKey: String,
) {
    internal val freezer: Freezer = Freezer()

    /**
     * The base URL of the analytics server. Default [DEFAULT_HOST]; on-prem and proxy setups set
     * their own, with an `http` or `https` scheme and an optional path:
     * `https://intranet.example.com/analytics`. A trailing slash is ignored. `http://` works and
     * logs a warning. An invalid URL, or one with a query, fragment, user info or surrounding
     * whitespace, throws when [debug] is on; otherwise the SDK does nothing and logs an error.
     */
    public var host: String by freezer.setting(DEFAULT_HOST)

    /**
     * Whether developer mistakes throw, such as an invalid setting or an unsupported property value.
     * Default `false`: the SDK then logs and carries on. Recommended: `BuildConfig.DEBUG`. It changes
     * neither the log level nor when events are uploaded.
     */
    public var debug: Boolean by freezer.setting(false)

    /** When events are uploaded. */
    public val flush: FlushConfig = FlushConfig(freezer)

    /** How sessions are measured. */
    public val session: SessionConfig = SessionConfig(freezer)

    /** Which events the SDK captures by itself. */
    public val autocapture: AutocaptureConfig = AutocaptureConfig(freezer)

    /** What goes into each event's `context`. */
    public val context: ContextConfig = ContextConfig(freezer)

    /** Consent. */
    public val privacy: PrivacyConfig = PrivacyConfig(freezer)

    /** The SDK's own log lines and health reports. */
    public val logging: LoggingConfig = LoggingConfig(freezer)

    /** Requests the SDK sends. */
    public val network: NetworkConfig = NetworkConfig(freezer)

    override fun toString(): String = "AnalyticsConfig(host=$host)"

    public companion object {
        /** The SaaS base URL, used when [host] is not set. */
        public const val DEFAULT_HOST: String = "https://api.rubicon.example"
    }
}
