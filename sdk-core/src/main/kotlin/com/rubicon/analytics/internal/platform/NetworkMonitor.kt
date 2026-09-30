package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal

/**
 * Connectivity as the uploader and the network trigger see it (Retry 6, Queue 2). Connected means
 * the default network has `NET_CAPABILITY_INTERNET`; validation is never required. When the state
 * cannot be known, for example without `ACCESS_NETWORK_STATE`, the monitor reports connected.
 */
@RubiconInternal
public interface NetworkMonitor {
    public fun isConnected(): Boolean

    /** Listeners are called on the thread that observed the change and must not block. */
    public fun addListener(listener: Listener)

    public fun removeListener(listener: Listener)

    public fun interface Listener {
        public fun onConnectivityChanged(connected: Boolean)
    }
}
