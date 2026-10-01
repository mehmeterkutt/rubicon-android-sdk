package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Log

/**
 * Whether the app is in the foreground, from `ProcessLifecycleOwner` on Android (Lifecycle 2).
 * Sessions (Session 3) and the background flush (Queue 2) listen to it.
 */
@RubiconInternal
public interface LifecycleSource {
    public val isForeground: Boolean

    /** Starts observing; called once by the writer at startup, before [addListener]. */
    public fun start(log: Log)

    public fun addListener(listener: Listener)

    public fun removeListener(listener: Listener)

    public interface Listener {
        public fun onForeground()

        /** The process went to the background (`ON_STOP`). */
        public fun onBackground()
    }
}
