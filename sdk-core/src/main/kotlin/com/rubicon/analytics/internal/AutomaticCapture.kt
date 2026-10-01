package com.rubicon.analytics.internal

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Log

/**
 * How `sdk-android` sends the events it captures itself: automatic screen views, deep links and
 * network requests (Lifecycle 1, 5). Each event has `source = AUTO` and is created only while
 * collecting; the writer drops it, uncounted, unless [Autocapture] `gate` is in the effective set,
 * so a remote switch-off applies from the next launch (Lifecycle 6). Never throws.
 */
@RubiconInternal
public interface AutomaticCapture {
    public fun track(
        gate: Autocapture,
        name: String,
        properties: Map<String, Any?>,
    )

    public fun screen(
        gate: Autocapture,
        name: String,
        properties: Map<String, Any?>,
    )

    /**
     * Whether a network request is captured now: `NETWORK_REQUESTS` configured, not switched off
     * remotely (at once, Lifecycle 6) and collecting. Read on every request; cheap.
     */
    public val capturesNetworkRequests: Boolean

    /** The host the SDK sends to, never captured. */
    public val sdkHost: String

    public val debug: Boolean

    public val log: Log
}
