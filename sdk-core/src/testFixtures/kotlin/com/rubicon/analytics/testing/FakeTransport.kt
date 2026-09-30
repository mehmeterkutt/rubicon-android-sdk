package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.http.HttpTransport

/**
 * Returns scripted results in order and records every request. Once the script is used up it
 * returns [whenEmpty].
 */
public class FakeTransport(
    private val whenEmpty: HttpResult = HttpResult.Success(200),
) : HttpTransport {
    private val lock = Any()
    private val script = ArrayDeque<HttpResult>()
    private val recorded = mutableListOf<HttpRequest>()

    /** The requests received so far, oldest first. */
    public val requests: List<HttpRequest>
        get() = synchronized(lock) { recorded.toList() }

    public fun enqueue(vararg results: HttpResult) {
        synchronized(lock) { script.addAll(results) }
    }

    override fun execute(request: HttpRequest): HttpResult =
        synchronized(lock) {
            recorded += request
            script.removeFirstOrNull() ?: whenEmpty
        }
}
