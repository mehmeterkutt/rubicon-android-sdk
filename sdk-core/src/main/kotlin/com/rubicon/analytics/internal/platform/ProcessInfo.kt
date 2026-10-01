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

    /**
     * The installed package, for install and update detection (Lifecycle 4); read on the writer at
     * the first foreground. `null` when it cannot be read.
     */
    public fun installedApp(): InstalledApp? = null
}

/** The app's version and Android's install times (`PackageInfo`), in epoch milliseconds. */
@RubiconInternal
public class InstalledApp(
    public val version: String?,
    /** The version code as text, like `context.app.build`. */
    public val build: String?,
    public val firstInstallTime: Long,
    public val lastUpdateTime: Long,
)
