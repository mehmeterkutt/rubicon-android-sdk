package com.rubicon.analytics.internal.upload

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.http.FailureKind
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PauseKind
import com.rubicon.analytics.internal.upload.RetryPolicy.Decision
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeElapsedClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import kotlin.random.Random

class RetryPolicyTest {
    private val clock = FakeClock(NOW)
    private val elapsed = FakeElapsedClock()
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val keyValueStore = InMemoryKeyValueStore()
    private val store = deviceStore()
    private val policy = policy(store)

    private fun deviceStore() = DeviceStore(keyValueStore, log, clock) { "device-1" }.apply { load() }

    private fun policy(
        store: DeviceStore,
        random: Random = Random(7),
    ) = RetryPolicy(store, clock, elapsed, random, log).apply { restore() }

    private fun http(
        code: Int,
        retryAfterMs: Long? = null,
    ) = HttpResult.HttpError(code, null, retryAfterMs)

    private fun network(kind: FailureKind) = HttpResult.NetworkFailure(kind, IOException(kind.name))

    private fun errors(code: Code) = logger.lines.count { it.level == LogLevel.ERROR && it.message.startsWith("${code.name}:") }

    @Nested
    inner class `classification` {
        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource(
            "200, SUCCESS",
            "201, SUCCESS",
            "204, SUCCESS",
            "100, TRANSIENT",
            "199, TRANSIENT",
            "408, TRANSIENT",
            "500, TRANSIENT",
            "502, TRANSIENT",
            "503, TRANSIENT",
            "504, TRANSIENT",
            "599, TRANSIENT",
            "600, TRANSIENT",
            "-1, TRANSIENT",
            "429, THROTTLE",
            "400, BAD_BATCH",
            "402, BAD_BATCH",
            "405, BAD_BATCH",
            "409, BAD_BATCH",
            "422, BAD_BATCH",
            "451, BAD_BATCH",
            "401, CONFIG",
            "403, CONFIG",
            "404, CONFIG",
            "410, CONFIG",
            "413, CONFIG",
            "501, CONFIG",
            "505, CONFIG",
            "301, CONFIG",
            "302, CONFIG",
            "304, CONFIG",
            "307, CONFIG",
            "308, CONFIG",
        )
        fun `status codes`(
            code: Int,
            group: String,
        ) {
            val result = if (code in 200..299) HttpResult.Success(code) else http(code)

            assertEquals(RetryGroup.valueOf(group), RetryGroup.of(result))
        }

        @Test
        fun `503 with Retry-After slows down, 429 does either way`() {
            assertEquals(RetryGroup.THROTTLE, RetryGroup.of(http(503, 30_000)))
            assertEquals(RetryGroup.THROTTLE, RetryGroup.of(http(429, 30_000)))
        }

        @ParameterizedTest(name = "{0} is {1}")
        @CsvSource(
            "TIMEOUT, TRANSIENT",
            "DNS, TRANSIENT",
            "CONNECT, TRANSIENT",
            "IO, TRANSIENT",
            "TLS, CONFIG",
            "BLOCKED, CONFIG",
            "UNEXPECTED, CONFIG",
        )
        fun `failure kinds`(
            kind: FailureKind,
            group: String,
        ) {
            assertEquals(RetryGroup.valueOf(group), RetryGroup.of(network(kind)))
        }
    }

    @Nested
    inner class `backoff` {
        @Test
        fun `transient failures walk the table with jitter between 0,5 and 1,5`() {
            val waits =
                (1..8).map {
                    assertEquals(Decision.KEEP, policy.onResult("0000000001", http(500)))
                    policy.waitMs()
                }

            val table = listOf(2_000L, 10_000, 30_000, 120_000, 300_000, 600_000, 600_000, 600_000)
            waits.zip(table).forEach { (wait, base) -> assertTrue(wait in base / 2..base * 3 / 2, "$wait for $base") }
            assertEquals(8, store.state.retry.step)
            assertEquals(PauseKind.TRANSIENT, store.state.retry.pauseKind)
            assertEquals("http_500", store.state.retry.lastFailure)
        }

        @Test
        fun `jitter spreads devices that failed together`() {
            val waits =
                (1..20).map { seed ->
                    policy(deviceStore(), Random(seed)).run {
                        onResult("s", network(FailureKind.TIMEOUT))
                        waitMs()
                    }
                }

            assertTrue(waits.toSet().size > 15)
            assertTrue(waits.all { it in 1_000L..3_000L })
        }

        @Test
        fun `a success resets the table and clears the wait`() {
            repeat(4) { policy.onResult("a", http(502)) }

            assertEquals(Decision.DELETE_SENT, policy.onResult("a", HttpResult.Success(200)))

            assertEquals(0, policy.waitMs())
            val retry = store.state.retry
            assertEquals(0, retry.step)
            assertNull(retry.pausedUntil)
            assertNull(retry.lastFailure)
            assertEquals(emptyMap<String, Int>(), retry.batches)
            policy.onResult("b", http(502))
            assertTrue(policy.waitMs() <= 3_000)
        }

        @Test
        fun `the wait passes on the elapsed clock, not the wall clock`() {
            policy.onResult("a", http(500))
            val wait = policy.waitMs()

            clock.advanceBy(-86_400_000)
            assertEquals(wait, policy.waitMs())
            elapsed.advanceBy(wait)
            assertEquals(0, policy.waitMs())
        }
    }

