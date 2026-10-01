package com.rubicon.analytics.internal.config

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.BeforeSend
import com.rubicon.analytics.Consent
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconLogger
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.IssueCounter
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class ConfigValidatorTest {
    private val logger = RecordingLogger()
    private val counted = mutableListOf<Code>()
    private val log = Log(LogLevel.DEBUG, null, logger, IssueCounter { counted += it })

    private fun config(block: AnalyticsConfig.() -> Unit = {}) = AnalyticsConfig("rk_test").apply(block)

    // Validates and reports as the factory will: throws in debug, logs in release.
    private fun start(
        config: AnalyticsConfig,
        debug: Boolean = config.debug,
    ): ConfigSnapshot? {
        val result = ConfigValidator.validate(config)
        result.report(debug, log)
        return result.snapshot
    }

    @Nested
    inner class `default values` {
        @Test
        fun `every setting has its decided default`() {
            val config = config()

            assertEquals("rk_test", config.apiKey)
            assertEquals("https://api.rubicon.example", config.host)
            assertEquals(AnalyticsConfig.DEFAULT_HOST, config.host)
            assertFalse(config.debug)
            assertEquals(emptyList<BeforeSend>(), config.beforeSend)
            assertNull(config.logging.eventObserver)
            assertEquals(30, config.flush.at)
            assertEquals(60_000L, config.flush.intervalMs)
            assertEquals(emptySet<String>(), config.flush.immediateEvents)
            assertEquals(300_000L, config.session.backgroundTimeoutMs)
            assertEquals(Autocapture.entries.toSet() - Autocapture.DEEP_LINKS, config.autocapture.enabled)
            assertEquals(emptySet<ContextField>(), config.context.disabledFields)
            assertEquals(Consent.GRANTED, config.privacy.defaultConsent)
            assertTrue(config.privacy.persistConsent)
            assertEquals(LogLevel.WARN, config.logging.level)
            assertNull(config.logging.logger)
            assertTrue(config.logging.sendDiagnostics)
            assertEquals(emptyMap<String, String>(), config.network.headers)
        }

        @Test
        fun `the default configuration validates without issues`() {
            val result = ConfigValidator.validate(config())
            val snapshot = result.snapshot!!

            assertEquals(emptyList<ConfigValidator.Issue>(), result.issues)
            assertEquals("https://api.rubicon.example/v1/batch", snapshot.batchUrl)
            assertEquals("https://api.rubicon.example/v1/config", snapshot.configUrl)
            assertEquals(30, snapshot.flushAt)
            assertEquals(Autocapture.entries.toSet() - Autocapture.DEEP_LINKS, snapshot.autocapture)
        }

        @Test
        fun `the enums hold the decided values`() {
            assertEquals(
                listOf("APP_LIFECYCLE", "INSTALL_UPDATE", "SESSIONS", "SCREEN_VIEWS", "DEEP_LINKS", "NETWORK_REQUESTS"),
                Autocapture.entries.map { it.name },
            )
            assertEquals(listOf("GRANTED", "PENDING"), Consent.entries.map { it.name })
            assertEquals(18, ContextField.entries.size)
            assertTrue(ContextField.entries.none { it.name.startsWith("LIBRARY") })
        }
    }

    @Nested
    inner class `numeric bounds` {
        // Each call gets its own logger, so one clamp gives exactly one WARN.
        private fun assertClamped(
            expected: Long,
            snapshotValue: (ConfigSnapshot) -> Long,
            set: AnalyticsConfig.() -> Unit,
        ) {
            val own = RecordingLogger()
            val codes = mutableListOf<Code>()
            val result = ConfigValidator.validate(config(set))
            result.report(debug = false, log = Log(LogLevel.DEBUG, null, own, IssueCounter { codes += it }))

            assertEquals(expected, snapshotValue(result.snapshot!!))
            assertEquals(listOf(Code.SETTING_OUT_OF_BOUNDS), codes)
            assertEquals(LogLevel.WARN, own.lines.single().level)
        }

        private fun assertAccepted(
            expected: Long,
            snapshotValue: (ConfigSnapshot) -> Long,
            set: AnalyticsConfig.() -> Unit,
        ) {
            assertEquals(expected, snapshotValue(start(config(set), debug = true)!!))
            assertEquals(emptyList<Code>(), counted)
        }

        @Test
        fun `flush at accepts 1 to 100`() {
            assertAccepted(1, { it.flushAt.toLong() }) { flush.at = 1 }
            assertAccepted(100, { it.flushAt.toLong() }) { flush.at = 100 }
        }

        @Test
        fun `flush at is clamped in release`() {
            assertClamped(1, { it.flushAt.toLong() }) { flush.at = 0 }
            assertClamped(100, { it.flushAt.toLong() }) { flush.at = 101 }
            assertClamped(1, { it.flushAt.toLong() }) { flush.at = Int.MIN_VALUE }
        }

        @Test
        fun `flush interval accepts one second to ten minutes`() {
            assertAccepted(1_000, { it.flushIntervalMs }) { flush.intervalMs = 1_000 }
            assertAccepted(600_000, { it.flushIntervalMs }) { flush.intervalMs = 600_000 }
        }

        @Test
        fun `flush interval is clamped in release`() {
            assertClamped(1_000, { it.flushIntervalMs }) { flush.intervalMs = 999 }
            assertClamped(600_000, { it.flushIntervalMs }) { flush.intervalMs = 600_001 }
        }

        @Test
        fun `background timeout accepts ten seconds to two hours`() {
            assertAccepted(10_000, { it.sessionBackgroundTimeoutMs }) { session.backgroundTimeoutMs = 10_000 }
            assertAccepted(7_200_000, { it.sessionBackgroundTimeoutMs }) { session.backgroundTimeoutMs = 7_200_000 }
        }

        @Test
        fun `background timeout is clamped in release`() {
            assertClamped(10_000, { it.sessionBackgroundTimeoutMs }) { session.backgroundTimeoutMs = 9_999 }
            assertClamped(7_200_000, { it.sessionBackgroundTimeoutMs }) { session.backgroundTimeoutMs = 7_200_001 }
        }

        @Test
        fun `an out-of-bounds number throws in debug, naming the setting and its bounds`() {
            val error = assertThrows<IllegalArgumentException> { start(config { flush.at = 0 }, debug = true) }

            assertEquals("flush.at is 0, outside 1 to 100; 1 is used.", error.message)
            assertThrows<IllegalArgumentException> { start(config { flush.intervalMs = 600_001 }, debug = true) }
            assertThrows<IllegalArgumentException> { start(config { session.backgroundTimeoutMs = 1 }, debug = true) }
        }
    }

    @Nested
    inner class `api key` {
        @ParameterizedTest
        @ValueSource(strings = ["", " ", "\t\n"])
        fun `a blank key stops the SDK with an ERROR in release`(key: String) {
            assertNull(start(AnalyticsConfig(key), debug = false))
            assertEquals(listOf(Code.INVALID_API_KEY), counted)
            assertEquals(LogLevel.ERROR, logger.lines.single().level)
        }

        @Test
        fun `a blank key throws in debug`() {
            assertThrows<IllegalArgumentException> { start(AnalyticsConfig(""), debug = true) }
        }

        @Test
        fun `the key never appears in a log line or description`() {
            val config = AnalyticsConfig("rk_live_secret").apply { flush.at = 0 }
            val snapshot = start(config, debug = false)!!

            assertFalse(config.toString().contains("rk_live_secret"))
            assertFalse(snapshot.toString().contains("rk_live_secret"))
            assertTrue(logger.messages.none { it.contains("rk_live_secret") })
        }
    }

    @Nested
    inner class `host url` {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "https://api.example.com|https://api.example.com",
                "https://api.example.com/|https://api.example.com",
                "https://intranet.example.com/analytics|https://intranet.example.com/analytics",
                "https://intranet.example.com/analytics/|https://intranet.example.com/analytics",
                "https://api.example.com:8443|https://api.example.com:8443",
                "HTTPS://api.example.com|HTTPS://api.example.com",
                "https://10.0.0.5|https://10.0.0.5",
            ],
        )
        fun `a base URL is accepted and its trailing slash removed`(case: String) {
            val (input, expected) = case.split('|')
            val snapshot = start(config { host = input }, debug = true)!!

            assertEquals(expected, snapshot.host)
            assertEquals("$expected/v1/batch", snapshot.batchUrl)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "",
                "api.example.com",
                "ftp://api.example.com",
                "https://",
                "https:///path",
                "https://api.example.com?key=1",
                "https://api.example.com/#part",
                "https://user:pass@api.example.com",
                " https://api.example.com",
                "https://api.example.com ",
                "not a url",
                "https://exa mple.com",
            ],
        )
        fun `an invalid host stops the SDK in release and throws in debug`(input: String) {
            assertNull(start(config { host = input }, debug = false), input)
            assertEquals(listOf(Code.INVALID_HOST), counted)
            assertEquals(LogLevel.ERROR, logger.lines.single().level)
            assertThrows<IllegalArgumentException> { start(config { host = input }, debug = true) }
        }

        @Test
        fun `http works with a WARN, in release and in debug`() {
            val snapshot = start(config { host = "http://10.0.2.2:8080" }, debug = true)!!

            assertEquals("http://10.0.2.2:8080", snapshot.host)
            assertEquals(listOf(Code.CLEARTEXT_HOST), counted)
            assertEquals(LogLevel.WARN, logger.lines.single().level)
            assertNotNull(start(config { host = "http://localhost:8080/" }, debug = false))
        }
    }

    @Nested
    inner class `custom headers` {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "Authorization", "authorization", "AUTHORIZATION", "Content-Type", "Content-Encoding", "Content-Length",
                "User-Agent", "Host", "If-None-Match", "X-Rubicon-Version", "x-rubicon-anything",
            ],
        )
        fun `a reserved header is ignored with a WARN in release and throws in debug`(name: String) {
            val snapshot = start(config { network.headers = mapOf(name to "v", "X-Gateway-Token" to "t") }, debug = false)!!

            assertEquals(mapOf("X-Gateway-Token" to "t"), snapshot.headers)
            assertEquals(listOf(Code.RESERVED_HEADER), counted)
            assertThrows<IllegalArgumentException> { start(config { network.headers = mapOf(name to "v") }, debug = true) }
        }

        @Test
        fun `other headers are kept, and their values never logged`() {
            val snapshot = start(config { network.headers = mapOf("X-Gateway-Token" to "token-123", "Authorization" to "secret-456") })!!

            assertEquals(mapOf("X-Gateway-Token" to "token-123"), snapshot.headers)
            assertTrue(logger.messages.none { it.contains("secret-456") || it.contains("token-123") })
        }
    }

    @Nested
    inner class `config snapshot` {
        @Test
        fun `later changes to the config and to the sets it was given never reach the snapshot`() {
            val immediate = mutableSetOf("Purchase")
            val headers = mutableMapOf("X-A" to "1")
            val config =
                config {
                    flush.immediateEvents = immediate
                    network.headers = headers
                }
            val snapshot = start(config)!!

            config.flush.at = 5
            config.host = "https://other.example.com"
            immediate += "Signup"
            headers["X-B"] = "2"

            assertEquals(30, snapshot.flushAt)
            assertEquals("https://api.rubicon.example", snapshot.host)
            assertEquals(setOf("Purchase"), snapshot.immediateEvents)
            assertEquals(mapOf("X-A" to "1"), snapshot.headers)
        }

        @Test
        @Suppress("UNCHECKED_CAST")
        fun `the snapshot's collections cannot be changed`() {
            val snapshot = start(config())!!

            assertThrows<UnsupportedOperationException> { (snapshot.autocapture as MutableSet<Autocapture>).clear() }
            assertThrows<UnsupportedOperationException> { (snapshot.disabledFields as MutableSet<ContextField>).add(ContextField.LOCALE) }
            assertThrows<UnsupportedOperationException> { (snapshot.immediateEvents as MutableSet<String>).add("x") }
            assertThrows<UnsupportedOperationException> { (snapshot.headers as MutableMap<String, String>)["x"] = "y" }
        }

        @Test
        fun `every value reaches the snapshot`() {
            val customLogger = RubiconLogger { _, _, _ -> }
            val hook = BeforeSend { it }
            val observer = EventObserver { }
            val hooks = mutableListOf(hook)
            val snapshot =
                start(
                    config {
                        debug = true
                        beforeSend = hooks
                        logging.eventObserver = observer
                        flush.immediateEvents = setOf("Purchase")
                        autocapture.enabled = setOf(Autocapture.DEEP_LINKS)
                        context.disabledFields = setOf(ContextField.LOCALE)
                        privacy.defaultConsent = Consent.PENDING
                        privacy.persistConsent = false
                        logging.level = LogLevel.DEBUG
                        logging.logger = customLogger
                        logging.sendDiagnostics = false
                    },
                )!!

            assertTrue(snapshot.debug)
            hooks += BeforeSend { null }
            assertEquals(listOf(hook), snapshot.beforeSend)
            assertSame(observer, snapshot.eventObserver)
            assertEquals(setOf(Autocapture.DEEP_LINKS), snapshot.autocapture)
            assertEquals(setOf(ContextField.LOCALE), snapshot.disabledFields)
            assertEquals(Consent.PENDING, snapshot.defaultConsent)
            assertFalse(snapshot.persistConsent)
            assertEquals(LogLevel.DEBUG, snapshot.logLevel)
            assertEquals(customLogger, snapshot.logger)
            assertFalse(snapshot.sendDiagnostics)
        }
    }

    @Nested
    inner class `frozen config` {
        // Every public setter of the config and its groups, found by reflection, so a setting added
        // later without the freezing delegate fails here.
        private fun setters(config: AnalyticsConfig): List<Pair<Any, Method>> {
            val groups =
                listOf(
                    config.flush,
                    config.session,
                    config.autocapture,
                    config.context,
                    config.privacy,
                    config.logging,
                    config.network,
                )
            return (listOf<Any>(config) + groups).flatMap { owner ->
                owner.javaClass.methods
                    .filter { it.name.startsWith("set") && it.parameterCount == 1 }
                    .map { owner to it }
            }
        }

        private fun sample(type: Class<*>): Any =
            when (type) {
                String::class.java -> "https://changed.example.com"
                Boolean::class.javaPrimitiveType -> true
                Int::class.javaPrimitiveType -> 5
                Long::class.javaPrimitiveType -> 5_000L
                Set::class.java -> emptySet<Any>()
                Map::class.java -> emptyMap<Any, Any>()
                List::class.java -> emptyList<Any>()
                EventObserver::class.java -> EventObserver { }
                LogLevel::class.java -> LogLevel.ERROR
                Consent::class.java -> Consent.PENDING
                RubiconLogger::class.java -> RubiconLogger { _, _, _ -> }
                else -> error("No sample for $type")
            }

        @Test
        fun `there are 16 settings with a setter`() {
            // With apiKey, 17 public settings (Configuration 2, without the removed remote group).
            assertEquals(16, setters(config()).size)
        }

        @Test
        fun `before freezing every setter works`() {
            val config = config()

            setters(config).forEach { (owner, setter) -> setter.invoke(owner, sample(setter.parameterTypes.single())) }

            assertEquals(5, config.flush.at)
            assertEquals("https://changed.example.com", config.host)
        }

        @Test
        fun `after freezing every setter throws in debug`() {
            val config = config()
            ConfigValidator.freeze(config, debug = true, log = log)

            setters(config).forEach { (owner, setter) ->
                val error = assertThrows<InvocationTargetException> { setter.invoke(owner, sample(setter.parameterTypes.single())) }
                assertTrue(error.cause is IllegalStateException, setter.name)
            }
            assertEquals(30, config.flush.at)
        }

        @Test
        fun `after freezing every setter is ignored in release, with one WARN`() {
            val config = config()
            ConfigValidator.freeze(config, debug = false, log = log)

            setters(config).forEach { (owner, setter) -> setter.invoke(owner, sample(setter.parameterTypes.single())) }

            assertEquals(30, config.flush.at)
            assertEquals(AnalyticsConfig.DEFAULT_HOST, config.host)
            assertEquals(LogLevel.WARN, config.logging.level)
            assertEquals(1, logger.lines.count { it.level == LogLevel.WARN })
            assertEquals(16, counted.count { it == Code.CONFIG_FROZEN })
            assertTrue(logger.messages.first().contains("was changed after the SDK was created"))
        }
    }
}
