package com.rubicon.analytics.testing

import com.rubicon.analytics.ContextField
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.ContextProvider
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.platform.LifecycleSource
import com.rubicon.analytics.internal.platform.NetworkMonitor
import com.rubicon.analytics.internal.platform.ProcessInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Connectivity the test switches; listeners hear only real changes. */
public class FakeNetworkMonitor(
    connected: Boolean = true,
) : NetworkMonitor {
    @Volatile
    private var connected: Boolean = connected
    private val listeners = CopyOnWriteArrayList<NetworkMonitor.Listener>()

    override fun isConnected(): Boolean = connected

    override fun addListener(listener: NetworkMonitor.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: NetworkMonitor.Listener) {
        listeners -= listener
    }

    public fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        listeners.forEach { it.onConnectivityChanged(value) }
    }
}

/** Foreground and background the test switches; listeners hear only real changes. */
public class FakeLifecycleSource(
    foreground: Boolean = false,
) : LifecycleSource {
    @Volatile
    override var isForeground: Boolean = foreground
        private set
    private val listeners = CopyOnWriteArrayList<LifecycleSource.Listener>()

    override fun addListener(listener: LifecycleSource.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: LifecycleSource.Listener) {
        listeners -= listener
    }

    public fun moveToForeground() {
        if (isForeground) return
        isForeground = true
        listeners.forEach { it.onForeground() }
    }

    public fun moveToBackground() {
        if (!isForeground) return
        isForeground = false
        listeners.forEach { it.onBackground() }
    }
}

/**
 * A [ContextProvider] that returns what the test set and records the disabled fields it was given.
 * It leaves out those fields itself, as the real provider never reads them (Context 6).
 */
public class FakeContextProvider(
    @Volatile public var staticValues: StaticValues = StaticValues.NONE,
    @Volatile public var callTimeValues: CallTimeValues = CallTimeValues.NONE,
) : ContextProvider {
    private val staticReadCount = AtomicInteger()
    private val callTimeReadCount = AtomicInteger()

    public val staticReads: Int get() = staticReadCount.get()
    public val callTimeReads: Int get() = callTimeReadCount.get()

    /** The disabled fields of the last read. */
    @Volatile
    public var lastDisabled: Set<ContextField> = emptySet()
        private set

    override fun staticValues(disabled: Set<ContextField>): StaticValues {
        staticReadCount.incrementAndGet()
        lastDisabled = disabled
        val v = staticValues

        fun <T> keep(
            field: ContextField,
            value: T,
        ): T? = if (field in disabled) null else value
        return StaticValues(
            appName = keep(ContextField.APP_NAME, v.appName),
            appVersion = keep(ContextField.APP_VERSION, v.appVersion),
            appBuild = keep(ContextField.APP_BUILD, v.appBuild),
            appNamespace = keep(ContextField.APP_NAMESPACE, v.appNamespace),
            deviceManufacturer = keep(ContextField.DEVICE_MANUFACTURER, v.deviceManufacturer),
            deviceModel = keep(ContextField.DEVICE_MODEL, v.deviceModel),
            deviceType = keep(ContextField.DEVICE_TYPE, v.deviceType),
            deviceIsEmulator = keep(ContextField.DEVICE_IS_EMULATOR, v.deviceIsEmulator),
            osName = keep(ContextField.OS_NAME, v.osName),
            osVersion = keep(ContextField.OS_VERSION, v.osVersion),
            osApiLevel = keep(ContextField.OS_API_LEVEL, v.osApiLevel),
        )
    }

    override fun callTimeValues(disabled: Set<ContextField>): CallTimeValues {
        callTimeReadCount.incrementAndGet()
        lastDisabled = disabled
        val v = callTimeValues

        fun <T> keep(
            field: ContextField,
            value: T,
        ): T? = if (field in disabled) null else value
        return CallTimeValues(
            screenWidth = keep(ContextField.SCREEN_WIDTH, v.screenWidth),
            screenHeight = keep(ContextField.SCREEN_HEIGHT, v.screenHeight),
            screenDensity = keep(ContextField.SCREEN_DENSITY, v.screenDensity),
            networkType = keep(ContextField.NETWORK_TYPE, v.networkType),
            networkCarrier = keep(ContextField.NETWORK_CARRIER, v.networkCarrier),
            locale = keep(ContextField.LOCALE, v.locale),
            timezone = keep(ContextField.TIMEZONE, v.timezone),
        )
    }
}

/** Process facts the test sets. */
public class FakeProcessInfo(
    override val osVersion: String = "16",
    override val apiLevel: Int = 36,
    @Volatile public var mainProcess: Boolean? = true,
) : ProcessInfo {
    override fun isMainProcess(): Boolean? = mainProcess
}
