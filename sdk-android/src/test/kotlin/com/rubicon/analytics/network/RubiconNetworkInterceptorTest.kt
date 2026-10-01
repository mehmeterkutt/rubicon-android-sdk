package com.rubicon.analytics.network

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.AutomaticCapture
import com.rubicon.analytics.internal.NoOpAnalytics
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.testing.RecordingLogger
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Lifecycle 1 and 6, Privacy 5c; runs against OkHttp 4.12 and 5.x (-PokhttpTestMajor=5).
class RubiconNetworkInterceptorTest {
    private val server = MockWebServer()
    private val logger = RecordingLogger()

    private class Captured(
        val gate: Autocapture,
        val name: String,
        val properties: Map<String, Any?>,
    )

    private inner class FakeSdk(
        override var capturesNetworkRequests: Boolean = true,
        override val sdkHost: String = "api.rubicon.example",
        override val debug: Boolean = false,
        val throwing: Boolean = false,
    ) : Analytics by NoOpAnalytics,
        AutomaticCapture {
        val events = mutableListOf<Captured>()
        override val log: Log = Log(LogLevel.DEBUG, null, logger)

        override fun track(
            gate: Autocapture,
            name: String,
            properties: Map<String, Any?>,
        ) {
            if (throwing) throw IllegalStateException("broken")
            events += Captured(gate, name, properties)
        }

        override fun screen(
            gate: Autocapture,
            name: String,
            properties: Map<String, Any?>,
        ): Unit = Unit
    }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(
        interceptor: Interceptor,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ) = OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .apply(configure)
        .build()

    private fun host() = server.hostName

    private fun call(
        client: OkHttpClient,
        path: String,
        body: String? = null,
    ): String {
        val builder = Request.Builder().url(server.url(path))
        if (body != null) builder.post(body.toRequestBody("text/plain".toMediaType()))
        return client.newCall(builder.build()).execute().use { it.body!!.string() }
    }

