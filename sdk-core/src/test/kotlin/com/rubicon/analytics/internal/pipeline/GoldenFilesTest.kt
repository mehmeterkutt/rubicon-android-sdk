package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.InstalledApp
import com.rubicon.analytics.internal.storage.FileEventStore
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The wire contract of the automatic events and of the envelope (Serialization 5, step 8.4): each
 * golden file is compared structurally with what the real writer stores, and parsed by an
 * independent parser. Ids are random, so they are checked to be UUIDs and taken from the file; the
 * library version is taken from the file too, since it changes with every release.
 *
 * `Deep Link Opened` and `Network Request` get their properties in `sdk-android`, whose tests check
 * them against the same files; here the rest of those events is checked.
 */
class GoldenFilesTest {
    private val goldenDir = File("src/test/resources/golden")

    private fun golden(name: String): JsonObject = Json.parseToJsonElement(File(goldenDir, "$name.json").readText()).jsonObject

    private fun writerTest(
        configure: com.rubicon.analytics.AnalyticsConfig.() -> Unit = {},
        block: suspend TestScope.(WriterHarness) -> Unit,
    ) = runTest {
        val harness = WriterHarness(this, configure)
        harness.test.clock.set(T0)
        harness.test.contextProvider.staticValues = STATIC
        harness.test.contextProvider.callTimeValues = CALL_TIME
        try {
            block(harness)
        } finally {
            harness.stop()
        }
    }

    private fun WriterHarness.event(name: String): JsonObject =
        lines()
            .map { Json.parseToJsonElement(it).jsonObject }
            .single { (it["event"] ?: it["name"])!!.jsonPrimitive.content == name }

    private fun assertGolden(
        name: String,
        actual: JsonObject,
    ) {
        val expected = golden(name)
        assertEquals(expected, withStableValuesOf(expected, actual), name)
    }

    // The ids must be UUIDs and the library version present; their values come from the golden file.
    private fun withStableValuesOf(
        golden: JsonObject,
        actual: JsonObject,
    ): JsonObject {
        assertTrue(UUID.matches(actual["message_id"]!!.jsonPrimitive.content))
        assertTrue(UUID.matches(actual["device_id"]!!.jsonPrimitive.content))
        val context = actual["context"]!!.jsonObject
        val goldenContext = golden["context"]!!.jsonObject
        val session =
            context["session"]?.jsonObject?.let { session ->
                assertTrue(UUID.matches(session["id"]!!.jsonPrimitive.content))
                JsonObject(session + ("id" to goldenContext["session"]!!.jsonObject["id"]!!))
            }
        val library = context["library"]!!.jsonObject
        assertTrue(library["version"]!!.jsonPrimitive.content.isNotEmpty())
        val stableContext =
            JsonObject(
                context +
                    listOfNotNull(
                        session?.let { "session" to it },
                        "library" to JsonObject(library + ("version" to goldenContext["library"]!!.jsonObject["version"]!!)),
                    ),
            )
        return JsonObject(
            actual + mapOf("message_id" to golden["message_id"]!!, "device_id" to golden["device_id"]!!, "context" to stableContext),
        )
    }

    @Nested
    inner class `lifecycle and session events` {
        @Test
        fun `a first launch, a background and a return after the session timeout`() =
            writerTest { h ->
                h.test.processInfo.installed = InstalledApp("1.4.0", "140", INSTALLED_AT, INSTALLED_AT)
                h.writer.start()
                runCurrent()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()
                h.test.clock.advanceBy(65_000)
                h.test.elapsedClock.advanceBy(65_000)
                h.test.lifecycleSource.moveToBackground()
                runCurrent()
                assertGolden("session-started", h.event("Session Started"))
                assertGolden("application-installed", h.event("Application Installed"))
                assertGolden("application-opened", h.event("Application Opened"))
                assertGolden("application-backgrounded", h.event("Application Backgrounded"))

                h.test.clock.advanceBy(600_000)
                h.test.elapsedClock.advanceBy(600_000)
                h.test.lifecycleSource.moveToForeground()
                runCurrent()

                assertGolden("session-ended", h.event("Session Ended"))
            }

        @Test
        fun `an update from an earlier version`() =
            writerTest { h ->
                val log = Log(LogLevel.OFF, null, RecordingLogger())
                DeviceStore(h.keyValueStore, log, FakeClock(T0)) { "0191d0c2-1a2b-7c3d-9e4f-5a6b7c8d9e0f" }.apply {
                    load()
                    update { it.copy(lastVersion = "1.3.2", lastBuild = "132") }
                    persist()
                }
                h.test.processInfo.installed = InstalledApp("1.4.0", "140", INSTALLED_AT, UPDATED_AT)
                h.writer.start()
                runCurrent()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()

                assertGolden("application-updated", h.event("Application Updated"))
            }
    }

