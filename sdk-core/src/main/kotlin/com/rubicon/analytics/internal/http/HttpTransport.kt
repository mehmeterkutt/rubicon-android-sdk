package com.rubicon.analytics.internal.http

import com.rubicon.analytics.RubiconInternal

/**
 * Sends one request, once, and reports what happened (HTTP 1, 4, 6). Never throws: every failure is
 * an [HttpResult]. The implementation adds compression and the SDK's own headers (HTTP 2, 5).
 */
@RubiconInternal
public interface HttpTransport {
    public fun execute(request: HttpRequest): HttpResult
}

/** A request to the configured host; [body] is the uncompressed JSON, `null` for a GET. */
@RubiconInternal
public class HttpRequest(
    public val method: Method,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
) {
    public enum class Method { GET, POST }

    override fun toString(): String = "HttpRequest($method $url, body=${body?.size ?: 0} bytes)"
}

/** The outcome of one attempt (HTTP 4). The transport reports; the retry policy decides. */
@RubiconInternal
public sealed interface HttpResult {
    /** A 2xx status. The body of a success is never used. */
    public data class Success(
        val code: Int,
    ) : HttpResult

    /** Any other status, with at most 4 KB of the error body and `Retry-After` in milliseconds. */
    public data class HttpError(
        val code: Int,
        val body: String?,
        val retryAfterMs: Long?,
    ) : HttpResult

    /** No HTTP status was received. */
    public data class NetworkFailure(
        val kind: FailureKind,
        val cause: Exception,
    ) : HttpResult
}

@RubiconInternal
public enum class FailureKind { TIMEOUT, DNS, CONNECT, TLS, IO, BLOCKED, UNEXPECTED }
