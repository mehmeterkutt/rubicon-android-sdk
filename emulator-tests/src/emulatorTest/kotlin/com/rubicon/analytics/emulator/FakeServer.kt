package com.rubicon.analytics.emulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.zip.GZIPInputStream

/**
 * The backend the test app sends to: accepts every batch and records its events, answers 404 for
 * remote configuration, and 200 for the app's own `/api` calls. Every body is parsed with
 * kotlinx-serialization-json, an independent parser.
 */
class FakeServer : Dispatcher() {
    private val server = MockWebServer().also { it.dispatcher = this }
    private val received = mutableListOf<JsonObject>()
    private val paths = mutableListOf<String>()

    val port: Int get() = server.port

    fun start() {
        server.start()
    }

    fun stop() {
        server.shutdown()
    }

    fun reset() {
        synchronized(this) {
            received.clear()
            paths.clear()
        }
    }

    fun events(): List<JsonObject> = synchronized(this) { received.toList() }

    fun requestPaths(): List<String> = synchronized(this) { paths.toList() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        synchronized(this) { paths += path }
        return when {
            path.startsWith("/v1/batch") -> {
                val body = request.body.readByteArray()
                val text =
                    (
                        if (request.getHeader("Content-Encoding") ==
                            "gzip"
                        ) {
                            GZIPInputStream(body.inputStream()).readBytes()
                        } else {
                            body
                        }
                    ).decodeToString()
                val events =
                    Json
                        .parseToJsonElement(text)
                        .jsonObject["batch"]!!
                        .jsonArray
                        .map { it.jsonObject }
                synchronized(this) { received += events }
                MockResponse()
            }
            path.startsWith("/v1/config") -> MockResponse().setResponseCode(404)
            else -> MockResponse().setBody("ok")
        }
    }

    /** Waits until [condition] holds for the received events, calling [nudge] every few seconds. */
    fun await(
        timeoutMs: Long = 30_000,
        nudge: () -> Unit = {},
        condition: (List<JsonObject>) -> Boolean,
    ): List<JsonObject> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var nextNudge = System.currentTimeMillis() + NUDGE_MS
        while (System.currentTimeMillis() < deadline) {
            val now = events()
            if (condition(now)) return now
            if (System.currentTimeMillis() >= nextNudge) {
                nudge()
                nextNudge = System.currentTimeMillis() + NUDGE_MS
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("Timed out; received: ${events().map(::label)}")
    }

    companion object {
        private const val POLL_MS = 200L
        private const val NUDGE_MS = 3_000L

        /** `event` for track, `name` for screen, the type for identify. */
        fun label(event: JsonObject): String = (event["event"] ?: event["name"] ?: event["type"])!!.jsonPrimitive.content

        fun property(
            event: JsonObject,
            key: String,
        ): String? = (event["properties"]?.jsonObject?.get(key) as? JsonPrimitive)?.content

        fun messageId(event: JsonObject): String = event["message_id"]!!.jsonPrimitive.content
    }
}
