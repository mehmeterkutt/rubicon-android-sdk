package com.rubicon.analytics.emulator

import com.rubicon.analytics.emulator.FakeServer.Companion.label
import com.rubicon.analytics.emulator.FakeServer.Companion.property
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Step 8.2: the smoke subset of the suite on the minified release build, so R8 runs with only the
 * SDK's consumer rules. The release build is not debuggable: nothing here reads the app's files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseSmokeTest {
    private val device = Device.fromSystemProperties()
    private val server = FakeServer()
    private val release =
        File(System.getProperty("apkDir"), "full/release")
            .listFiles { file -> file.extension == "apk" }!!
            .single()

    @BeforeAll
    fun startServer() {
        server.start()
        device.reverse(DEVICE_PORT, server.port)
    }

    @AfterAll
    fun stopServer() {
        server.stop()
    }

    @BeforeEach
    fun freshInstall() {
        server.reset()
        device.uninstall()
        device.install(release)
    }

    private fun named(
        events: List<JsonObject>,
        name: String,
    ) = events.filter { label(it) == name }

    @Test
    fun `lifecycle, sessions and both screen names survive R8`() {
        device.startActivity("MainActivity")
        device.startActivity("CheckoutActivity")
        Thread.sleep(1_500)
        device.pressHome()

        val events = server.await { named(it, "Application Backgrounded").isNotEmpty() }
        val order = listOf("Session Started", "Application Installed", "Application Opened", "Main", "checkout", "Application Backgrounded")
        assertEquals(order, events.map(::label).filter { it in order })
        // The annotation is kept by the consumer rules; the class name by the manifest.
        assertEquals("annotation", property(named(events, "checkout").single(), "name_source"))
        assertEquals("class", property(named(events, "Main").single(), "name_source"))
    }

    @Test
    fun `the interceptor and deep links work in the minified app`() {
        device.command("http")
        device.openLink("rubicon-test://open/promo?utm_source=release")
        Thread.sleep(1_500)
        device.pressHome()

        val events =
            server.await(nudge = { device.command("flush") }) {
                named(it, "Network Request").isNotEmpty() && named(it, "Deep Link Opened").isNotEmpty()
            }
        assertEquals("/api/users/:id/orders", property(named(events, "Network Request").single(), "path"))
        assertEquals("release", property(named(events, "Deep Link Opened").single(), "utm_source"))
        assertTrue(events.none { it.toString().contains("secret") })
    }

    private companion object {
        const val DEVICE_PORT = 18765
    }
}
