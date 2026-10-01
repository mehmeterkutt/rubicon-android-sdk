package com.rubicon.analytics.internal.config

import java.net.URI
import java.net.URISyntaxException

/**
 * The `host` rules of Configuration 3: a base URL with an `http` or `https` scheme, a host name and
 * an optional path prefix, such as `https://intranet.example.com/analytics`. Trailing slashes are
 * removed so endpoints can be appended: `{host}/v1/batch`, `{host}/v1/config`.
 */
internal object Hosts {
    /** The normalized base URL, or `null` when [host] is not a valid one. */
    fun normalize(host: String): String? {
        val trimmed = host.trim()
        if (trimmed.isEmpty() || trimmed != host) return null
        val uri =
            try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
        val scheme = uri.scheme?.lowercase()
        val valid =
            (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawUserInfo == null
        return if (valid) trimmed.trimEnd('/') else null
    }

    /** Whether a normalized host sends in cleartext, which is logged as a WARN (Configuration 3). */
    fun isCleartext(normalizedHost: String): Boolean = normalizedHost.startsWith("http://", ignoreCase = true)
}
