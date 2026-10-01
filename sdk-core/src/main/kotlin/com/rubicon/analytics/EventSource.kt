package com.rubicon.analytics

/**
 * Who created an event (Lifecycle 5): sent as `"source": "app"` or `"source": "auto"`. Only the SDK
 * sets it. Values may be added in minor versions; a `when` over this enum should keep an `else`
 * branch.
 */
public enum class EventSource {
    /** A call by the app: `track`, `screen` or `identify`. */
    APP,

    /** Captured by the SDK itself, such as `Application Opened` or an automatic screen view. */
    AUTO,
}