    @Test
    fun `an event carries the decided fields, without query or fragment, ids masked`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse().setBody("hello"))

        val body = call(client(RubiconNetworkInterceptor(sdk, setOf(host()))), "/users/12345/orders?token=secret#frag", body = "abc")

        assertEquals("hello", body)
        val event = sdk.events.single()
        assertEquals(Autocapture.NETWORK_REQUESTS, event.gate)
        assertEquals("Network Request", event.name)
        val p = event.properties
        assertEquals("POST", p["method"])
        assertEquals("http://${host()}:${server.port}/users/:id/orders", p["url"])
        assertEquals(host(), p["host"])
        assertEquals("/users/:id/orders", p["path"])
        assertEquals(200, p["status_code"])
        assertTrue((p["duration_ms"] as Long) >= 0)
        assertEquals(3L, p["request_bytes"])
        assertEquals(5L, p["response_bytes"])
        assertFalse("error" in p)
        assertFalse(p.values.any { it.toString().contains("secret") || it.toString().contains("frag") })
        assertEquals(
            setOf("method", "url", "host", "path", "status_code", "duration_ms", "request_bytes", "response_bytes"),
            p.keys,
        )
    }

    @Test
    fun `ids are digits, UUIDs or hexadecimal longer than 16 characters`() {
        val p = NetworkRequestProperties

        assertTrue(p.isId("12345"))
        assertTrue(p.isId("3f2a9c1e-0b1c-4d2e-8f3a-7b2d9c1e0b1c"))
        assertTrue(p.isId("0123456789abcdef0"))
        assertFalse(p.isId("0123456789abcdef"))
        assertFalse(p.isId("search"))
        assertFalse(p.isId("v1"))

        val url = server.url("/files/3f2a9c1e-0b1c-4d2e-8f3a-7b2d9c1e0b1c/download/v1/products/search")
        assertEquals("/files/:id/download/v1/products/search", p.path(url, maskPathIds = true))
        assertEquals(url.encodedPath, p.path(url, maskPathIds = false))
    }

    @Test
    fun `masking can be switched off`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse())

        call(client(RubiconNetworkInterceptor(sdk, setOf(host()), maskPathIds = false)), "/users/12345")

        assertEquals("/users/12345", sdk.events.single().properties["path"])
    }

    @Test
    fun `an unconfigured host is not captured`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse())

        call(client(RubiconNetworkInterceptor(sdk, setOf("api.shop.com", "*.shop.com"))), "/")

        assertTrue(sdk.events.isEmpty())
    }

    @Test
    fun `hosts match exactly or by a leading wildcard, ignoring case`() {
        val patterns = HostPatterns.parse(setOf("API.shop.com", "*.cdn.example.com"), null)

        assertTrue(patterns.matches("api.shop.com"))
        assertTrue(patterns.matches("Api.Shop.Com"))
        assertFalse(patterns.matches("img.shop.com"))
        assertFalse(patterns.matches("shop.com"))
        assertTrue(patterns.matches("eu.cdn.example.com"))
        assertTrue(patterns.matches("a.b.cdn.example.com"))
        assertFalse(patterns.matches("cdn.example.com"))
        assertFalse(patterns.matches("evilcdn.example.com"))
    }

    @Test
    fun `an invalid host pattern warns and is ignored, and throws in debug`() {
        val invalid = listOf("*.com", "https://api.shop.com", "api.shop.com/v1", "api.shop.com:443", "*api.shop.com", "", "api.*.com")

        for (pattern in invalid) {
            val patterns = HostPatterns.parse(setOf(pattern), FakeSdk())
            assertFalse(pattern, patterns.matches("api.shop.com"))
            assertThrows(pattern, IllegalArgumentException::class.java) { RubiconNetworkInterceptor(FakeSdk(debug = true), setOf(pattern)) }
        }
        assertEquals(invalid.size, logger.lines.count { it.level == LogLevel.WARN && it.message.startsWith("INVALID_CAPTURE_HOST") })
    }

    @Test
    fun `a failed call is recorded with a short error and rethrown unchanged`() {
        val sdk = FakeSdk()
        val failure = UnknownHostException("nowhere.shop.com")
        val client =
            client(RubiconNetworkInterceptor(sdk, setOf("nowhere.shop.com"))) {
                dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = throw failure
                    },
                )
            }

        val thrown =
            assertThrows(
                UnknownHostException::class.java,
            ) { client.newCall(Request.Builder().url("http://nowhere.shop.com/a").build()).execute() }

        assertSame(failure, thrown)
        val p = sdk.events.single().properties
        assertEquals("unknown_host", p["error"])
        assertFalse("status_code" in p)
        assertFalse("response_bytes" in p)
    }

    @Test
    fun `a timeout and a refused connection have their own short names`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val timing = client(RubiconNetworkInterceptor(sdk, setOf(host()))) { readTimeout(200, TimeUnit.MILLISECONDS) }

        assertThrows(IOException::class.java) { call(timing, "/slow") }
        val port = server.port
        server.shutdown()
        assertThrows(IOException::class.java) {
            client(
                RubiconNetworkInterceptor(sdk, setOf(host())),
            ).newCall(Request.Builder().url("http://${host()}:$port/").build()).execute()
        }

        assertEquals(listOf("timeout", "connect"), sdk.events.map { it.properties["error"] })
    }

    @Test
    fun `a throwing SDK still returns the response, readable`() {
        server.enqueue(MockResponse().setBody("intact"))

        assertEquals("intact", call(client(RubiconNetworkInterceptor(FakeSdk(throwing = true), setOf(host()))), "/"))
    }

    @Test
    fun `the request is passed on untouched`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse())
        val original =
            Request
                .Builder()
                .url(server.url("/a"))
                .header("Authorization", "Bearer secret")
                .build()
        var seen: Request? = null
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(RubiconNetworkInterceptor(sdk, setOf(host())))
                .addInterceptor(Interceptor { chain -> chain.request().also { seen = it }.let(chain::proceed) })
                .build()

        client.newCall(original).execute().close()

        assertSame(original, seen)
        assertFalse(
            sdk.events
                .single()
                .properties.values
                .any { it.toString().contains("secret") },
        )
    }

    @Test
    fun `it passes through when not capturing, for the SDK's host and for another Analytics`() {
        server.enqueue(MockResponse(), MockResponse(), MockResponse())
        val off = FakeSdk(capturesNetworkRequests = false)
        val own = FakeSdk(sdkHost = host())

        call(client(RubiconNetworkInterceptor(off, setOf(host()))), "/")
        call(client(RubiconNetworkInterceptor(own, setOf(host()))), "/")
        call(client(RubiconNetworkInterceptor(NoOpAnalytics, setOf(host()))), "/")

        assertTrue(off.events.isEmpty())
        assertTrue(own.events.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `switching capture off applies to the next request`() {
        val sdk = FakeSdk()
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val client = client(RubiconNetworkInterceptor(sdk, setOf(host())))

        call(client, "/first")
        sdk.capturesNetworkRequests = false
        call(client, "/second")

        assertEquals(listOf("/first"), sdk.events.map { it.properties["path"] })
    }

    private fun MockWebServer.enqueue(vararg responses: MockResponse) = responses.forEach { enqueue(it) }
}
