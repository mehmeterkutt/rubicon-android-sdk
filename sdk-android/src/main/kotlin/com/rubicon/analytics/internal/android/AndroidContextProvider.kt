package com.rubicon.analytics.internal.android

import android.app.UiModeManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.ContextProvider
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.platform.ElapsedClock
import java.util.Locale
import java.util.TimeZone

/**
 * The `context` of events on Android (Context 1 to 3, 6).
 *
 * - App, device and OS values are read once, on the writer, on first need.
 * - Screen, locale, network type and carrier live in a cache that the calling thread only reads. The
 *   writer fills it at startup ([refresh]); a configuration change marks it stale and the writer
 *   reads it again before its next message, so events captured in between are `pending` and get the
 *   new values when written. The network callback refreshes the network type and the carrier on its
 *   own thread; without a callback the writer reads both at most every 60 seconds.
 * - The timezone is read per event, on the calling thread (`TimeZone.getDefault()` is cached).
 * - A disabled field is never read; a value that cannot be read is `null` and omitted.
 */
@RubiconInternal
internal class AndroidContextProvider(
    private val context: Context,
    private val network: AndroidNetworkMonitor,
    private val elapsedClock: ElapsedClock,
) : ContextProvider {
    private data class Cache(
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val screenDensity: Double? = null,
        val networkType: String? = null,
        val networkCarrier: String? = null,
        val locale: String? = null,
    )

    private val lock = Any()

    // Null until the writer's first refresh.
    @Volatile
    private var cache: Cache? = null

    // A configuration change counts up; the cache is stale until refresh has read the latest one.
    @Volatile
    private var configurationChanges = 0

    @Volatile
    private var configurationRead = 0

    @Volatile
    private var disabled: Set<ContextField> = emptySet()

    // Guarded by the writer: only refresh reads and writes it.
    private var networkReadAt = 0L

    override fun staticValues(disabled: Set<ContextField>): StaticValues {
        val packageInfo by lazy(LazyThreadSafetyMode.NONE) { packageInfo() }
        return StaticValues(
            appName = read(ContextField.APP_NAME, disabled) { context.applicationInfo.loadLabel(context.packageManager).toString() },
            appVersion = read(ContextField.APP_VERSION, disabled) { packageInfo?.versionName },
            appBuild = read(ContextField.APP_BUILD, disabled) { packageInfo?.let { AndroidProcessInfo.versionCode(it).toString() } },
            appNamespace = read(ContextField.APP_NAMESPACE, disabled) { context.packageName },
            deviceManufacturer = read(ContextField.DEVICE_MANUFACTURER, disabled) { Build.MANUFACTURER },
            deviceModel = read(ContextField.DEVICE_MODEL, disabled) { Build.MODEL },
            deviceType = read(ContextField.DEVICE_TYPE, disabled) { deviceType() },
            deviceIsEmulator = read(ContextField.DEVICE_IS_EMULATOR, disabled) { Emulator.isCurrent() },
            osName = read(ContextField.OS_NAME, disabled) { OS_NAME },
            osVersion = read(ContextField.OS_VERSION, disabled) { Build.VERSION.RELEASE },
            osApiLevel = read(ContextField.OS_API_LEVEL, disabled) { Build.VERSION.SDK_INT },
        )
    }

    override fun callTimeValues(disabled: Set<ContextField>): CallTimeValues {
        val timezone = read(ContextField.TIMEZONE, disabled) { TimeZone.getDefault().id }
        val c = cache
        if (c == null || configurationRead != configurationChanges) return CallTimeValues(timezone = timezone, pending = true)
        // The cache holds no disabled field; filtering again keeps that true whatever set is passed.
        return CallTimeValues(
            screenWidth = on(ContextField.SCREEN_WIDTH, disabled, c.screenWidth),
            screenHeight = on(ContextField.SCREEN_HEIGHT, disabled, c.screenHeight),
            screenDensity = on(ContextField.SCREEN_DENSITY, disabled, c.screenDensity),
            networkType = on(ContextField.NETWORK_TYPE, disabled, c.networkType),
            networkCarrier = on(ContextField.NETWORK_CARRIER, disabled, c.networkCarrier),
            locale = on(ContextField.LOCALE, disabled, c.locale),
            timezone = timezone,
        )
    }

    override fun refresh(disabled: Set<ContextField>) {
        try {
            this.disabled = disabled
            if (cache == null) {
                start(disabled)
                return
            }
            val changes = configurationChanges
            if (configurationRead != changes) {
                val display = readDisplay(disabled)
                update {
                    it.copy(
                        screenWidth = display.width,
                        screenHeight = display.height,
                        screenDensity = display.density,
                        locale = display.locale,
                    )
                }
                configurationRead = changes
            }
            if (!network.usesCallback && elapsedClock.elapsedRealtimeMillis() - networkReadAt >= NETWORK_READ_INTERVAL_MS) {
                networkReadAt = elapsedClock.elapsedRealtimeMillis()
                val type = read(ContextField.NETWORK_TYPE, disabled) { network.networkType }
                val carrier = read(ContextField.NETWORK_CARRIER, disabled) { carrier() }
                update { it.copy(networkType = type, networkCarrier = carrier) }
            }
        } catch (t: Throwable) {
            // The cache keeps its values; an event never waits for a missing one.
            if (cache == null) cache = Cache()
        }
    }

    // The first refresh: listen for changes first, so none is missed between the read and the
    // registration.
    private fun start(disabled: Set<ContextField>) {
        network.onNetworkChanged = ::onNetworkChanged
        try {
            context.registerComponentCallbacks(configurationCallbacks)
        } catch (t: Throwable) {
            // Screen and locale keep their first values.
        }
        networkReadAt = elapsedClock.elapsedRealtimeMillis()
        val display = readDisplay(disabled)
        cache =
            Cache(
                screenWidth = display.width,
                screenHeight = display.height,
                screenDensity = display.density,
                networkType = read(ContextField.NETWORK_TYPE, disabled) { network.networkType },
                networkCarrier = read(ContextField.NETWORK_CARRIER, disabled) { carrier() },
                locale = display.locale,
            )
    }

    // On the network callback's thread, never the main thread.
    private fun onNetworkChanged() {
        if (cache == null) return
        val current = disabled
        val type = read(ContextField.NETWORK_TYPE, current) { network.networkType }
        val carrier = read(ContextField.NETWORK_CARRIER, current) { carrier() }
        update { it.copy(networkType = type, networkCarrier = carrier) }
    }

    private inline fun update(change: (Cache) -> Cache) {
        synchronized(lock) { cache = change(cache ?: Cache()) }
    }

    private val configurationCallbacks =
        object : ComponentCallbacks {
            // On the main thread: only a count, the writer reads the new values.
            override fun onConfigurationChanged(newConfig: Configuration) {
                synchronized(lock) { configurationChanges++ }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory(): Unit = Unit
        }

    private class DisplayValues(
        val width: Int?,
        val height: Int?,
        val density: Double?,
        val locale: String?,
    )

    private fun readDisplay(disabled: Set<ContextField>): DisplayValues {
        val wantsSize = ContextField.SCREEN_WIDTH !in disabled || ContextField.SCREEN_HEIGHT !in disabled
        val wantsDensity = ContextField.SCREEN_DENSITY !in disabled
        val metrics = if (wantsSize || wantsDensity) safely { realMetrics() } else null
        val (short, long) =
            metrics?.let { minOf(it.widthPixels, it.heightPixels) to maxOf(it.widthPixels, it.heightPixels) } ?: (null to null)
        return DisplayValues(
            width = on(ContextField.SCREEN_WIDTH, disabled, short?.takeIf { it > 0 }),
            height = on(ContextField.SCREEN_HEIGHT, disabled, long?.takeIf { it > 0 }),
            // Float via its decimal text (Serialization 2): 2.625, not 2.625000047...
            density =
                on(
                    ContextField.SCREEN_DENSITY,
                    disabled,
                    metrics
                        ?.density
                        ?.takeIf { it > 0f }
                        ?.toString()
                        ?.toDouble(),
                ),
            locale = read(ContextField.LOCALE, disabled) { locale() },
        )
    }

    // The whole display, not the app's window: the short side is the width, so rotating the device
    // changes nothing and no orientation is implied (Context 3).
    private fun realMetrics(): DisplayMetrics? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun locale(): String? {
        val configuration = context.resources.configuration
        val locale: Locale? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.locales.takeIf { !it.isEmpty }?.get(0)
            } else {
                @Suppress("DEPRECATION")
                configuration.locale
            }
        return locale?.toLanguageTag()?.takeIf { it != UNDETERMINED }
    }

    // The operator of the SIM that carries mobile data, not the default (voice) one (Context 2).
    private fun carrier(): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dataSubscription = SubscriptionManager.getDefaultDataSubscriptionId()
            if (dataSubscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return telephony.createForSubscriptionId(dataSubscription).networkOperatorName
            }
        }
        return telephony.networkOperatorName
    }

    private fun deviceType(): String? {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        ) {
            return "tv"
        }
        val smallestWidth = context.resources.configuration.smallestScreenWidthDp
        return when {
            smallestWidth == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED -> null
            smallestWidth >= TABLET_MIN_WIDTH_DP -> "tablet"
            else -> "phone"
        }
    }

    private fun packageInfo(): PackageInfo? = safely { context.packageManager.getPackageInfo(context.packageName, 0) }

    private companion object {
        const val OS_NAME = "Android"
        const val UNDETERMINED = "und"
        const val TABLET_MIN_WIDTH_DP = 600
        const val NETWORK_READ_INTERVAL_MS = 60_000L

        fun <T> on(
            field: ContextField,
            disabled: Set<ContextField>,
            value: T?,
        ): T? = if (field in disabled) null else value

        // A disabled field is never read; a failure or a blank text is an unknown value (Context 3, 6).
        inline fun <T> read(
            field: ContextField,
            disabled: Set<ContextField>,
            block: () -> T?,
        ): T? {
            if (field in disabled) return null
            val value = safely(block)
            return if (value is String && value.isBlank()) null else value
        }

        inline fun <T> safely(block: () -> T?): T? =
            try {
                block()
            } catch (t: Throwable) {
                null
            }
    }
}
