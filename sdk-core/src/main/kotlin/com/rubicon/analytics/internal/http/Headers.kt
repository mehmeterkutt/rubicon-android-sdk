package com.rubicon.analytics.internal.http

import java.util.Locale

/** The header names of the SDK's requests (HTTP 5, Configuration 5). */
internal object Headers {
    const val AUTHORIZATION = "Authorization"
    const val USER_AGENT = "User-Agent"
    const val CONTENT_TYPE = "Content-Type"
    const val CONTENT_ENCODING = "Content-Encoding"
    const val IF_NONE_MATCH = "If-None-Match"
    const val RETRY_AFTER = "Retry-After"
    const val LOCATION = "Location"

    /** Pending backend agreement (HTTP 4): a request id the server may return, for the DEBUG line. */
    const val REQUEST_ID = "X-Request-Id"

    const val JSON = "application/json; charset=utf-8"
    const val GZIP = "gzip"

    private val RESERVED =
        setOf("authorization", "content-type", "content-encoding", "content-length", "user-agent", "host", "if-none-match")
    private const val RESERVED_PREFIX = "x-rubicon-"

    /** Whether `network.headers` may not set [name]: the SDK sets it itself; any letter case. */
    fun isReserved(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in RESERVED || lower.startsWith(RESERVED_PREFIX)
    }
}
