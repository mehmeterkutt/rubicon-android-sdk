package com.rubicon.analytics.network

import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * The properties of `Network Request` (Lifecycle 1, Privacy 5c). Only the method, the URL without
 * query or fragment, the status, the error type, the duration and the sizes; headers and bodies are
 * never read, only their declared lengths. Uses only OkHttp API that 4.12 and 5.x share, with
 * null-safe access where 5.x made a value non-null.
 */
internal object NetworkRequestProperties {
    private val DIGITS = Regex("[0-9]+")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val LONG_HEX = Regex("[0-9a-fA-F]{17,}")
    private const val MASK = ":id"

    fun of(
        request: Request,
        response: Response?,
        error: String?,
        durationMs: Long,
        maskPathIds: Boolean,
    ): Map<String, Any?> {
        val url = request.url
        val path = path(url, maskPathIds)
        val properties = LinkedHashMap<String, Any?>()
        properties["method"] = request.method
        properties["url"] = origin(url) + path
        properties["host"] = url.host
        properties["path"] = path
        if (response != null) properties["status_code"] = response.code
        if (error != null) properties["error"] = error
        properties["duration_ms"] = durationMs
        size { request.body?.contentLength() }?.let { properties["request_bytes"] = it }
        if (response != null) size { response.body?.contentLength() }?.let { properties["response_bytes"] = it }
        return properties
    }

    /** The encoded path, with ids masked when asked. */
    fun path(
        url: HttpUrl,
        maskPathIds: Boolean,
    ): String {
        if (!maskPathIds) return url.encodedPath
        return url.encodedPathSegments.joinToString(separator = "/", prefix = "/") { if (isId(it)) MASK else it }
    }

    fun isId(segment: String): Boolean = DIGITS.matches(segment) || UUID.matches(segment) || LONG_HEX.matches(segment)

    // The port only when it is not the scheme's default.
    private fun origin(url: HttpUrl): String {
        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return "${url.scheme}://$host$port"
    }

    // -1 means unknown; computing a length may throw for a streamed body.
    private inline fun size(length: () -> Long?): Long? =
        try {
            length()?.takeIf { it >= 0 }
        } catch (t: Throwable) {
            null
        }
}
