package com.rubicon.analytics.internal.log

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconLogger
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LogTest {
    private val platform = RecordingLogger()
    private val counted = Collections.synchronizedList(mutableListOf<Code>())
    private val counter = IssueCounter { counted += it }

    private fun log(
        level: LogLevel = LogLevel.DEBUG,
        logger: RubiconLogger? = null,
    ) = Log(level, logger, platform, counter)

    @Nested
    inner class `level gating` {
        @ParameterizedTest
        @EnumSource(LogLevel::class)
        fun `a disabled line never builds its message`(level: LogLevel) {
            val log = log(level)
            val built = AtomicInteger()

            log.debug { "d${built.incrementAndGet()}" }
            log.info { "i${built.incrementAndGet()}" }
            log.warn(Code.HOST_BLOCKED) { "w${built.incrementAndGet()}" }
            log.error(Code.TLS_FAILED) { "e${built.incrementAndGet()}" }

            val expected = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR).count { it >= level }
            assertEquals(expected, built.get())
            assertEquals(expected, platform.lines.size)
        }

        @Test
        fun `OFF logs nothing but still counts issues`() {
            val log = log(LogLevel.OFF)

            log.debug { "d" }
            log.info { "i" }
            log.warn(Code.HOST_BLOCKED) { "w" }
            log.error(Code.TLS_FAILED) { "e" }

            assertEquals(emptyList<String>(), platform.messages)
            assertEquals(listOf(Code.HOST_BLOCKED, Code.TLS_FAILED), counted)
        }

        @Test
        fun `isEnabled follows the order of the levels`() {
            val log = log(LogLevel.WARN)

            assertFalse(log.isEnabled(LogLevel.DEBUG))
            assertFalse(log.isEnabled(LogLevel.INFO))
            assertTrue(log.isEnabled(LogLevel.WARN))
            assertTrue(log.isEnabled(LogLevel.ERROR))
            assertFalse(log.isEnabled(LogLevel.OFF))
        }
    }

    @Nested
    inner class `log lines` {
        @Test
        fun `warnings and errors start with their code and carry the throwable`() {
            val log = log()
            val cause = IllegalStateException("disk full")

            log.debug { "queued" }
            log.info { "host in use" }
            log.warn(Code.CLEARTEXT_HOST) { "host uses http" }
            log.error(Code.STORAGE_WRITE_FAILED, cause) { "write failed" }

            assertEquals(
                listOf(
                    "DEBUG queued",
                    "INFO host in use",
                    "WARN CLEARTEXT_HOST: host uses http",
                    "ERROR STORAGE_WRITE_FAILED: write failed",
                ),
                platform.lines.map { it.toString() },
            )
            assertSame(cause, platform.lines.last().throwable)
        }

        @Test
        fun `the developer's logger replaces the platform logger`() {
            val developer = RecordingLogger()
            val log = log(logger = developer)

            log.info { "hello" }

            assertEquals(listOf("hello"), developer.messages)
            assertEquals(emptyList<String>(), platform.messages)
        }
    }

    @Nested
    inner class `repeat limit` {
        @Test
        fun `a code is logged at its level once, then at DEBUG, and always counted`() {
            val log = log(LogLevel.DEBUG)

            repeat(3) { log.warn(Code.HOST_BLOCKED) { "blocked $it" } }

            assertEquals(listOf(LogLevel.WARN, LogLevel.DEBUG, LogLevel.DEBUG), platform.lines.map { it.level })
            assertEquals(List(3) { Code.HOST_BLOCKED }, counted)
        }

        @Test
        fun `at the default level only the first occurrence is visible`() {
            val log = log(LogLevel.WARN)
            val built = AtomicInteger()

            repeat(3) { log.error(Code.TLS_FAILED) { "tls ${built.incrementAndGet()}" } }

            assertEquals(listOf("TLS_FAILED: tls 1"), platform.messages)
            assertEquals(1, built.get())
            assertEquals(3, counted.size)
        }

        @Test
        fun `each code has its own limit`() {
            val log = log(LogLevel.WARN)

            log.warn(Code.HOST_BLOCKED) { "a" }
            log.warn(Code.CLEARTEXT_HOST) { "b" }
            log.warn(Code.HOST_BLOCKED) { "c" }

            assertEquals(listOf("HOST_BLOCKED: a", "CLEARTEXT_HOST: b"), platform.messages)
        }

        @Test
        fun `a repeat key limits per key instead of per code`() {
            val log = log(LogLevel.WARN)

            log.warn(Code.PROPERTY_TRUNCATED, repeatKey = "bio") { "bio" }
            log.warn(Code.PROPERTY_TRUNCATED, repeatKey = "note") { "note" }
            log.warn(Code.PROPERTY_TRUNCATED, repeatKey = "bio") { "bio again" }
            log.warn(Code.PROPERTY_TRUNCATED) { "without key" }

            assertEquals(
                listOf("PROPERTY_TRUNCATED: bio", "PROPERTY_TRUNCATED: note", "PROPERTY_TRUNCATED: without key"),
                platform.messages,
            )
            assertEquals(4, counted.size)
        }

        @Test
        fun `separate instances keep separate limits`() {
            log(LogLevel.WARN).warn(Code.HOST_BLOCKED) { "first" }
            log(LogLevel.WARN).warn(Code.HOST_BLOCKED) { "second" }

            assertEquals(listOf("HOST_BLOCKED: first", "HOST_BLOCKED: second"), platform.messages)
        }
    }

    @Nested
    inner class `throwing logger` {
        private val calls = AtomicInteger()
        private val throwing =
            RubiconLogger { _, _, _ ->
                calls.incrementAndGet()
                throw IllegalStateException("broken logger")
            }

        @Test
        fun `falls back to the platform logger once and says so once`() {
            val log = log(logger = throwing)

            log.info { "first" }
            log.info { "second" }

            assertEquals(1, calls.get())
            assertEquals(
                listOf("LOGGER_THREW: Custom logger threw; falling back to the default logger", "first", "second"),
                platform.messages,
            )
            assertEquals(
                "broken logger",
                platform.lines
                    .first()
                    .throwable
                    ?.message,
            )
            assertEquals(listOf(Code.LOGGER_THREW), counted)
        }

        @Test
        fun `a throwing platform logger is swallowed`() {
            val log = Log(LogLevel.DEBUG, null, throwing, counter)

            log.info { "one" }
            log.error(Code.TLS_FAILED) { "two" }

            assertEquals(2, calls.get())
        }
    }

    @Nested
    inner class `event observer` {
        @Test
        fun `updates reach the observer`() {
            val log = log()
            val seen = mutableListOf<String>()

            log.callbacks.notifyObserver { seen += "queued" }
            log.callbacks.notifyObserver { seen += "sent" }

            assertEquals(listOf("queued", "sent"), seen)
            assertTrue(log.callbacks.isObserverEnabled)
        }

        @Test
        fun `a throwing observer is switched off after one ERROR`() {
            val log = log(LogLevel.WARN)
            val calls = AtomicInteger()

            repeat(3) {
                log.callbacks.notifyObserver {
                    calls.incrementAndGet()
                    throw IllegalArgumentException("broken observer")
                }
            }

            assertEquals(1, calls.get())
            assertFalse(log.callbacks.isObserverEnabled)
            assertEquals(listOf("OBSERVER_THREW: Event observer threw; it is switched off"), platform.messages)
            assertEquals(
                "broken observer",
                platform.lines
                    .single()
                    .throwable
                    ?.message,
            )
        }
    }

    @Nested
    inner class `re-entrancy` {
        @Test
        fun `a logger that logs through the SDK is ignored, without recursion`() {
            lateinit var log: Log
            val developer = RecordingLogger()
            val calls = AtomicInteger()
            val reentrant =
                RubiconLogger { level, message, t ->
                    calls.incrementAndGet()
                    developer.log(level, message, t)
                    log.warn(Code.HOST_BLOCKED) { "from inside the logger" }
                }
            log = log(logger = reentrant)

            log.info { "outside" }

            assertEquals(1, calls.get())
            assertEquals(listOf("outside"), developer.messages)
            // The ignored line still counts as an issue.
            assertEquals(listOf(Code.HOST_BLOCKED), counted)
        }

        @Test
        fun `an observer that logs or notifies again is ignored`() {
            val log = log(LogLevel.DEBUG)
            val calls = AtomicInteger()

            log.callbacks.notifyObserver {
                calls.incrementAndGet()
                log.info { "from inside the observer" }
                log.callbacks.notifyObserver { calls.incrementAndGet() }
            }

            assertEquals(1, calls.get())
            assertEquals(emptyList<String>(), platform.messages)
            assertFalse(log.callbacks.isInCallback)
        }

        @Test
        fun `only the thread inside the callback is affected`() {
            val log = log(LogLevel.DEBUG)
            val other = Thread { log.info { "from another thread" } }

            log.callbacks.notifyObserver {
                other.start()
                other.join(5_000)
            }

            assertEquals(listOf("from another thread"), platform.messages)
        }
    }

    @Nested
    inner class `many threads` {
        @Test
        fun `lines from many threads all arrive and a code is logged at its level once`() {
            val log = log(LogLevel.DEBUG)
            val threads = 8
            val perThread = 500
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            repeat(threads) { t ->
                Thread {
                    start.await()
                    repeat(perThread) { i ->
                        log.debug { "t$t-$i" }
                        log.warn(Code.HOST_BLOCKED) { "blocked" }
                    }
                    done.countDown()
                }.start()
            }

            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS))

            val lines = platform.lines
            assertEquals(threads * perThread * 2, lines.size)
            assertEquals(1, lines.count { it.level == LogLevel.WARN })
            assertEquals(threads * perThread, counted.size)
        }
    }

    @Test
    fun `the no-op logger and counter accept anything`() {
        NoOpLogger.log(LogLevel.ERROR, "ignored", RuntimeException())
        IssueCounter.NONE.increment(Code.HOST_BLOCKED)

        assertEquals(43, Code.entries.size)
    }
}
