package com.rubicon.analytics.internal.remote

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.config.ConfigValidator
import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.RemoteConfigCache
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeTransport
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

class RemoteConfigTest {
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)

    private fun code(change: AnalyticsConfig.() -> Unit = {}): ConfigSnapshot =
        ConfigValidator.validate(AnalyticsConfig("rk_test").apply(change)).snapshot!!

    private fun remote(
        json: String,
        code: ConfigSnapshot = code(),
    ) = RemoteConfig.from(RemoteConfig.parseBody(json)!!, code, log)

    private fun warnings() = logger.lines.filter { it.level == LogLevel.WARN && it.message.startsWith("REMOTE_CONFIG_INVALID_VALUE") }

    @Nested
    inner class `validation` {
        @Test
        fun `a full valid body restricts the code`() {
            val code = code()

            val remote =
                remote(
                    """{"enabled":false,"autocapture_off":["network_requests","sessions"],"flush_at":50,""" +
                        """"flush_interval_ms":120000,"sample_rate":0.5,"diagnostics_sample_rate":0}""",
                    code,
                )
            val restricted = remote.restrict(code)

            assertFalse(remote.enabled)
            assertEquals(setOf(Autocapture.NETWORK_REQUESTS, Autocapture.SESSIONS), remote.autocaptureOff)
            assertEquals(code.autocapture - setOf(Autocapture.NETWORK_REQUESTS, Autocapture.SESSIONS), restricted.autocapture)
            assertEquals(50, restricted.flushAt)
            assertEquals(120_000L, restricted.flushIntervalMs)
            assertEquals(0.5, remote.sampleRate)
            assertEquals(0.0, remote.diagnosticsSampleRate)
            assertTrue(warnings().isEmpty())
        }

        @Test
        fun `an empty body changes nothing`() {
            val code = code()

            assertEquals(code, remote("{}", code).restrict(code))
            assertEquals(RemoteConfig.NONE, remote("{}", code))
        }

        @Test
        fun `remote cannot switch on an autocapture the code switched off`() {
            val code = code { autocapture.enabled = setOf(Autocapture.SESSIONS) }

            val restricted = remote("""{"autocapture_off":[]}""", code).restrict(code)

            assertEquals(setOf(Autocapture.SESSIONS), restricted.autocapture)
        }

        @Test
        fun `flushing can only become less frequent`() {
            val code =
                code {
                    flush.at = 40
                    flush.intervalMs = 90_000
                }

            val lower = remote("""{"flush_at":20,"flush_interval_ms":30000}""", code).restrict(code)
            val higher = remote("""{"flush_at":40,"flush_interval_ms":600000}""", code).restrict(code)

            assertEquals(40, lower.flushAt)
            assertEquals(90_000L, lower.flushIntervalMs)
            assertEquals(40, higher.flushAt)
            assertEquals(600_000L, higher.flushIntervalMs)
            assertEquals(2, warnings().size)
        }

        @Test
        fun `values outside the bounds are ignored`() {
            val remote = remote("""{"flush_at":101,"flush_interval_ms":600001,"sample_rate":1.5,"diagnostics_sample_rate":-0.1}""")

            assertEquals(RemoteConfig.NONE, remote)
            assertEquals(4, warnings().size)
        }

        @Test
        fun `diagnostics cannot be switched on when the code switched them off`() {
            val code = code { logging.sendDiagnostics = false }

            assertNull(remote("""{"diagnostics_sample_rate":0.5}""", code).diagnosticsSampleRate)
            assertEquals(0.0, remote("""{"diagnostics_sample_rate":0}""", code).diagnosticsSampleRate)
        }

        @Test
        fun `values of the wrong type are ignored with a warning, the rest still applies`() {
            val remote =
                remote(
                    """{"enabled":"false","autocapture_off":"sessions","flush_at":50.5,"flush_interval_ms":"60000",""" +
                        """"sample_rate":true,"diagnostics_sample_rate":0.25}""",
                )

            assertTrue(remote.enabled)
            assertEquals(emptySet<Autocapture>(), remote.autocaptureOff)
            assertNull(remote.flushAt)
            assertNull(remote.flushIntervalMs)
            assertNull(remote.sampleRate)
            assertEquals(0.25, remote.diagnosticsSampleRate)
            assertEquals(5, warnings().size)
        }

        @Test
        fun `a list with a non-string entry is invalid as a whole`() {
            assertEquals(emptySet<Autocapture>(), remote("""{"autocapture_off":["sessions",3]}""").autocaptureOff)
            assertEquals(1, warnings().size)
        }

        @Test
        fun `unknown keys and autocapture types are ignored quietly`() {
            val remote = remote("""{"replay_rate":0.1,"autocapture_off":["taps","deep_links"]}""")

            assertEquals(setOf(Autocapture.DEEP_LINKS), remote.autocaptureOff)
            assertTrue(warnings().isEmpty())
            assertTrue(logger.lines.any { it.level == LogLevel.DEBUG && it.message.contains("\"replay_rate\"") })
            assertTrue(logger.lines.any { it.level == LogLevel.DEBUG && it.message.contains("\"taps\"") })
        }

        @Test
        fun `whole-number rates are accepted`() {
            assertEquals(1.0, remote("""{"sample_rate":1}""").sampleRate)
            assertEquals(0.0, remote("""{"sample_rate":0}""").sampleRate)
        }

        @Test
        fun `a body that is not one JSON object is not parsed`() {
            assertNull(RemoteConfig.parseBody("[1,2]"))
            assertNull(RemoteConfig.parseBody("{\"enabled\":"))
            assertNull(RemoteConfig.parseBody("<html>"))
            assertNull(RemoteConfig.parseBody("{} trailing"))
        }
    }

    @Nested
    inner class `sampling` {
        @Test
        fun `is stable for a device id`() {
            repeat(100) {
                val id = "device-$it"
                val first = Sampling.isIn(id, 0.5, Sampling.Purpose.EVENTS)
                repeat(3) { assertEquals(first, Sampling.isIn(id, 0.5, Sampling.Purpose.EVENTS)) }
            }
        }

        @Test
        fun `splits a population close to the rate`() {
            val ids = (0 until 20_000).map { "0192e4a1-7c3f-7a2b-8d4e-${it.toString().padStart(12, '0')}" }

            listOf(0.01, 0.1, 0.5, 0.9).forEach { rate ->
                val share = ids.count { Sampling.isIn(it, rate, Sampling.Purpose.EVENTS) } / ids.size.toDouble()
                assertEquals(rate, share, 0.015, "rate $rate")
            }
        }

        @Test
        fun `rates of 0 and 1 are exact`() {
            assertTrue(Sampling.isIn("any", 1.0, Sampling.Purpose.EVENTS))
            assertFalse(Sampling.isIn("any", 0.0, Sampling.Purpose.EVENTS))
        }

        @Test
        fun `a device inside a lower rate is inside every higher rate`() {
            (0 until 1_000).map { "d$it" }.forEach { id ->
                if (Sampling.isIn(id, 0.2, Sampling.Purpose.EVENTS)) assertTrue(Sampling.isIn(id, 0.6, Sampling.Purpose.EVENTS))
            }
        }

        @Test
        fun `events and diagnostics draw independently`() {
            val ids = (0 until 10_000).map { "d$it" }
            val both = ids.count { Sampling.isIn(it, 0.5, Sampling.Purpose.EVENTS) && Sampling.isIn(it, 0.5, Sampling.Purpose.DIAGNOSTICS) }

            assertEquals(0.25, both / ids.size.toDouble(), 0.02)
        }
    }

    @Nested
    inner class `the client` {
        private val keyValueStore = InMemoryKeyValueStore()
        private val transport = FakeTransport()
        private val flags = RuntimeFlags()
        private val code = code()
        private val store = deviceStore()

        private fun deviceStore() = DeviceStore(keyValueStore, log, FakeClock()) { "device-1" }.apply { load() }

        private fun client(
            store: DeviceStore = this.store,
            flags: RuntimeFlags = this.flags,
            code: ConfigSnapshot = this.code,
        ) = RemoteConfigClient(code, store, transport, flags, log)

        private fun ok(
            body: String,
            etag: String? = "\"v1\"",
        ) = HttpResult.Success(200, body, etag)

        private fun failedWarning() = logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("REMOTE_CONFIG_FAILED") }

        @Test
        fun `200 saves the values and the ETag for the next launch`() {
            transport.enqueue(ok("""{"flush_at":60}"""))

            client().fetch()

            val request = transport.requests.single()
            assertEquals("https://api.rubicon.example/v1/config", request.url)
            assertEquals(emptyMap<String, String>(), request.headers)
            assertEquals(RemoteConfigCache("\"v1\"", mapOf("flush_at" to 60)), store.remoteConfig)
        }

        @Test
        fun `a restart applies the saved values`() {
            transport.enqueue(ok("""{"flush_at":60,"autocapture_off":["screen_views"]}"""))
            client().fetch()
            store.persist()

            val effective = client(deviceStore(), RuntimeFlags()).startup()

            assertEquals(60, effective.snapshot.flushAt)
            assertFalse(Autocapture.SCREEN_VIEWS in effective.snapshot.autocapture)
            assertTrue(effective.collecting)
        }

        @Test
        fun `the saved ETag is sent and 304 keeps the values`() {
            store.updateRemoteConfig(RemoteConfigCache("\"v1\"", mapOf("flush_at" to 60)))
            transport.enqueue(HttpResult.HttpError(304, null, null))

            client().fetch()

            assertEquals(mapOf("If-None-Match" to "\"v1\""), transport.requests.single().headers)
            assertEquals(RemoteConfigCache("\"v1\"", mapOf("flush_at" to 60)), store.remoteConfig)
            assertFalse(failedWarning())
        }

        @Test
        fun `404 means no remote values and deletes the saved ones`() {
            store.updateRemoteConfig(RemoteConfigCache("\"v1\"", mapOf("flush_at" to 60)))
            transport.enqueue(HttpResult.HttpError(404, null, null))

            client().fetch()

            assertNull(store.remoteConfig)
            assertFalse(failedWarning())
        }

        @Test
        fun `5xx, a network failure or a malformed body keep the last valid values with one warning`() {
            val saved = RemoteConfigCache("\"v1\"", mapOf("flush_at" to 60))
            store.updateRemoteConfig(saved)
            transport.enqueue(
                HttpResult.HttpError(503, null, null),
                HttpResult.NetworkFailure(FailureKind.DNS, IOException("offline")),
                ok("<html>"),
                HttpResult.Success(200),
            )

            repeat(4) { client().fetch() }

            assertEquals(saved, store.remoteConfig)
            assertEquals(1, logger.lines.count { it.level == LogLevel.WARN && it.message.startsWith("REMOTE_CONFIG_FAILED") })
        }

        @Test
        fun `the kill switch stops collection at once and stays off at the next launch`() {
            transport.enqueue(ok("""{"enabled":false}"""))

            client().fetch()

            assertFalse(flags.collecting)
            store.persist()
            assertFalse(client(deviceStore(), RuntimeFlags()).startup().collecting)
        }

        @Test
        fun `switching collection back on waits for the next launch`() {
            store.updateRemoteConfig(RemoteConfigCache(null, mapOf("enabled" to false)))
            val running = client()
            assertFalse(running.startup().collecting)
            transport.enqueue(ok("""{"enabled":true}"""))

            running.fetch()

            assertFalse(flags.collecting)
            assertTrue(client(store, RuntimeFlags()).startup().collecting)
        }

        @Test
        fun `switching off network requests applies at once, other types at the next launch`() {
            transport.enqueue(ok("""{"autocapture_off":["network_requests","screen_views"]}"""))
            val running = client()
            val before = running.startup()

            running.fetch()

            assertFalse(flags.capturingNetworkRequests)
            assertTrue(Autocapture.SCREEN_VIEWS in before.snapshot.autocapture)
        }

        @Test
        fun `network requests switched off in code clear the flag at startup`() {
            val flags = RuntimeFlags()

            client(flags = flags, code = code { autocapture.enabled -= Autocapture.NETWORK_REQUESTS }).startup()

            assertFalse(flags.capturingNetworkRequests)
        }

        @Test
        fun `a device outside the sample rate collects nothing`() {
            store.updateRemoteConfig(RemoteConfigCache(null, mapOf("sample_rate" to 0)))

            val effective = client().startup()

            assertFalse(effective.collecting)
            assertFalse(flags.collecting)
        }

        @Test
        fun `diagnostics follow the code switch and their own sample rate`() {
            assertTrue(client().startup().sendsDiagnostics)
            store.updateRemoteConfig(RemoteConfigCache(null, mapOf("diagnostics_sample_rate" to 0)))
            assertFalse(client().startup().sendsDiagnostics)
            assertFalse(client(code = code { logging.sendDiagnostics = false }).startup().sendsDiagnostics)
        }

        @Test
        fun `saved values are validated again against the code of this launch`() {
            store.updateRemoteConfig(RemoteConfigCache(null, mapOf("flush_at" to 30)))

            val stricter = client(code = code { flush.at = 50 }).startup()

            assertEquals(50, stricter.snapshot.flushAt)
        }

        @Test
        fun `the fetch needs no consent, since it carries no personal data`() {
            transport.enqueue(ok("{}"))

            client().fetch()

            val request = transport.requests.single()
            assertNull(request.body)
            assertEquals(HttpRequest.Method.GET, request.method)
        }
    }
}
