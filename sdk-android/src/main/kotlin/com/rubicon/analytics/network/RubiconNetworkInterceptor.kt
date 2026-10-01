package com.rubicon.analytics.network

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.internal.AutomaticCapture
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

private const val EVENT_NAME = "Network Request"
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Sends a `Network Request` event for each call to the hosts you choose (Lifecycle 1, 6; Privacy
 * 5c). Add it to your own `OkHttpClient` as an application interceptor:
 *
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(RubiconNetworkInterceptor(analytics, setOf("api.shop.com", "*.cdn.shop.com")))
 *     .build()
 * ```
 *
 * - **Hosts**: an exact host name (`api.shop.com`) or a leading wildcard (`*.shop.com`, every
 *   subdomain of `shop.com` but not `shop.com` itself), ignoring case. Nothing else is captured.
 *   An invalid pattern (a scheme, path, port, or a wildcard such as `*.com`) throws when `debug` is
 *   on and is ignored with a warning otherwise.
 * - **Sent**: `method`, `url` and `path` without query or fragment, `host`, `status_code` (absent
 *   when the call failed), `error` (a short type such as `timeout`, never a message),
 *   `duration_ms`, `request_bytes` and `response_bytes` when known.
 * - **Never sent**: headers, bodies, query parameters, fragments. With [maskPathIds] (the default),
 *   path segments that are all digits, a UUID or hexadecimal longer than 16 characters become `:id`.
 * - The request and response pass through untouched, the response body stays readable, and the
 *   interceptor never throws anything the call did not throw. It passes through when the user opted
 *   out, when [Autocapture.NETWORK_REQUESTS] is off (a remote switch-off applies at once), for the
 *   SDK's own host, and when [analytics] is not the SDK's instance, for example a test double.
 *
 * @param analytics the instance returned by `Analytics(context, config)`.
 * @param hosts the hosts to capture.
 * @param maskPathIds whether ids in paths are replaced by `:id`.
 */
public class RubiconNetworkInterceptor
    @JvmOverloads
    constructor(
        analytics: Analytics,
        hosts: Set<String>,
        private val maskPathIds: Boolean = true,
    ) : Interceptor {
        private val capture: AutomaticCapture? = analytics as? AutomaticCapture
        private val patterns: HostPatterns = HostPatterns.parse(hosts, capture)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!captures(request)) return chain.proceed(request)
            val start = System.nanoTime()
            val response =
                try {
                    chain.proceed(request)
                } catch (t: Throwable) {
                    record(request, null, NetworkErrors.name(t, chain), start)
                    throw t
                }
            record(request, response, null, start)
            return response
        }

        private fun captures(request: Request): Boolean =
            try {
                val capture = capture
                val host = request.url.host
                capture != null &&
                    capture.capturesNetworkRequests &&
                    !host.equals(capture.sdkHost, ignoreCase = true) &&
                    patterns.matches(host)
            } catch (t: Throwable) {
                false
            }

        // Never throws: a failure here loses the event, never the app's response.
        private fun record(
            request: Request,
            response: Response?,
            error: String?,
            start: Long,
        ) {
            try {
                val properties =
                    NetworkRequestProperties.of(
                        request,
                        response,
                        error,
                        (System.nanoTime() - start) / NANOS_PER_MILLI,
                        maskPathIds,
                    )
                capture?.track(Autocapture.NETWORK_REQUESTS, EVENT_NAME, properties)
            } catch (t: Throwable) {
                // The app's call is unaffected.
            }
        }
    }
