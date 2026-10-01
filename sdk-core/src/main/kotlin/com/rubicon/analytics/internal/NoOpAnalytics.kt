package com.rubicon.analytics.internal

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.RubiconInternal

/**
 * The instance the factory returns when the SDK must not run: an unusable configuration in release
 * (Configuration 3) or a process other than the main one (Disk 2d). It accepts every call, touches
 * no disk or network and never throws. [isOptedOut] is `true`, since nothing is sent, and [deviceId]
 * is empty, since there is no installation id to report (section 15, step 3.3).
 */
@RubiconInternal
public object NoOpAnalytics : Analytics {
    override fun track(
        name: String,
        properties: Map<String, Any?>,
    ): Unit = Unit

    override fun screen(
        name: String,
        properties: Map<String, Any?>,
    ): Unit = Unit

    override fun identify(
        userId: String,
        traits: Map<String, Any?>,
    ): Unit = Unit

    override fun reset(): Unit = Unit

    override fun register(
        key: String,
        value: Any?,
    ): Unit = Unit

    override fun unregister(key: String): Unit = Unit

    override fun flush(): Unit = Unit

    override fun optOut(): Unit = Unit

    override fun optIn(): Unit = Unit

    override fun isOptedOut(): Boolean = true

    override fun clearLocalData(): Unit = Unit

    override val deviceId: String = ""

    override fun toString(): String = "NoOpAnalytics"
}
