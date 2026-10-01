@file:JvmName("RubiconAnalytics")

package com.rubicon.analytics

import android.content.Context
import com.rubicon.analytics.internal.NoOpAnalytics
import com.rubicon.analytics.internal.RubiconCore
import com.rubicon.analytics.internal.android.ActivityCapture
import com.rubicon.analytics.internal.android.AndroidLifecycleSource
import com.rubicon.analytics.internal.android.AndroidPlatform
import com.rubicon.analytics.internal.android.AndroidProcessInfo
import com.rubicon.analytics.internal.android.LogcatLogger
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log

/**
 * Creates the SDK's instance (Public API 1, 2). Call it once, in `Application.onCreate`, and hold
 * the result; a second call returns the first instance (it throws when `debug` is on).
 *
 * ```
 * class MyApp : Application() {
 *     lateinit var analytics: Analytics
 *     override fun onCreate() {
 *         super.onCreate()
 *         analytics = Analytics(this, AnalyticsConfig("api-key").apply { debug = BuildConfig.DEBUG })
 *     }
 * }
 * ```
 *
 * From Java: `Analytics analytics = RubiconAnalytics.create(this, config);`
 *
 * - Only the application context is kept.
 * - It reads nothing from disk, so it is safe on the main thread; the SDK loads its files in the
 *   background (Privacy 2).
 * - The SDK runs in the app's main process only. In any other process, such as a `:remote` service,
 *   it returns an instance that does nothing (Disk 2d).
 * - With an unusable configuration (a blank key, an invalid host) it returns an instance that does
 *   nothing and logs an ERROR, or throws when `debug` is on (Configuration 3).
 */
@JvmName("create")
public fun Analytics(
    context: Context,
    config: AnalyticsConfig,
): Analytics {
    val app = context.applicationContext ?: context
    val processInfo = AndroidProcessInfo(app)
    when (processInfo.isMainProcess()) {
        false -> {
            factoryLog(config).info { "Not the app's main process; analytics does nothing in this process" }
            return NoOpAnalytics
        }
        null ->
            factoryLog(config).warn(Code.PROCESS_NAME_UNKNOWN) { "The process name could not be determined; the main process is assumed" }
        true -> Unit
    }
    val platform = AndroidPlatform.create(app, processInfo)
    return RubiconCore.create(config, platform) { capture ->
        // Before the first activity starts, when called from Application.onCreate (Lifecycle 2).
        (platform.lifecycleSource as? AndroidLifecycleSource)?.attach()
        ActivityCapture.attach(app, config.autocapture.enabled, capture)
    }
}

// The core builds the instance's log; this one only reports what the factory decides before that.
private fun factoryLog(config: AnalyticsConfig) = Log(config.logging.level, config.logging.logger, LogcatLogger)
