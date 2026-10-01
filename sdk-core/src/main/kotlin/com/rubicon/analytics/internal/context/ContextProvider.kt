package com.rubicon.analytics.internal.context

import com.rubicon.analytics.ContextField
import com.rubicon.analytics.RubiconInternal

/**
 * Supplies the `context` of events (Context 1, 2), implemented in `sdk-android`. Nothing is read on
 * the main thread, and a field in `disabled` is never read, not merely left out (Context 6). A value
 * that cannot be read is `null` and is omitted from the event (Context 3).
 */
@RubiconInternal
public interface ContextProvider {
    /** App, device and OS values; collected once on the worker, on first need. */
    public fun staticValues(disabled: Set<ContextField>): StaticValues

    /**
     * Screen, network, locale and timezone, read from a cache on the calling thread at call time
     * (Context 2). Must be cheap and must not block.
     */
    public fun callTimeValues(disabled: Set<ContextField>): CallTimeValues
}

/** The values that do not change during a process (Context 2, 3). */
@RubiconInternal
public class StaticValues(
    public val appName: String? = null,
    public val appVersion: String? = null,
    public val appBuild: String? = null,
    public val appNamespace: String? = null,
    public val deviceManufacturer: String? = null,
    public val deviceModel: String? = null,
    /** `phone`, `tablet` or `tv`. */
    public val deviceType: String? = null,
    public val deviceIsEmulator: Boolean? = null,
    public val osName: String? = null,
    public val osVersion: String? = null,
    public val osApiLevel: Int? = null,
) {
    public companion object {
        public val NONE: StaticValues = StaticValues()
    }
}

/** The values captured when an event is created (Context 2, 3). */
@RubiconInternal
public class CallTimeValues(
    public val screenWidth: Int? = null,
    public val screenHeight: Int? = null,
    public val screenDensity: Double? = null,
    /** `wifi`, `cellular`, `ethernet` or `none`. */
    public val networkType: String? = null,
    public val networkCarrier: String? = null,
    /** A BCP 47 tag such as `tr-TR`. */
    public val locale: String? = null,
    /** An IANA zone such as `Europe/Istanbul`. */
    public val timezone: String? = null,
) {
    public companion object {
        public val NONE: CallTimeValues = CallTimeValues()
    }
}
