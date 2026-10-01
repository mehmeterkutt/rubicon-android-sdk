package com.rubicon.analytics.internal.http

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.BuildInfo
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.platform.ProcessInfo
import com.rubicon.analytics.internal.util.HttpDates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLException

/**
 * The [HttpTransport] of HTTP 1 to 6, on the platform's `HttpURLConnection` with no dependency.
 *
 * - One attempt, never a retry. Never throws: every outcome is an [HttpResult].
 * - The body is gzipped in memory and sent with a fixed length, never chunked (HTTP 2). If
 *   compression fails, it is sent uncompressed with a WARN.
 * - Connect 15 s, read 20 s, and a watchdog that calls `disconnect()` after 45 s, since a blocked
 *   socket ignores interrupts and coroutine cancellation (HTTP 3).
 * - Headers (HTTP 5): `network.headers` first, then the SDK's own, which therefore always win:
 *   `Authorization: Bearer`, `User-Agent`, `Content-Type`, `Content-Encoding`; `Content-Length` comes
 *   from the fixed length. Redirects are not followed; a 3xx other than 304 logs an ERROR naming the
 *   target.
 * - After a `CONNECT` or `DNS` failure the host is resolved once: if every address is loopback or
 *   any-local, as ad blockers and private DNS answer, the result is `BLOCKED` (HTTP 6).
 * - Bodies are never logged; at most 4 KB of an error body is read, for the retry layer.
 */
