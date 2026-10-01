package com.rubicon.analytics.internal.http

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeElapsedClock
import com.rubicon.analytics.testing.FakeProcessInfo
import com.rubicon.analytics.testing.RecordingLogger
import com.rubicon.analytics.testing.TestPlatform
import kotlinx.coroutines.Dispatchers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class HttpURLConnectionTransportTest {
    private val server = MockWebServer()
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val clock = FakeClock(NOW)
    private val processInfo = FakeProcessInfo()
    private val body = """{"sent_at":1,"batch":[{"type":"track","event":"Purchase Completed"}]}""".toByteArray()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.shutdown()

    private fun transport(
        headers: Map<String, String> = emptyMap(),
        timeouts: HttpURLConnectionTransport.Timeouts = HttpURLConnectionTransport.Timeouts(2_000, 2_000, 5_000),
        resolve: (String) -> List<InetAddress> = { throw UnknownHostException(it) },
        compress: (ByteArray) -> ByteArray = ::gzip,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ) = HttpURLConnectionTransport(
        "rk_test_key",
        headers,
        log,
        processInfo,
        clock,
        FakeElapsedClock(),
        Dispatchers.Default,
        timeouts,
        resolve,
        compress,
        openConnection,
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun post(
        transport: HttpURLConnectionTransport = transport(),
        path: String = "/v1/batch",
    ) = transport.execute(HttpRequest(HttpRequest.Method.POST, server.url(path).toString(), body = body))

    private fun get(
        transport: HttpURLConnectionTransport = transport(),
        headers: Map<String, String> = emptyMap(),
    ) = transport.execute(HttpRequest(HttpRequest.Method.GET, server.url("/v1/config").toString(), headers))

    private fun logged(code: Code) = logger.messages.any { it.startsWith("${code.name}:") }

    @Nested
    inner class `the request` {
        @Test
        fun `posts the gzipped body with a fixed length and the SDK's headers`() {
            server.enqueue(MockResponse().setResponseCode(200))

            assertEquals(HttpResult.Success(200), post(transport(headers = mapOf("X-Gateway-Token" to "gw"))))

            val request = server.takeRequest()
            assertEquals("POST /v1/batch HTTP/1.1", request.requestLine)
            assertEquals("Bearer rk_test_key", request.getHeader("Authorization"))
            assertEquals("rubicon-android/0.1.0 (Android 16; API 36)", request.getHeader("User-Agent"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            assertEquals("gw", request.getHeader("X-Gateway-Token"))
            assertEquals(request.bodySize.toString(), request.getHeader("Content-Length"))
            assertNull(request.getHeader("Transfer-Encoding"))
            assertEquals(String(body), GZIPInputStream(request.body.inputStream()).readBytes().decodeToString())
        }

        @Test
        fun `custom headers never replace the SDK's own, in any letter case`() {
            server.enqueue(MockResponse())
            val sneaky =
                mapOf(
                    "authorization" to "Bearer stolen",
                    "USER-AGENT" to "evil",
                    "Content-Type" to "text/plain",
                    "content-encoding" to "identity",
                    "X-Rubicon-Debug" to "1",
                    "X-Allowed" to "yes",
                )

            post(transport(headers = sneaky))

            val request = server.takeRequest()
            assertEquals("Bearer rk_test_key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("User-Agent")!!.startsWith("rubicon-android/"))
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            assertNull(request.getHeader("X-Rubicon-Debug"))
            assertEquals("yes", request.getHeader("X-Allowed"))
        }

        @Test
        fun `a GET sends no body and carries If-None-Match`() {
            server.enqueue(MockResponse().setResponseCode(304))

            assertEquals(HttpResult.HttpError(304, "", null), get(headers = mapOf("If-None-Match" to "\"abc\"")))

            val request = server.takeRequest()
            assertEquals("GET /v1/config HTTP/1.1", request.requestLine)
            assertEquals("\"abc\"", request.getHeader("If-None-Match"))
            assertEquals(0L, request.bodySize)
            assertFalse(logged(Code.HTTP_REDIRECT))
        }

        @Test
        fun `a failed compression sends the body uncompressed with a warning`() {
            server.enqueue(MockResponse())

            post(transport(compress = { throw IllegalStateException("no deflater") }))

            val request = server.takeRequest()
            assertNull(request.getHeader("Content-Encoding"))
            assertEquals(String(body), request.body.readUtf8())
            assertTrue(logged(Code.COMPRESSION_FAILED))
        }

        @Test
        fun `bodies are never logged and the DEBUG line names the request id`() {
            server.enqueue(MockResponse().setResponseCode(500).setBody("secret detail").addHeader("X-Request-Id", "req-42"))

            post()

            assertTrue(logger.messages.any { it.contains("POST /v1/batch: 500") && it.contains("request id req-42") })
            assertFalse(logger.messages.any { it.contains("Purchase Completed") || it.contains("secret detail") })
        }
    }

    @Nested
    inner class `responses` {
        @ParameterizedTest
        @ValueSource(ints = [200, 201, 202, 204])
        fun `2xx is a success`(code: Int) {
            server.enqueue(MockResponse().setResponseCode(code))

            assertEquals(HttpResult.Success(code), post())
        }

        @ParameterizedTest
        @ValueSource(ints = [400, 401, 403, 404, 408, 410, 413, 422, 500, 501, 502, 503, 504, 505])
        fun `every other status is an HTTP error with its code and body`(code: Int) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("reason $code"))

            val result = post() as HttpResult.HttpError

            assertEquals(code, result.code)
            assertNull(result.retryAfterMs)
            // The JDK withholds the body of a 401 to a streamed request (HttpRetryException); the body
            // is for logs only, so null is fine. Android's HttpURLConnection returns it.
            if (code != 401) assertEquals("reason $code", result.body)
        }

        @Test
        fun `a redirect is not followed and logs its target without the query`() {
            server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "https://new.example/v1/batch?token=secret"))
            server.enqueue(MockResponse())

            val result = post()

            assertEquals(301, (result as HttpResult.HttpError).code)
            assertEquals(1, server.requestCount)
            val line = logger.lines.single { it.message.startsWith("HTTP_REDIRECT") }
            assertEquals(LogLevel.ERROR, line.level)
            assertTrue(line.message.contains("https://new.example/v1/batch"))
            assertFalse(line.message.contains("secret"))
        }

        @Test
        fun `a 1 MB error body is read up to 4 KB`() {
            server.enqueue(MockResponse().setResponseCode(400).setBody("x".repeat(1024 * 1024)))

            val result = post() as HttpResult.HttpError

            assertEquals(4096, result.body!!.length)
        }
    }

    @Nested
    inner class `Retry-After` {
        private fun retryAfter(value: String?): Long? {
            val response = MockResponse().setResponseCode(429)
            if (value != null) response.addHeader("Retry-After", value)
            server.enqueue(response)
            return (post() as HttpResult.HttpError).retryAfterMs
        }

        @Test
        fun `seconds`() = assertEquals(120_000L, retryAfter("120"))

        @Test
        fun `an HTTP date`() = assertEquals(60_000L, retryAfter("Wed, 01 Oct 2026 12:01:00 GMT"))

        @Test
        fun `a date in the past is negative and left to the retry layer`() =
            assertEquals(-60_000L, retryAfter("Wed, 01 Oct 2026 11:59:00 GMT"))

        @Test
        fun `invalid values and a missing header are null`() {
            assertNull(retryAfter("soon"))
            assertNull(retryAfter("-5"))
            assertNull(retryAfter("1.5"))
            assertNull(retryAfter("Wednesday, 01-Oct-26 12:01:00 GMT"))
            assertNull(retryAfter(null))
        }

        @Test
        fun `a huge number of seconds does not overflow`() {
            val transport = transport()

            assertEquals(Long.MAX_VALUE, transport.retryAfterMs("99999999999999999999"))
            assertEquals(Long.MAX_VALUE, transport.retryAfterMs("9223372036854775807"))
        }

        @Test
        fun `503 carries it too`() {
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "30"))

            // An empty error body gives no error stream: the body is null.
            assertEquals(HttpResult.HttpError(503, null, 30_000L), post())
        }
    }

    @Nested
    inner class `failures` {
        @Test
        fun `slow headers trip the read timeout`() {
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))

            val result = post(transport(timeouts = HttpURLConnectionTransport.Timeouts(1_000, 300, 10_000)))

            assertEquals(FailureKind.TIMEOUT, (result as HttpResult.NetworkFailure).kind)
        }

        @Test
        fun `a server that never answers trips the watchdog before the read timeout`() {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = System.nanoTime()

            val result = post(transport(timeouts = HttpURLConnectionTransport.Timeouts(1_000, 10_000, 300)))

            assertEquals(FailureKind.TIMEOUT, (result as HttpResult.NetworkFailure).kind)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
        }

        @Test
        fun `a refused connection is CONNECT`() {
            val url = server.url("/v1/batch").toString()
            server.shutdown()

            val result = transport().execute(HttpRequest(HttpRequest.Method.POST, url, body = body))

            assertEquals(FailureKind.CONNECT, (result as HttpResult.NetworkFailure).kind)
        }

        @Test
        fun `an unknown host is DNS`() {
            val result = transport().execute(HttpRequest(HttpRequest.Method.POST, "https://no-such-host.invalid/v1/batch", body = body))

            assertEquals(FailureKind.DNS, (result as HttpResult.NetworkFailure).kind)
            assertFalse(logged(Code.HOST_BLOCKED))
        }

        @Test
        fun `a host resolving to loopback after a failure is BLOCKED, logged once`() {
            val blocking = transport(resolve = { listOf(InetAddress.getByName("0.0.0.0"), InetAddress.getByName("127.0.0.1")) })

            repeat(2) {
                val result = blocking.execute(HttpRequest(HttpRequest.Method.POST, "https://blocked.invalid/v1/batch", body = body))
                assertEquals(FailureKind.BLOCKED, (result as HttpResult.NetworkFailure).kind)
            }

            assertEquals(1, logger.lines.count { it.level == LogLevel.WARN && it.message.startsWith("HOST_BLOCKED") })
        }

        @Test
        fun `a host with one public address is not blocked`() {
            val result =
                transport(resolve = { listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("93.184.216.34")) })
                    .execute(HttpRequest(HttpRequest.Method.POST, "https://partly.invalid/v1/batch", body = body))

            assertEquals(FailureKind.DNS, (result as HttpResult.NetworkFailure).kind)
        }

        @Test
        fun `localhost and IP hosts are never checked`() {
            var resolved = 0
            val transport =
                transport(resolve = {
                    resolved++
                    listOf(InetAddress.getByName("127.0.0.1"))
                })
            val url = server.url("/v1/batch")
            server.shutdown()

            val byIp = transport.execute(HttpRequest(HttpRequest.Method.POST, "http://127.0.0.1:${url.port}/v1/batch", body = body))
            val byName = transport.execute(HttpRequest(HttpRequest.Method.POST, "http://localhost:${url.port}/v1/batch", body = body))

            assertEquals(FailureKind.CONNECT, (byIp as HttpResult.NetworkFailure).kind)
            assertEquals(FailureKind.CONNECT, (byName as HttpResult.NetworkFailure).kind)
            assertEquals(0, resolved)
        }

        @Test
        fun `an untrusted certificate is TLS`() {
            val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse())

            val result = post()

            assertEquals(FailureKind.TLS, (result as HttpResult.NetworkFailure).kind)
        }

        @Test
        fun `an unexpected exception is UNEXPECTED with an error`() {
            val result = post(transport(openConnection = { throw SecurityException("INTERNET permission removed") }))

            assertEquals(FailureKind.UNEXPECTED, (result as HttpResult.NetworkFailure).kind)
            assertTrue(logger.lines.any { it.level == LogLevel.ERROR && it.message.startsWith("HTTP_UNEXPECTED") })
        }

        @Test
        fun `a malformed URL or a broken connection is IO`() {
            val malformed = transport().execute(HttpRequest(HttpRequest.Method.GET, "not a url"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val cut = post()

            assertEquals(FailureKind.IO, (malformed as HttpResult.NetworkFailure).kind)
            assertEquals(FailureKind.IO, (cut as HttpResult.NetworkFailure).kind)
        }

        @Test
        fun `the transport never throws, even when the stream fails mid-write`() {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))

            val result = post()

            assertInstanceOf(HttpResult.NetworkFailure::class.java, result)
        }
    }

    @Test
    fun `the production constructor takes its parts from the platform`() {
        val test = TestPlatform()
        server.enqueue(MockResponse())

        val transport = HttpURLConnectionTransport(TransportSettings("rk_platform", emptyMap(), log), test.platform)
        transport.execute(HttpRequest(HttpRequest.Method.GET, server.url("/v1/config").toString()))

        assertEquals("Bearer rk_platform", server.takeRequest().getHeader("Authorization"))
        assertInstanceOf(
            HttpURLConnectionTransport::class.java,
            HttpTransportFactory.DEFAULT.create(TransportSettings("k", emptyMap(), log), test.platform),
        )
    }

    private companion object {
        // Wed, 01 Oct 2026 12:00:00 GMT
        const val NOW = 1_790_856_000_000L
    }
}
