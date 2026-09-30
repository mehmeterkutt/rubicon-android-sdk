package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal

/** Facts about the running process and operating system (Disk 2d, HTTP 5). */
@RubiconInternal
public interface ProcessInfo {
    /** Operating system version for the User-Agent, for example `16`. */
    public val osVersion: String

    /** Platform API level for the User-Agent, for example `36`. */
    public val apiLevel: Int

    /** `true` in the app's main process, `false` in another one, `null` when it cannot be told. */
    public fun isMainProcess(): Boolean?
}