@RubiconInternal
public class HttpURLConnectionTransport internal constructor(
    private val apiKey: String,
    customHeaders: Map<String, String>,
    private val log: Log,
    private val processInfo: ProcessInfo,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock,
    watchdogDispatcher: CoroutineDispatcher,
    private val timeouts: Timeouts = Timeouts.DEFAULT,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val compress: (ByteArray) -> ByteArray = ::gzip,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpTransport {
    public constructor(settings: TransportSettings, platform: Platform) : this(
        apiKey = settings.apiKey,
        customHeaders = settings.headers,
        log = settings.log,
        processInfo = platform.processInfo,
        clock = platform.clock,
        elapsedClock = platform.elapsedClock,
        watchdogDispatcher = platform.dispatchers.watchdog,
    )

    internal class Timeouts(
        val connectMs: Int,
        val readMs: Int,
        val callMs: Long,
    ) {
        companion object {
            val DEFAULT = Timeouts(connectMs = 15_000, readMs = 20_000, callMs = 45_000)
        }
    }

    // Reserved names are already removed by validation; dropping them again keeps the rule here too.
    private val customHeaders = customHeaders.filterKeys { !Headers.isReserved(it) }
    private val watchdogScope = CoroutineScope(watchdogDispatcher + SupervisorJob())

    private val userAgent: String
        get() = "rubicon-android/${BuildInfo.VERSION} (Android ${processInfo.osVersion}; API ${processInfo.apiLevel})"

    override fun execute(request: HttpRequest): HttpResult {
        val started = elapsedClock.elapsedRealtimeMillis()
        val timedOut = AtomicBoolean(false)
        var connection: HttpURLConnection? = null
        var watchdog: Job? = null
        val url =
            try {
                URL(request.url)
            } catch (e: IOException) {
                return failure(FailureKind.IO, e, request, null)
            }
        return try {
            val conn = openConnection(url)
            connection = conn
            conn.connectTimeout = timeouts.connectMs
            conn.readTimeout = timeouts.readMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.requestMethod = request.method.name
            for ((name, value) in customHeaders) conn.setRequestProperty(name, value)
            for ((name, value) in request.headers) conn.setRequestProperty(name, value)
            conn.setRequestProperty(Headers.AUTHORIZATION, "Bearer $apiKey")
            conn.setRequestProperty(Headers.USER_AGENT, userAgent)
            watchdog =
                watchdogScope.launch {
                    delay(timeouts.callMs)
                    timedOut.set(true)
                    conn.disconnect()
                }
            val sentBytes = request.body?.let { send(conn, it) } ?: 0
            val result = receive(conn, url)
            val elapsed = elapsedClock.elapsedRealtimeMillis() - started
            val requestId = conn.getHeaderField(Headers.REQUEST_ID)?.let { " (request id $it)" }.orEmpty()
            log.debug { "${request.method} ${url.path}: ${describe(result)} in $elapsed ms, $sentBytes bytes sent$requestId" }
            result
        } catch (e: Exception) {
            connection?.disconnect()
            failure(classify(e, timedOut.get()), e, request, url)
        } finally {
            watchdog?.cancel()
        }
    }

    /** Writes the body, compressed when possible; returns the bytes sent. */
    private fun send(
        conn: HttpURLConnection,
        body: ByteArray,
    ): Int {
        val compressed =
            try {
                compress(body)
            } catch (e: Exception) {
                log.warn(Code.COMPRESSION_FAILED, e) { "Could not compress a request; it is sent uncompressed" }
                null
            }
        val bytes = compressed ?: body
        conn.setRequestProperty(Headers.CONTENT_TYPE, Headers.JSON)
        if (compressed != null) conn.setRequestProperty(Headers.CONTENT_ENCODING, Headers.GZIP)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return bytes.size
    }

    private fun receive(
        conn: HttpURLConnection,
        url: URL,
    ): HttpResult {
        val code = conn.responseCode
        if (code < 0) throw IOException("Not a valid HTTP response")
        if (code in 200..299) {
            // The body of a success is never used; it is drained so the connection can be reused.
            drainQuietly { conn.inputStream }
            return HttpResult.Success(code)
        }
        val body = readBodyQuietly { if (code >= 400) conn.errorStream else conn.inputStream }
        if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
            val target = conn.getHeaderField(Headers.LOCATION)?.let(::withoutQuery) ?: "an unknown location"
            log.error(Code.HTTP_REDIRECT) { "${url.host} redirects ($code) to $target; set host to the final URL" }
        }
        return HttpResult.HttpError(code, body, retryAfterMs(conn.getHeaderField(Headers.RETRY_AFTER)))
    }

    /** `Retry-After` in milliseconds from now: seconds or an HTTP date (HTTP 4); `null` if absent or invalid. */
    internal fun retryAfterMs(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.all { it in '0'..'9' }) {
            val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE // a number too large still means "long"
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        return HttpDates.parse(text)?.let { it - clock.currentTimeMillis() }
    }

    private fun classify(
        e: Exception,
        timedOut: Boolean,
    ): FailureKind =
        when {
            timedOut || e is SocketTimeoutException -> FailureKind.TIMEOUT
            e is UnknownHostException -> FailureKind.DNS
            e is SSLException -> FailureKind.TLS
            e is ConnectException || e is NoRouteToHostException || e is PortUnreachableException -> FailureKind.CONNECT
            e is IOException -> FailureKind.IO
            else -> FailureKind.UNEXPECTED
        }

    private fun failure(
        kind: FailureKind,
        cause: Exception,
        request: HttpRequest,
        url: URL?,
    ): HttpResult {
        val host = url?.host
        val effective =
            if ((kind == FailureKind.CONNECT || kind == FailureKind.DNS) && host != null && isBlocked(host)) FailureKind.BLOCKED else kind
        when (effective) {
            FailureKind.BLOCKED ->
                log.warn(Code.HOST_BLOCKED) { "$host resolves to a local address, as ad blockers and private DNS do; uploads wait" }
            FailureKind.UNEXPECTED ->
                log.error(Code.HTTP_UNEXPECTED, cause) { "The HTTP stack threw ${cause.javaClass.name}" }
            else -> log.debug { "${request.method} ${url?.path.orEmpty()}: $effective (${cause.javaClass.simpleName})" }
        }
        return HttpResult.NetworkFailure(effective, cause)
    }

    private fun isBlocked(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || isIpLiteral(host)) return false
        val addresses =
            try {
                resolve(host)
            } catch (e: Exception) {
                return false
            }
        return addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress || it.isAnyLocalAddress }
    }

    private companion object {
        const val MAX_ERROR_BODY = 4 * 1024
        const val MAX_DRAIN = 64 * 1024
        val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size / 4 + 64)
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        fun isIpLiteral(host: String) = host.startsWith("[") || host.contains(':') || IPV4.matches(host)

        /** A redirect target without query or fragment, which may carry personal data (Logging 4a). */
        fun withoutQuery(location: String): String = location.substringBefore('#').substringBefore('?')

        fun describe(result: HttpResult): String =
            when (result) {
                is HttpResult.Success -> "${result.code}"
                is HttpResult.HttpError -> "${result.code}"
                is HttpResult.NetworkFailure -> "${result.kind}"
            }

        /** Reads up to 4 KB as text for the retry layer, drains up to 64 KB more, then closes. */
        inline fun readBodyQuietly(open: () -> InputStream?): String? =
            try {
                open()?.use { input ->
                    val buffer = ByteArray(MAX_ERROR_BODY)
                    var filled = 0
                    while (filled < buffer.size) {
                        val read = input.read(buffer, filled, buffer.size - filled)
                        if (read < 0) break
                        filled += read
                    }
                    drain(input)
                    decodeLeniently(buffer, filled)
                }
            } catch (e: IOException) {
                null
            }

        inline fun drainQuietly(open: () -> InputStream?) {
            try {
                open()?.use { drain(it) }
            } catch (e: IOException) {
                // The result is already known; a broken body only costs connection reuse.
            }
        }

        fun drain(input: InputStream) {
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < MAX_DRAIN) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
        }

        fun decodeLeniently(
            bytes: ByteArray,
            length: Int,
        ): String =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
    }
}
