package com.rubicon.analytics.internal.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.testing.RecordingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.util.TimeZone

// Both sides of API 24 (data subscription, locale list) (Context 1 to 3, 6).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class AndroidContextProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val log = Log(LogLevel.DEBUG, null, RecordingLogger())
    private var now = 0L
    private val clock = ElapsedClock { now }
    private val defaultZone = TimeZone.getDefault()
    private val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @After
    fun restore() {
        TimeZone.setDefault(defaultZone)
    }

    private fun provider(
        context: Context = app,
        monitor: AndroidNetworkMonitor = AndroidNetworkMonitor(app),
    ) = AndroidContextProvider(context, monitor, clock)

    private fun ready(
        provider: AndroidContextProvider,
        disabled: Set<ContextField> = emptySet(),
    ): CallTimeValues {
        provider.refresh(disabled)
        return provider.callTimeValues(disabled)
    }

    private fun changeConfiguration(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        app.onConfigurationChanged(app.resources.configuration)
    }

    private fun version(
        name: String,
        code: Int,
    ) {
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.versionName = name
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode = code.toLong()
        } else {
            @Suppress("DEPRECATION")
            run { info.versionCode = code }
        }
    }

    @Test
    fun `app, device and OS values come from the package, Build and the configuration`() {
        version("1.4.0", 140)

        val values = provider().staticValues(emptySet())

        assertEquals(app.applicationInfo.loadLabel(app.packageManager).toString(), values.appName)
        assertEquals("1.4.0", values.appVersion)
        assertEquals("140", values.appBuild)
        assertEquals(app.packageName, values.appNamespace)
        assertEquals(Build.MANUFACTURER, values.deviceManufacturer)
        assertEquals(Build.MODEL, values.deviceModel)
        assertEquals("phone", values.deviceType)
        assertEquals(false, values.deviceIsEmulator)
        assertEquals("Android", values.osName)
        assertEquals(Build.VERSION.RELEASE, values.osVersion)
        assertEquals(Build.VERSION.SDK_INT, values.osApiLevel)
    }

    @Test
    fun `a tablet is told by its smallest width and a TV by leanback`() {
        RuntimeEnvironment.setQualifiers("sw720dp")
        assertEquals("tablet", provider().staticValues(emptySet()).deviceType)

        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertEquals("tv", provider().staticValues(emptySet()).deviceType)
    }

    @Test
    fun `emulators are recognised by their Build markers and phones are not`() {
        assertTrue(
            Emulator.isEmulator("google/sdk_gphone64_arm64/emu64a:14", "sdk_gphone64_arm64", "Google", "ranchu", "sdk_gphone64_arm64"),
        )
        assertTrue(Emulator.isEmulator("generic/sdk/generic:5.0", "Android SDK built for x86", "unknown", "goldfish", "sdk"))
        assertTrue(Emulator.isEmulator("Genymotion/vbox86p/vbox86p:9", "Pixel", "Genymotion", "vbox86", "vbox86p"))
        assertFalse(Emulator.isEmulator("google/husky/husky:14/AP1A/1:user/release-keys", "Pixel 8 Pro", "Google", "husky", "husky"))
        assertFalse(Emulator.isEmulator(null, null, null, null, null))
    }

    @Test
    fun `before the first refresh only the timezone is given, marked pending`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"))

        val values = provider().callTimeValues(emptySet())

        assertTrue(values.pending)
        assertEquals("Europe/Istanbul", values.timezone)
        assertNull(values.locale)
        assertNull(values.screenWidth)
    }

    @Test
    fun `the screen is the whole display with the short side as width, in either orientation`() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port-xxhdpi")
        val provider = provider()

        val portrait = ready(provider)
        changeConfiguration("+land")
        val landscape = ready(provider)
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(real)
        assertTrue("the display turned", real.widthPixels > real.heightPixels)

        assertFalse(portrait.pending)
        assertEquals(1080, portrait.screenWidth)
        assertTrue(portrait.screenHeight!! > portrait.screenWidth!!)
        assertEquals(3.0, portrait.screenDensity!!, 0.0)
        assertEquals(portrait.screenWidth, landscape.screenWidth)
        assertEquals(portrait.screenHeight, landscape.screenHeight)
    }

    @Test
    fun `a configuration change makes the values pending until the writer reads them again`() {
        RuntimeEnvironment.setQualifiers("tr-rTR")
        val provider = provider()
        assertEquals("tr-TR", ready(provider).locale)

        changeConfiguration("fr-rFR-sw720dp")

        assertTrue(provider.callTimeValues(emptySet()).pending)
        val after = ready(provider)
        assertFalse(after.pending)
        assertEquals("fr-FR", after.locale)
    }

    @Test
    fun `the timezone is read for every event`() {
        val provider = provider()
        provider.refresh(emptySet())

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"))
        assertEquals("Europe/Istanbul", provider.callTimeValues(emptySet()).timezone)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        assertEquals("America/New_York", provider.callTimeValues(emptySet()).timezone)
    }

    @Test
    fun `without ACCESS_NETWORK_STATE there is no network type and the carrier is read at most every 60 seconds`() {
        val monitor = AndroidNetworkMonitor(app).also { it.start(log) }
        shadowOf(telephony).setNetworkOperatorName("Turkcell")
        val provider = provider(monitor = monitor)

        val first = ready(provider)
        shadowOf(telephony).setNetworkOperatorName("Vodafone TR")
        now += 59_999
        val early = ready(provider)
        now += 1
        val late = ready(provider)

        assertNull(first.networkType)
        assertEquals("Turkcell", first.networkCarrier)
        assertEquals("Turkcell", early.networkCarrier)
        assertEquals("Vodafone TR", late.networkCarrier)
    }

    @Test
    @Config(sdk = [24, 35])
    fun `dual SIM reports the operator of the data SIM`() {
        shadowOf(telephony).setNetworkOperatorName("Turkcell")
        val dataSim = mockk<TelephonyManager> { every { networkOperatorName } returns "Vodafone TR" }
        shadowOf(telephony).setTelephonyManagerForSubscriptionId(2, dataSim)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(2)

        assertEquals("Vodafone TR", ready(provider()).networkCarrier)
    }

    @Test
    fun `a network change refreshes the type and the carrier, a signal change does not`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val manager = mockk<ConnectivityManager>(relaxed = true)
        val registered = slot<ConnectivityManager.NetworkCallback>()
        if (Build.VERSION.SDK_INT >= 24) every { manager.registerDefaultNetworkCallback(capture(registered)) } returns Unit
        every { manager.registerNetworkCallback(any<NetworkRequest>(), capture(registered)) } returns Unit
        val wifi = mockk<Network>()
        val cellular = mockk<Network>()
        every { manager.activeNetwork } returns wifi
        every { manager.getNetworkCapabilities(wifi) } returns capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        val monitor = AndroidNetworkMonitor(app) { manager }.also { it.start(log) }
        shadowOf(telephony).setNetworkOperatorName("Turkcell")
        val provider = provider(monitor = monitor)
        registered.captured.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        val before = ready(provider)

        shadowOf(telephony).setNetworkOperatorName("Vodafone TR")
        registered.captured.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        val sameNetwork = provider.callTimeValues(emptySet())
        registered.captured.onCapabilitiesChanged(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        val after = provider.callTimeValues(emptySet())

        assertEquals("wifi", before.networkType)
        assertEquals("Turkcell", before.networkCarrier)
        assertEquals("Turkcell", sameNetwork.networkCarrier)
        assertEquals("cellular", after.networkType)
        assertEquals("Vodafone TR", after.networkCarrier)
    }

    private fun capabilities(transport: Int) =
        mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasTransport(any()) } answers { firstArg<Int>() == transport }
        }

    @Test
    fun `each disabled field is left out`() {
        version("1.4.0", 140)
        shadowOf(telephony).setNetworkOperatorName("Turkcell")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"))
        val all = everyField(provider().staticValues(emptySet()), ready(provider()))
        // Without a started monitor there is no network type; everything else is known.
        assertEquals(listOf(ContextField.NETWORK_TYPE), ContextField.values().filter { all[it] == null })

        for (field in ContextField.values()) {
            val disabled = setOf(field)
            val p = provider()
            val values = everyField(p.staticValues(disabled), ready(p, disabled))
            assertNull("$field", values[field])
            assertNotNull("$field leaves the others", values[ContextField.OS_NAME.takeIf { field != it } ?: ContextField.APP_NAMESPACE])
        }
    }

    @Test
    fun `a disabled field is never read`() {
        val reading = ReadingContext(app)
        val p = provider(context = reading)
        val disabled = ContextField.values().toSet()

        p.staticValues(disabled)
        ready(p, disabled)

        assertTrue("services read: ${reading.services}", reading.services.isEmpty())
        assertEquals(0, reading.packageManagerReads)
    }

    @Test
    fun `a disabled carrier never touches the telephony service`() {
        val reading = ReadingContext(app)

        ready(provider(context = reading), setOf(ContextField.NETWORK_CARRIER))

        assertFalse(Context.TELEPHONY_SERVICE in reading.services)
        assertTrue(Context.DISPLAY_SERVICE in reading.services)
    }

    @Test
    fun `the platform uses it`() {
        assertTrue(AndroidPlatform.create(app, AndroidProcessInfo(app)).contextProvider is AndroidContextProvider)
    }

    private class ReadingContext(
        base: Context,
    ) : ContextWrapper(base) {
        val services = mutableListOf<String>()
        var packageManagerReads = 0

        override fun getSystemService(name: String): Any? {
            services += name
            return super.getSystemService(name)
        }

        override fun getPackageManager(): PackageManager {
            packageManagerReads++
            return super.getPackageManager()
        }
    }

    private fun everyField(
        s: StaticValues,
        c: CallTimeValues,
    ): Map<ContextField, Any?> =
        mapOf(
            ContextField.APP_NAME to s.appName,
            ContextField.APP_VERSION to s.appVersion,
            ContextField.APP_BUILD to s.appBuild,
            ContextField.APP_NAMESPACE to s.appNamespace,
            ContextField.DEVICE_MANUFACTURER to s.deviceManufacturer,
            ContextField.DEVICE_MODEL to s.deviceModel,
            ContextField.DEVICE_TYPE to s.deviceType,
            ContextField.DEVICE_IS_EMULATOR to s.deviceIsEmulator,
            ContextField.OS_NAME to s.osName,
            ContextField.OS_VERSION to s.osVersion,
            ContextField.OS_API_LEVEL to s.osApiLevel,
            ContextField.SCREEN_WIDTH to c.screenWidth,
            ContextField.SCREEN_HEIGHT to c.screenHeight,
            ContextField.SCREEN_DENSITY to c.screenDensity,
            ContextField.NETWORK_TYPE to c.networkType,
            ContextField.NETWORK_CARRIER to c.networkCarrier,
            ContextField.LOCALE to c.locale,
            ContextField.TIMEZONE to c.timezone,
        )
}
