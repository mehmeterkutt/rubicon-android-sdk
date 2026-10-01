package com.rubicon.analytics.network

import okhttp3.Interceptor
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The `error` of a failed call (Lifecycle 1): a fixed short value for the common failures, else the
 * exception's simple class name. Never the message, which can carry URLs or data.
 */
internal object NetworkErrors {
    fun name(
        error: Throwable,
        chain: Interceptor.Chain,
    ): String =
        when {
            canceled(chain) -> "canceled"
            error is SocketTimeoutException -> "timeout"
            // OkHttp's call timeout; its message is read here, never sent.
            error is InterruptedIOException && error.message == "timeout" -> "timeout"
            error is UnknownHostException -> "unknown_host"
            error is ConnectException || error is NoRouteToHostException -> "connect"
            error is SSLException -> "tls"
            else -> error.javaClass.simpleName.ifEmpty { "unknown" }
        }

    private fun canceled(chain: Interceptor.Chain): Boolean =
        try {
            chain.call().isCanceled()
        } catch (t: Throwable) {
            false
        }
}
