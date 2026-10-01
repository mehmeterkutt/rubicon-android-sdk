package com.rubicon.analytics.integration

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.integration.IntegrationSupport.config
import com.rubicon.analytics.integration.IntegrationSupport.events
import com.rubicon.analytics.integration.IntegrationSupport.platform
import com.rubicon.analytics.internal.AutomaticCapture
import com.rubicon.analytics.internal.DefaultAnalytics
import com.rubicon.analytics.internal.NoOpAnalytics
import com.rubicon.analytics.internal.RubiconCore
import com.rubicon.analytics.internal.util.Hashing
import com.rubicon.analytics.testing.FakeNetworkMonitor
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CoreIntegrationTest {
    @TempDir
    lateinit var root: File

    private val server = MockWebServer()
    private val backend = FakeBackend()

    @BeforeEach
    fun start() {
        RubiconCore.resetForTests()
        server.dispatcher = backend
        server.start()
    }

    @AfterEach
    fun stop() {
        RubiconCore.resetForTests()
        server.shutdown()
    }

    private fun create(
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        logger: RecordingLogger = RecordingLogger(),
        configure: AnalyticsConfig.() -> Unit = {},
    ): DefaultAnalytics = RubiconCore.create(config(server, configure), platform(root, network, logger = logger)) as DefaultAnalytics

    private fun names(request: RecordedRequest) =
        events(request).map {
            it["event"]?.jsonPrimitive?.content
                ?: it["type"]!!.jsonPrimitive.content
        }

    @Test
    fun `tracked events reach the server through real files`() {
        backend.enqueue(MockResponse())
        val analytics = create()

        analytics.identify("user-42", mapOf("plan" to "pro"))
        analytics.track("Signed Up", mapOf("method" to "email"))
        analytics.screen("checkout")

        assertTrue(analytics.flushBlocking(10_000))
        val request = backend.nextUpload()
        assertEquals("Bearer ${IntegrationSupport.API_KEY}", request.getHeader("Authorization"))
        assertEquals(listOf("identify", "Signed Up", "screen"), names(request))
        assertTrue(events(request).all { it["user_id"]!!.jsonPrimitive.content == "user-42" })
        assertEquals(analytics.deviceId, events(request).first()["device_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `automatic events are sent with source auto, only while their type is enabled`() {
        backend.enqueue(MockResponse())
        var capture: AutomaticCapture? = null
        val analytics =
            RubiconCore.create(config(server), platform(root, FakeNetworkMonitor())) { capture = it } as DefaultAnalytics

        capture!!.screen(Autocapture.SCREEN_VIEWS, "Checkout", mapOf("name_source" to "class"))
        // DEEP_LINKS is off by default (Lifecycle 6).
        capture!!.track(Autocapture.DEEP_LINKS, "Deep Link Opened", mapOf("scheme" to "https"))

        assertTrue(analytics.flushBlocking(10_000))
        val events = events(backend.nextUpload())
        assertEquals(listOf("screen"), events.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("Checkout", events.single()["name"]!!.jsonPrimitive.content)
        assertEquals("auto", events.single()["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a bad automatic name only warns, even in debug`() {
        val logger = RecordingLogger()
        var capture: AutomaticCapture? = null
        RubiconCore.create(config(server) { debug = true }, platform(root, FakeNetworkMonitor(), logger = logger)) { capture = it }

        capture!!.screen(Autocapture.SCREEN_VIEWS, "", emptyMap())

        assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("INVALID_EVENT_NAME") })
    }

    @Test
    fun `automatic capture is attached once, to a new instance only`() {
        var attached = 0
        RubiconCore.create(config(server), platform(root, FakeNetworkMonitor())) { attached++ }
        RubiconCore.create(config(server), platform(root, FakeNetworkMonitor())) { attached++ }
        RubiconCore.resetForTests()
        RubiconCore.create(config(server) { host = "not a host" }, platform(root, FakeNetworkMonitor())) { attached++ }

        assertEquals(1, attached)
    }

    @Test
    fun `offline then online`() {
        backend.enqueue(MockResponse())
        val network = FakeNetworkMonitor(connected = false)
        val analytics = create(network)

        analytics.track("Offline")
        assertFalse(analytics.flushBlocking(500))
        assertEquals(0, backend.uploads.size)

        network.setConnected(true)

        assertTrue(analytics.flushBlocking(10_000))
        assertEquals(listOf("Offline"), names(backend.nextUpload()))
    }

    @Test
    fun `503 then 200 sends the same batch again`() {
        backend.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "1"))
        backend.enqueue(MockResponse())
        val analytics = create()

        analytics.track("Retried")

        assertTrue(analytics.flushBlocking(15_000))
        val first = events(backend.nextUpload())
        val second = events(backend.nextUpload())
        assertEquals(first.map { it["message_id"] }, second.map { it["message_id"] })
    }

    @Test
    fun `a restart sends what the previous process left`() {
        val offline = FakeNetworkMonitor(connected = false)
        val first = create(offline)
        first.track("Left", mapOf("n" to 1))
        first.track("Left", mapOf("n" to 2))
        assertFalse(first.flushBlocking(500))
        RubiconCore.resetForTests()

        backend.enqueue(MockResponse())
        val second = create()

        assertTrue(second.flushBlocking(10_000))
        assertEquals(listOf("Left", "Left"), names(backend.nextUpload()))
        assertEquals(first.deviceId, second.deviceId)
    }

    @Test
    fun `a crash leftover is recovered and its whole lines are sent`() {
        val dir = File(root, "events/v1/${Hashing.sha256Hex(IntegrationSupport.API_KEY).take(16)}").apply { mkdirs() }
        val now = System.currentTimeMillis()
        File(dir, "0000000004.tmp").writeText(
            """{"type":"track","event":"Before Crash","source":"app","message_id":"a","timestamp":$now}""" + "\n" +
                """{"type":"track","event":"Half""",
        )
        backend.enqueue(MockResponse())
        val analytics = create()

        assertTrue(analytics.flushBlocking(10_000))
        assertEquals(listOf("Before Crash"), names(backend.nextUpload()))
    }

    @Test
    fun `a second instance returns the first in release and throws in debug`() {
        val logger = RecordingLogger()
        val first = create(logger = logger)

        val second = RubiconCore.create(config(server), platform(root, logger = logger))

        assertSame(first, second)
        assertTrue(logger.lines.any { it.level == LogLevel.ERROR && it.message.startsWith("SECOND_INSTANCE") })
        assertThrows<IllegalStateException> { RubiconCore.create(config(server) { debug = true }, platform(root)) }
    }

    @Test
    fun `an unusable configuration gives the no-op instance in release and throws in debug`() {
        val logger = RecordingLogger()

        val analytics: Analytics = RubiconCore.create(AnalyticsConfig(" "), platform(root, logger = logger))

        assertSame(NoOpAnalytics, analytics)
        assertTrue(logger.lines.any { it.level == LogLevel.ERROR && it.message.startsWith("INVALID_API_KEY") })
        assertThrows<IllegalArgumentException> { RubiconCore.create(AnalyticsConfig(" ").apply { debug = true }, platform(root)) }
    }

    @Test
    fun `the configuration is frozen after creation`() {
        val logger = RecordingLogger()
        val config = config(server)
        RubiconCore.create(config, platform(root, logger = logger))

        config.flush.at = 99

        assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("CONFIG_FROZEN") })
    }

    @Test
    fun `an invalid event name is dropped in release and throws in debug`() {
        backend.enqueue(MockResponse())
        val analytics = create()

        analytics.track(" ")
        analytics.track("Valid")

        assertTrue(analytics.flushBlocking(10_000))
        assertEquals(listOf("Valid"), names(backend.nextUpload()))
        RubiconCore.resetForTests()
        assertThrows<IllegalArgumentException> { create { debug = true }.track("") }
    }

    @Test
    fun `opted out, nothing new is sent and the choice survives a restart`() {
        val analytics = create()
        analytics.optOut()
        analytics.track("Hidden")
        analytics.flushBlocking(1_000)
        RubiconCore.resetForTests()

        val restarted = create()

        assertTrue(restarted.isOptedOut())
        restarted.track("Still hidden")
        restarted.flushBlocking(1_000)
        assertEquals(0, backend.uploads.size)
    }
}
