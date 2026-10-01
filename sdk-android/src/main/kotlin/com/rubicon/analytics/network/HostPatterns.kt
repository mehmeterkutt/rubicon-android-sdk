package com.rubicon.analytics.network

import com.rubicon.analytics.internal.AutomaticCapture
import com.rubicon.analytics.internal.log.Code
import java.util.Locale

/**
 * The hosts the interceptor captures (Privacy 5c): exact names, and `*.` followed by at least two
 * labels for every subdomain of a domain (`*.shop.com`, not `*.com`), ignoring case.
 */
internal class HostPatterns private constructor(
    private val exact: Set<String>,
    // ".shop.com" for "*.shop.com".
    private val suffixes: List<String>,
) {
    fun matches(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT)
        return name in exact || suffixes.any { name.endsWith(it) }
    }

    companion object {
        private val LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

        fun parse(
            hosts: Set<String>,
            capture: AutomaticCapture?,
        ): HostPatterns {
            val exact = HashSet<String>()
            val suffixes = ArrayList<String>()
            for (raw in hosts) {
                val pattern = raw.trim().lowercase(Locale.ROOT)
                when {
                    pattern.startsWith("*.") && isDomain(pattern.substring(2), minLabels = 2) -> suffixes += pattern.substring(1)
                    isDomain(pattern, minLabels = 1) -> exact += pattern
                    else -> invalid(raw, capture)
                }
            }
            return HostPatterns(exact, suffixes)
        }

        private fun isDomain(
            name: String,
            minLabels: Int,
        ): Boolean {
            val labels = name.split('.')
            return labels.size >= minLabels && labels.all { LABEL.matches(it) }
        }

        private fun invalid(
            pattern: String,
            capture: AutomaticCapture?,
        ) {
            val message =
                "The network capture host \"$pattern\" is invalid: use a host name such as api.shop.com " +
                    "or a wildcard such as *.shop.com; it is ignored"
            if (capture == null) return
            if (capture.debug) throw IllegalArgumentException(message)
            capture.log.warn(Code.INVALID_CAPTURE_HOST) { message }
        }
    }
}
