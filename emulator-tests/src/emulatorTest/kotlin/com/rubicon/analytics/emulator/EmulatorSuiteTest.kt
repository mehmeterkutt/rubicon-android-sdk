package com.rubicon.analytics.emulator

import com.rubicon.analytics.emulator.FakeServer.Companion.label
import com.rubicon.analytics.emulator.FakeServer.Companion.messageId
import com.rubicon.analytics.emulator.FakeServer.Companion.property
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The emulator suite of step 8.1: what the JVM tests cannot show, on a real Android runtime. Each
 * test starts from a fresh install of the test app. Run it on the lowest supported API level and on
 * the latest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmulatorSuiteTest {
    private val device = Device.fromSystemProperties()
    private val server = FakeServer()
    private val apkDir = File(System.getProperty("apkDir"))

    private fun apk(
        flavor: String,
        buildType: String = "debug",
    ): File =
        File(apkDir, "$flavor/$buildType")
            .listFiles { file -> file.extension == "apk" }!!
            .single()

    @BeforeAll
    fun startServer() {
        server.start()
        device.reverse(DEVICE_PORT, server.port)
    }

    @AfterAll
    fun stopServer() {
        device.setNetwork(true)
        server.stop()
    }

    @BeforeEach
    fun freshInstall() {
        server.reset()
        install("full")
    }

    private fun install(
        flavor: String,
        buildType: String = "debug",
    ) {
        device.uninstall()
        device.install(apk(flavor, buildType))
    }

    private fun flush() = device.command("flush")

    private fun named(
        events: List<JsonObject>,
        name: String,
    ) = events.filter { label(it) == name }

    private fun awaitProcessGone(name: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (device.pidOf(name) != null) {
            check(System.currentTimeMillis() < deadline) { "$name is still running" }
            Thread.sleep(200)
        }
    }

    @Test
    fun `every event stored before the process was killed arrives, a repeat only with the same message id`() {
        device.command("track", "--ei count 500 --ei killAfterStored 100")
        awaitProcessGone(Device.PACKAGE)
        val stored =
            device
                .runAs("cat files/queued.txt")
                .lines()
                .map { it.trim() }
                .filter { UUID.matches(it) }
                .toSet()
        assertTrue(stored.size >= 100, "the SDK stored ${stored.size} events before the kill")

        // Restarting the process sends what the previous one left.
        val events = server.await(timeoutMs = 90_000, nudge = ::flush) { received -> received.map(::messageId).containsAll(stored) }

        val burst = named(events, "Burst")
        for ((id, copies) in burst.groupBy(::messageId)) {
            assertEquals(1, copies.map { it.toString() }.distinct().size, "copies of $id differ")
        }
        assertTrue(burst.map { property(it, "index")!!.toInt() }.all { it in 0 until 500 })
        println("Stored before the kill: ${stored.size} of 500; received: ${burst.map(::messageId).toSet().size}")
    }

    @Test
    fun `nothing is uploaded offline, and the network coming back sends the events`() {
        flush()
        Thread.sleep(2_000)
        try {
            device.setNetwork(false)
            Thread.sleep(3_000)
            device.command("track", "--es name Offline")
            flush()
            Thread.sleep(5_000)
            assertTrue(named(server.events(), "Offline").isEmpty(), "uploaded while offline")
        } finally {
            device.setNetwork(true)
        }

        server.await(timeoutMs = 60_000) { named(it, "Offline").isNotEmpty() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["full", "noStartup"])
    fun `foreground and background produce the lifecycle events, also without the Startup provider`(flavor: String) {
        install(flavor)

        device.startActivity("MainActivity")
        Thread.sleep(2_000)
        device.pressHome()

        val events = server.await { named(it, "Application Backgrounded").isNotEmpty() }
        val order = listOf("Session Started", "Application Installed", "Application Opened", "Main", "Application Backgrounded")
        assertEquals(order, events.map(::label).filter { it in order })
        assertTrue(events.all { it["source"]!!.jsonPrimitive.content == "auto" })
        assertEquals("false", property(named(events, "Application Opened").single(), "from_background"))
        assertEquals("class", property(named(events, "Main").single(), "name_source"))
    }

    @Test
    fun `the remote process creates no files and sends nothing`() {
        device.broadcast("RemoteReceiver")
        Thread.sleep(4_000)

        assertNotNull(device.pidOf("${Device.PACKAGE}:remote"), "the :remote process ran")
        assertNull(device.pidOf(Device.PACKAGE), "the main process never started")
        assertTrue(device.runAs("ls no_backup/rubicon").contains("No such file"), "no SDK directory")
        assertEquals(emptyList<String>(), server.requestPaths())
    }

    @Test
    fun `installing a higher version code reports Application Updated with the previous version`() {
        device.startActivity("MainActivity")
        device.pressHome()
        server.await { named(it, "Application Installed").isNotEmpty() }

        device.install(apk("fullV2"))
        device.startActivity("MainActivity")
        device.pressHome()

        val updated = named(server.await { named(it, "Application Updated").isNotEmpty() }, "Application Updated").single()
        assertEquals("2.0", property(updated, "version"))
        assertEquals("2", property(updated, "build"))
        assertEquals("1.0", property(updated, "previous_version"))
        assertEquals("1", property(updated, "previous_build"))
        assertFalse(updated["properties"]!!.jsonObject.containsKey("is_downgrade"))
    }

    @Test
    fun `creating the SDK on the main thread touches no disk under StrictMode`() {
        device.clearLogcat()

        device.startActivity("MainActivity")
        Thread.sleep(2_000)

        assertNotNull(device.pidOf(Device.PACKAGE), "the app survived the StrictMode death penalty")
        val log = device.logcat()
        assertFalse(log.contains("StrictMode policy violation"), "a StrictMode violation was logged")
        device.pressHome()
    }

    @Test
    fun `deep links carry only the allowed parts, a singleTop link included`() {
        device.openLink("rubicon-test://open/promo?utm_source=adb&token=secret#frag")
        Thread.sleep(1_500)
        device.openLink("rubicon-test://open/second?utm_campaign=fall")
        Thread.sleep(1_500)
        device.pressHome()

        val links = named(server.await { named(it, "Deep Link Opened").size >= 2 }, "Deep Link Opened")
        assertEquals(
            listOf(
                mapOf("scheme" to "rubicon-test", "host" to "open", "path" to "/promo", "utm_source" to "adb"),
                mapOf("scheme" to "rubicon-test", "host" to "open", "path" to "/second", "utm_campaign" to "fall"),
            ),
            links.map { link ->
                link["properties"]!!.jsonObject.filterKeys { it != "referrer" }.mapValues { it.value.jsonPrimitive.content }
            },
        )
        assertFalse(links.toString().contains("secret"))
    }

    @Test
    fun `the interceptor reports a call without query, ids masked`() {
        device.command("http")

        val request = named(server.await(nudge = ::flush) { named(it, "Network Request").isNotEmpty() }, "Network Request").single()
        assertEquals("GET", property(request, "method"))
        assertEquals("localhost", property(request, "host"))
        assertEquals("/api/users/:id/orders", property(request, "path"))
        assertEquals("http://localhost:$DEVICE_PORT/api/users/:id/orders", property(request, "url"))
        assertEquals("200", property(request, "status_code"))
        assertFalse(request.toString().contains("secret"))
    }

    // Serialization 5: the pinned org.json behaviours of step 2.4 on this device's runtime.
    @Test
    fun `org json on this runtime behaves as the JSON layer expects`() {
        device.command("json")
        val deadline = System.currentTimeMillis() + 15_000
        var text = ""
        while (System.currentTimeMillis() < deadline && !text.contains("line_32kb")) {
            Thread.sleep(300)
            text = device.runAs("cat files/json.txt")
        }
        val results = text.lines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }

        assertEquals("""{"coupon":null}""", results["null_kept"])
        assertEquals("{}", results["kotlin_null_removes"])
        assertEquals("""{"price":50}""", results["whole_double"])
        assertEquals("""{"url":"https:\/\/a.com\/b"}""", results["slash"])
        assertEquals("""{"x":1.0E21,"y":1.5E-7}""", results["exponent"])
        assertEquals("true", results["nan_throws"])
        assertEquals("0", results["opt_default"])
        assertEquals("true", results["round_trip"])
        assertEquals("true", results["special_round_trip"])
        assertEquals("true", results["line_32kb"])
        for (key in listOf("null_kept", "whole_double", "slash", "exponent", "special_line")) {
            Json.parseToJsonElement(results.getValue(key))
        }
    }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** The port the test app sends to; `adb reverse` maps it to the fake server. */
        const val DEVICE_PORT = 18765
    }
}
