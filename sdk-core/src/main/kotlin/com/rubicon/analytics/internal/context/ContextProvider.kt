package com.rubicon.analytics.internal.context

import com.rubicon.analytics.RubiconInternal

/**
 * Supplies the `context` of events (Context 1, 2), implemented in `sdk-android`. Nothing is read on
 * the main thread and a disabled field is never read (Context 6). Values are wire-ready nested maps
 * keyed by the `snake_case` field names, with unknown values omitted.
 */
@RubiconInternal
public interface ContextProvider {
    /** App, device, OS and library values; collected once on the worker, on first need. */
    public fun staticValues(): Map<String, Any?>

    /**
     * Network, screen, locale and timezone, read from a cache on the calling thread at call time.
     * Must be cheap and must not block.
     */
    public fun callTimeValues(): Map<String, Any?>
}