    @Nested
    inner class `Retry-After` {
        @Test
        fun `is never shortened and gets forward jitter of at most 10 percent`() {
            repeat(20) {
                policy.onResult("a", http(429, 120_000))
                assertTrue(policy.waitMs() in 120_000L..132_000L, "${policy.waitMs()}")
            }
            assertEquals(PauseKind.THROTTLE, store.state.retry.pauseKind)
        }

        @Test
        fun `is capped at one hour, jitter included`() {
            policy.onResult("a", http(503, 7_200_000))
            assertEquals(3_600_000L, policy.waitMs())

            policy.onResult("a", http(429, 3_590_000))
            assertTrue(policy.waitMs() in 3_590_000L..3_600_000L)
        }

        @Test
        fun `zero or less counts as one second`() {
            policy.onResult("a", http(429, 0))
            assertTrue(policy.waitMs() in 1_000L..1_100L)

            policy.onResult("a", http(429, -60_000))
            assertTrue(policy.waitMs() in 1_000L..1_100L)
        }

        @Test
        fun `429 without the header uses the table`() {
            policy.onResult("a", http(429))

            assertTrue(policy.waitMs() in 1_000L..3_000L)
            assertEquals(1, store.state.retry.step)
        }

        @Test
        fun `does not advance the table or count against the batch`() {
            policy.onResult("a", http(429, 5_000))

            assertEquals(0, store.state.retry.step)
            assertEquals(emptyMap<String, Int>(), store.state.retry.batches)
        }
    }

