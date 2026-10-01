package com.rubicon.analytics.internal.android

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.testing.RecordingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Both registration paths: a request below API 24, the default network callback from it (Retry 6).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class AndroidNetworkMonitorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val registered = slot<ConnectivityManager.NetworkCallback>()
    private val network = mockk<Network>()
    private val changes = mutableListOf<Boolean>()

    init {
        // The permission the SDK's manifest declares; the test app does not merge it.
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        // registerDefaultNetworkCallback does not exist below API 24, not even to be stubbed.
        if (Build.VERSION.SDK_INT >= 24) every { manager.registerDefaultNetworkCallback(capture(registered)) } returns Unit
        every { manager.registerNetworkCallback(any<NetworkRequest>(), capture(registered)) } returns Unit
        active(capabilities(internet = true, transport = NetworkCapabilities.TRANSPORT_WIFI))
    }

    private fun monitor() = AndroidNetworkMonitor(app) { manager }.also { it.addListener { connected -> changes += connected } }

    private fun capabilities(
        internet: Boolean,
        transport: Int,
        validated: Boolean = false,
    ) = mockk<NetworkCapabilities> {
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        every { hasTransport(any()) } answers { firstArg<Int>() == transport }
    }

    // What a query sees: the default network on API 23+, the legacy network info before.
    private fun active(capabilities: NetworkCapabilities?) {
        every { manager.activeNetwork } returns (if (capabilities == null) null else network)
        every { manager.getNetworkCapabilities(network) } returns capabilities
        every { manager.getNetworkCapabilities(null) } returns null
        @Suppress("DEPRECATION")
        val info =
            mockk<NetworkInfo> {
                every { isConnected } returns (capabilities != null)
                every { type } returns
                    ConnectivityManager.TYPE_WIFI
            }
        @Suppress("DEPRECATION")
        every { manager.activeNetworkInfo } returns (if (capabilities == null) null else info)
    }

    private fun warned(code: String) = logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith(code) }

    @Test
    fun `registers one callback at start and reads the current state`() {
        val monitor = monitor()

        monitor.start(log)
        monitor.start(log)

        if (Build.VERSION.SDK_INT >= 24) {
            verify(exactly = 1) { manager.registerDefaultNetworkCallback(any()) }
        } else {
            verify(exactly = 1) { manager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) }
        }
        assertTrue(monitor.isConnected())
        assertEquals("wifi", monitor.networkType)
    }

    @Test
    fun `losing and regaining the network tells the listeners once each`() {
        val monitor = monitor()
        monitor.start(log)

        active(null)
        registered.captured.onLost(network)
        registered.captured.onLost(network)
        assertFalse(monitor.isConnected())
        assertEquals("none", monitor.networkType)

        registered.captured.onCapabilitiesChanged(
            network,
            capabilities(internet = true, transport = NetworkCapabilities.TRANSPORT_CELLULAR),
        )

        assertEquals(listOf(false, true), changes)
        assertTrue(monitor.isConnected())
        assertEquals("cellular", monitor.networkType)
    }

    // Below API 26 a returning network may report onAvailable alone.
    @Test
    fun `a network that comes back with onAvailable alone is seen`() {
        val monitor = monitor()
        monitor.start(log)
        active(null)
        registered.captured.onLost(network)
        val back = mockk<Network>()
        every { manager.getNetworkCapabilities(back) } returns
            capabilities(internet = true, transport = NetworkCapabilities.TRANSPORT_CELLULAR)

        registered.captured.onAvailable(back)

        assertEquals(listOf(false, true), changes)
        assertTrue(monitor.isConnected())
        assertEquals("cellular", monitor.networkType)
    }

    @Test
    fun `internet without validation counts as connected`() {
        val monitor = monitor()
        monitor.start(log)

        registered.captured.onCapabilitiesChanged(
            network,
            capabilities(internet = true, transport = NetworkCapabilities.TRANSPORT_WIFI, validated = false),
        )

        assertTrue(monitor.isConnected())
    }

    @Test
    fun `a network without internet capability is not connected`() {
        val monitor = monitor()
        monitor.start(log)

        registered.captured.onCapabilitiesChanged(network, capabilities(internet = false, transport = NetworkCapabilities.TRANSPORT_WIFI))

        assertFalse(monitor.isConnected())
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `without ACCESS_NETWORK_STATE it is always connected, registers nothing and warns once`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val monitor = monitor()

        monitor.start(log)

        assertTrue(monitor.isConnected())
        assertNull(monitor.networkType)
        if (Build.VERSION.SDK_INT >= 24) verify(exactly = 0) { manager.registerDefaultNetworkCallback(any()) }
        verify(exactly = 0) { manager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) }
        assertTrue(warned("NETWORK_STATE_PERMISSION_MISSING"))
    }

    @Test
    fun `a registration that throws falls back to a query each time, with one warning`() {
        if (Build.VERSION.SDK_INT >=
            24
        ) {
            every { manager.registerDefaultNetworkCallback(any()) } throws SecurityException("too many callbacks")
        }
        every { manager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } throws
            SecurityException("too many callbacks")
        val monitor = monitor()

        monitor.start(log)
        assertTrue(monitor.isConnected())
        active(null)

        assertFalse(monitor.isConnected())
        assertEquals("none", monitor.networkType)
        assertTrue(changes.isEmpty())
        assertTrue(warned("NETWORK_CALLBACK_FAILED"))
    }

    @Test
    fun `before start it reports connected`() {
        assertTrue(monitor().isConnected())
    }

    @Test
    fun `the platform uses it`() {
        val platform = AndroidPlatform.create(app, AndroidProcessInfo(app))

        assertTrue(platform.networkMonitor is AndroidNetworkMonitor)
    }
}
