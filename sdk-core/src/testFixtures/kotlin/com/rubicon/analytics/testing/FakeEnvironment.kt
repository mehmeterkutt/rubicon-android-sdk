package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.context.ContextProvider
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

/** Context values the test sets; counts reads so a test can check that nothing was collected. */
public class FakeContextProvider(
    @Volatile public var staticValues: Map<String, Any?> = emptyMap(),
    @Volatile public var callTimeValues: Map<String, Any?> = emptyMap(),
) : ContextProvider {
    private val staticReadCount = AtomicInteger()
    private val callTimeReadCount = AtomicInteger()

    public val staticReads: Int get() = staticReadCount.get()
    public val callTimeReads: Int get() = callTimeReadCount.get()

    override fun staticValues(): Map<String, Any?> {
        staticReadCount.incrementAndGet()
        return staticValues
    }

    override fun callTimeValues(): Map<String, Any?> {
        callTimeReadCount.incrementAndGet()
        return callTimeValues
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