    @Nested
    inner class `configuration problems` {
        @Test
        fun `pause one hour plus or minus 20 percent and log one error per code`() {
            repeat(3) {
                assertEquals(Decision.KEEP, policy.onResult("a", http(401)))
                assertTrue(policy.waitMs() in 2_880_000L..4_320_000L)
            }
            policy.onResult("a", http(404))
            policy.onResult("a", http(413))
            policy.onResult("a", http(505))
            policy.onResult("a", network(FailureKind.TLS))

            assertEquals(1, errors(Code.UPLOAD_UNAUTHORIZED))
            assertEquals(1, errors(Code.UPLOAD_ENDPOINT_MISSING))
            assertEquals(1, errors(Code.UPLOAD_TOO_LARGE))
            assertEquals(1, errors(Code.UPLOAD_NOT_SUPPORTED))
            assertEquals(1, errors(Code.TLS_FAILED))
            assertEquals(PauseKind.CONFIG, store.state.retry.pauseKind)
            assertEquals(0, store.state.retry.step)
            assertEquals(emptyMap<String, Int>(), store.state.retry.batches)
        }

        @Test
        fun `a bad batch is deleted at once, with a warning`() {
            assertEquals(Decision.DELETE_REJECTED, policy.onResult("a", http(400)))
            assertEquals(Decision.DELETE_REJECTED, policy.onResult("b", http(422)))

            assertEquals(0, policy.waitMs())
            assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("BATCH_REJECTED:") })
        }
    }

    @Nested
    inner class `network regained` {
        @Test
        fun `ends a wait caused by the network and keeps the table position`() {
            repeat(3) { policy.onResult("a", network(FailureKind.CONNECT)) }

            policy.onNetworkRegained()

            assertEquals(0, policy.waitMs())
            assertNull(store.state.retry.pausedUntil)
            assertEquals(3, store.state.retry.step)
            policy.onResult("a", network(FailureKind.DNS))
            assertTrue(policy.waitMs() >= 60_000)
        }

        @Test
        fun `never ends a server's wait`() {
            listOf(http(500), http(429, 60_000), http(429), http(401), network(FailureKind.TLS), network(FailureKind.BLOCKED)).forEach {
                policy.onResult("a", it)
                val wait = policy.waitMs()

                policy.onNetworkRegained()

                assertEquals(wait, policy.waitMs(), "$it")
            }
        }
    }

    @Nested
    inner class `poison batch` {
        // A scripted uploader: the oldest segment in the policy's order, one attempt at a time.
        private fun run(
            segments: MutableList<String>,
            attempts: Int,
            answer: (String) -> HttpResult,
        ): List<Pair<String, Decision>> {
            val log = ArrayList<Pair<String, Decision>>()
            repeat(attempts) {
                if (segments.isEmpty()) return log
                elapsed.advanceBy(policy.waitMs())
                val segment = policy.order(segments).first()
                val decision = policy.onResult(segment, answer(segment))
                log += segment to decision
                if (decision != Decision.KEEP) segments -= segment
            }
            return log
        }

        @Test
        fun `a batch that keeps failing while others succeed is deleted after 10 attempts`() {
            val segments = mutableListOf("41", "42", "43")

            val log = run(segments, 30) { if (it == "41") http(500) else HttpResult.Success(200) }

            assertEquals(
                List(5) { "41" to Decision.KEEP } +
                    listOf("42" to Decision.DELETE_SENT, "43" to Decision.DELETE_SENT) +
                    List(4) { "41" to Decision.KEEP } +
                    listOf("41" to Decision.DELETE_EXHAUSTED),
                log,
            )
            assertEquals(emptyMap<String, Int>(), store.state.retry.batches)
            assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("RETRY_EXHAUSTED:") })
        }

        @Test
        fun `newer batches arriving later go before the suspect`() {
            val segments = mutableListOf("41", "42")
            run(segments, 7) { if (it == "41") http(503) else HttpResult.Success(200) }
            segments += "44"

            val log = run(segments, 1) { HttpResult.Success(200) }

            assertEquals(listOf("44" to Decision.DELETE_SENT), log)
        }

        @Test
        fun `in a general outage nothing is deleted`() {
            val segments = mutableListOf("41", "42", "43")

            val log = run(segments, 60) { network(FailureKind.TIMEOUT) }

            assertTrue(log.all { it.second == Decision.KEEP })
            assertEquals(listOf("41", "42", "43"), segments)
            assertEquals("42", log[5].first)
        }

        @Test
        fun `a lone failing batch is never deleted, since nothing proves the server works`() {
            val segments = mutableListOf("41")

            run(segments, 25) { http(500) }

            assertEquals(listOf("41"), segments)
            assertEquals(25, store.state.retry.batches["41"])
        }

        @Test
        fun `a segment deleted for another reason is forgotten`() {
            policy.onResult("41", http(500))

            policy.forget("41")

            assertEquals(emptyMap<String, Int>(), store.state.retry.batches)
        }
    }

    @Nested
    inner class `persistence` {
        private fun restart(): RetryPolicy {
            store.persist()
            return policy(deviceStore())
        }

        @Test
        fun `a wait survives a restart`() {
            policy.onResult("41", http(429, 600_000))
            val wait = policy.waitMs()
            clock.advanceBy(100_000)

            val restored = restart()

            assertEquals(wait - 100_000, restored.waitMs())
        }

        @Test
        fun `attempts per segment and the table position survive a restart`() {
            repeat(3) { policy.onResult("41", http(500)) }

            restart()

            val retry = deviceStore().state.retry
            assertEquals(3, retry.step)
            assertEquals(mapOf("41" to 3), retry.batches)
            assertEquals("http_500", retry.lastFailure)
        }

        @Test
        fun `a wait that ended before the restart is not restored`() {
            policy.onResult("41", http(500))
            clock.advanceBy(60_000)

            assertEquals(0, restart().waitMs())
        }

        @Test
        fun `a clock moved back by a day clamps the wait to the longest of its kind`() {
            policy.onResult("41", http(401))
            clock.advanceBy(-86_400_000)
            store.persist()
            val restoredStore = deviceStore()

            val restored = policy(restoredStore)

            assertEquals(3_600_000L, restored.waitMs())
            assertEquals(clock.currentTimeMillis() + 3_600_000L, restoredStore.state.retry.pausedUntil)
        }

        @Test
        fun `a transient wait is clamped to ten minutes`() {
            repeat(6) { policy.onResult("41", network(FailureKind.IO)) }
            clock.advanceBy(-86_400_000)

            assertEquals(600_000L, restart().waitMs())
        }
    }

    private companion object {
        const val NOW = 1_759_147_200_000L
    }
}