    @Nested
    inner class `events with properties from sdk-android` {
        private fun captureFrom(
            h: WriterHarness,
            name: String,
            gate: Autocapture,
        ) {
            val properties = Json.decodeFromJsonElement(JsonObject.serializer(), golden(name)["properties"]!!).toPlain()
            h.writer.enqueue(
                CapturedEvent(
                    CapturedEvent.Type.TRACK,
                    golden(name)["event"]!!.jsonPrimitive.content,
                    properties,
                    EventSource.AUTO,
                    "0192e4a3-2000-7000-8000-000000000001",
                    h.test.clock.currentTimeMillis(),
                    h.identity.snapshot(),
                    CALL_TIME,
                    gate = gate,
                ),
            )
        }

        @Test
        fun `Deep Link Opened`() =
            writerTest({ autocapture.enabled += Autocapture.DEEP_LINKS }) { h ->
                h.writer.start()
                runCurrent()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()
                captureFrom(h, "deep-link-opened", Autocapture.DEEP_LINKS)
                runCurrent()

                assertGolden("deep-link-opened", h.event("Deep Link Opened"))
            }

        @Test
        fun `Network Request`() =
            writerTest { h ->
                h.writer.start()
                runCurrent()
                h.test.lifecycleSource.moveToForeground()
                runCurrent()
                captureFrom(h, "network-request", Autocapture.NETWORK_REQUESTS)
                runCurrent()

                assertGolden("network-request", h.event("Network Request"))
            }
    }

    @Test
    fun `the envelope streams the stored lines unchanged after sent_at`(
        @TempDir root: File,
    ) {
        val store = FileEventStore(root)
        val log = Log(LogLevel.OFF, null, RecordingLogger())
        val clock = FakeClock(T0)
        store.open("rk_test", clock, log) { _, _ -> }
        val expected = golden("envelope")
        for (event in expected["batch"]!!.jsonArray) store.append(event.toString())
        store.closeOpenSegment()
        val body = ByteArrayOutputStream()

        store.writeBatch(store.closedSegments().single(), expected["sent_at"]!!.jsonPrimitive.content.toLong(), body)

        assertEquals(expected, Json.parseToJsonElement(body.toString(Charsets.UTF_8.name())))
    }

    @Test
    fun `every golden file is one JSON object to an independent parser`() {
        val files = goldenDir.listFiles { file -> file.extension == "json" }!!
        assertEquals(EXPECTED_FILES, files.map { it.nameWithoutExtension }.toSet())
        for (file in files) Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun JsonObject.toPlain(): Map<String, Any?> = mapValues { (_, value) -> value.toPlain() }

    private fun JsonElement.toPlain(): Any? =
        when (this) {
            is JsonObject -> toPlain()
            is JsonPrimitive ->
                when {
                    isString -> content
                    content == "null" -> null
                    content == "true" || content == "false" -> content.toBoolean()
                    else -> content.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: content.toDouble()
                }
            else -> jsonArray.map { it.toPlain() }
        }

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        // 2025-09-29T12:00:00.000Z
        const val T0 = 1_759_147_200_000L
        const val INSTALLED_AT = 1_759_060_800_000L
        const val UPDATED_AT = 1_759_140_000_000L

        val STATIC =
            StaticValues(
                appName = "Rubicon",
                appVersion = "1.4.0",
                appBuild = "140",
                appNamespace = "com.rubicon.demo",
                deviceManufacturer = "Google",
                deviceModel = "Pixel 8",
                deviceType = "phone",
                deviceIsEmulator = false,
                osName = "Android",
                osVersion = "16",
                osApiLevel = 36,
            )
        val CALL_TIME =
            CallTimeValues(
                screenWidth = 1080,
                screenHeight = 2400,
                screenDensity = 2.625,
                networkType = "wifi",
                networkCarrier = "Vodafone TR",
                locale = "tr-TR",
                timezone = "Europe/Istanbul",
            )

        val EXPECTED_FILES =
            setOf(
                "track",
                "screen",
                "identify",
                "sdk-diagnostics",
                "envelope",
                "application-installed",
                "application-updated",
                "application-opened",
                "application-backgrounded",
                "session-started",
                "session-ended",
                "deep-link-opened",
                "network-request",
            )
    }
}
