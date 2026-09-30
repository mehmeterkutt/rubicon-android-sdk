package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class FakeTransportTest {
    private fun post(url: String) = HttpRequest(HttpRequest.Method.POST, url, body = "{}".toByteArray())

    @Test
    fun `returns scripted results in order, then the default`() {
        val transport = FakeTransport()
        val failure = HttpResult.NetworkFailure(FailureKind.DNS, IOException("no host"))
        transport.enqueue(HttpResult.HttpError(503, null, null), failure)
        transport.enqueue(HttpResult.Success(204))

        val results = List(4) { transport.execute(post("https://h/v1/batch")) }

        assertEquals(
            listOf(HttpResult.HttpError(503, null, null), failure, HttpResult.Success(204), HttpResult.Success(200)),
            results,
        )
    }

    @Test
    fun `the default result can be chosen`() {
        val transport = FakeTransport(whenEmpty = HttpResult.HttpError(401, "denied", null))

        assertEquals(HttpResult.HttpError(401, "denied", null), transport.execute(post("https://h/v1/batch")))
    }

    @Test
    fun `records every request in order`() {
        val transport = FakeTransport()

        transport.execute(post("https://h/v1/batch"))
        transport.execute(HttpRequest(HttpRequest.Method.GET, "https://h/v1/config"))

        assertEquals(listOf("https://h/v1/batch", "https://h/v1/config"), transport.requests.map { it.url })
        assertEquals(listOf(HttpRequest.Method.POST, HttpRequest.Method.GET), transport.requests.map { it.method })
    }

    @Test
    fun `the recorded list is a snapshot`() {
        val transport = FakeTransport()
        val before = transport.requests

        transport.execute(post("https://h/v1/batch"))

        assertEquals(0, before.size)
        assertEquals(1, transport.requests.size)
    }
}
