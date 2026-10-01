package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.internal.platform.InstalledApp
import com.rubicon.analytics.internal.platform.ProcessInfo
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.util.IsoDates

/**
 * The automatic app events (Lifecycle 1, 4, 6): `Application Installed` and `Application Updated` on
 * the first foreground of the process, `Application Opened` on every foreground and
 * `Application Backgrounded` on every background. It only decides; the writer appends them.
 *
 * - A repeated signal (foreground while in the foreground) produces nothing, so the writer's startup
 *   check and a replayed `ON_START` do not open twice.
 * - The last seen version and build are written on the first foreground whatever the gates and the
 *   consent say, so a later opt-in or a feature switched on later does not report a false update.
 *
 * Runs on the writer only.
 */
internal class AppLifecycle(
    private val deviceStore: DeviceStore,
    private val processInfo: ProcessInfo,
    private val autocapture: Set<Autocapture>,
) {
    class AutoEvent(
        val name: String,
        val properties: Map<String, Any?>,
    )

    private var foreground = false
    private var openedBefore = false

    /** The events of coming to the foreground, oldest first; `null` when already in the foreground. */
    fun onForeground(): List<AutoEvent>? {
        if (foreground) return null
        foreground = true
        val first = !openedBefore
        openedBefore = true
        val events = ArrayList<AutoEvent>(2)
        if (first) {
            val installOrUpdate = checkInstall()
            if (installOrUpdate != null && Autocapture.INSTALL_UPDATE in autocapture) events += installOrUpdate
        }
        if (Autocapture.APP_LIFECYCLE in autocapture) events += AutoEvent(OPENED, mapOf(FROM_BACKGROUND to !first))
        return events
    }

    /** The events of going to the background; `null` when not in the foreground. */
    fun onBackground(): List<AutoEvent>? {
        if (!foreground) return null
        foreground = false
        return if (Autocapture.APP_LIFECYCLE in autocapture) listOf(AutoEvent(BACKGROUNDED, emptyMap())) else emptyList()
    }

    // Lifecycle 4: the install times tell a fresh install from an app that just added the SDK.
    private fun checkInstall(): AutoEvent? {
        val app = processInfo.installedApp() ?: return null
        val build = app.build ?: return null
        val state = deviceStore.state
        val event =
            when {
                state.lastBuild == null && app.firstInstallTime == app.lastUpdateTime -> installed(app)
                state.lastBuild == null -> updated(app, null, null)
                state.lastBuild != build -> updated(app, state.lastVersion, state.lastBuild)
                else -> null
            }
        deviceStore.update { it.copy(lastVersion = app.version, lastBuild = build) }
        return event
    }

    private fun installed(app: InstalledApp) =
        AutoEvent(
            INSTALLED,
            known(VERSION to app.version, BUILD to app.build, INSTALLED_AT to IsoDates.format(app.firstInstallTime)),
        )

    private fun updated(
        app: InstalledApp,
        previousVersion: String?,
        previousBuild: String?,
    ): AutoEvent {
        val downgrade = isLower(app.build, previousBuild)
        return AutoEvent(
            UPDATED,
            known(
                VERSION to app.version,
                BUILD to app.build,
                PREVIOUS_VERSION to previousVersion,
                PREVIOUS_BUILD to previousBuild,
                UPDATED_AT to IsoDates.format(app.lastUpdateTime),
                IS_DOWNGRADE to (if (downgrade) true else null),
            ),
        )
    }

    // Properties that are not known are left out, not sent as null.
    private fun known(vararg entries: Pair<String, Any?>): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for ((key, value) in entries) if (value != null) map[key] = value
        return map
    }

    private fun isLower(
        build: String?,
        previous: String?,
    ): Boolean {
        val now = build?.toLongOrNull() ?: return false
        val before = previous?.toLongOrNull() ?: return false
        return now < before
    }

    companion object {
        const val INSTALLED: String = "Application Installed"
        const val UPDATED: String = "Application Updated"
        const val OPENED: String = "Application Opened"
        const val BACKGROUNDED: String = "Application Backgrounded"
        const val FROM_BACKGROUND: String = "from_background"
        const val VERSION: String = "version"
        const val BUILD: String = "build"
        const val PREVIOUS_VERSION: String = "previous_version"
        const val PREVIOUS_BUILD: String = "previous_build"
        const val INSTALLED_AT: String = "installed_at"
        const val UPDATED_AT: String = "updated_at"
        const val IS_DOWNGRADE: String = "is_downgrade"
    }
}
