package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.BeforeSend
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.Event
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.IdentifyEvent
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.TrackEvent
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.identity.IdentityRef
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.pipeline.CapturedEvent.Type
import com.rubicon.analytics.internal.session.SessionManager
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.SessionState
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeContextProvider
import com.rubicon.analytics.testing.FakeElapsedClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class EventPipelineTest {
    private val clock = FakeClock(NOW)
    private val elapsed = FakeElapsedClock()
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val person = PersonStore(InMemoryKeyValueStore(), log).apply { load() }
    private val provider = FakeContextProvider(STATIC, CALL_TIME)
    private var ids = 0

    private val sessions =
        SessionManager(person, clock, elapsed, 300_000, true, { "session-${++ids}" }, log) {}

    private fun pipeline(
        hooks: List<BeforeSend> = emptyList(),
        debug: Boolean = false,
        disabled: Set<ContextField> = emptySet(),
    ) = EventPipeline(hooks, disabled, debug, provider, sessions, { "generated-${++ids}" }, log)

    private val identity = IdentitySnapshot("user-42", DEVICE_ID, emptyMap())

    private fun captured(
        type: Type = Type.TRACK,
        name: String? = "Purchase Completed",
        values: Map<String, Any?> = mapOf("amount" to 9.99),
        source: EventSource = EventSource.APP,
        identity: IdentitySnapshot = this.identity,
        skipsHooks: Boolean = false,
        messageId: String = "m-1",
        timestamp: Long = NOW,
    ) = CapturedEvent(type, name, values, source, messageId, timestamp, IdentityRef().apply { value = identity }, CALL_TIME, skipsHooks)

    private fun single(results: List<PipelineResult>) = results.single()

    private fun accepted(result: PipelineResult) = (result as PipelineResult.Accepted).event

    private fun hook(block: (Event) -> Event?) = BeforeSend { block(it) }

    private fun errors(code: Code) = logger.lines.count { it.level == LogLevel.ERROR && it.message.startsWith("${code.name}:") }

    @Nested
    inner class `building the event` {
        @Test
        fun `a track event matches the golden file`() {
            person.update { it.copy(session = SessionState("0192e49f-0b1c-7d2e-8f3a-4b5c6d7e8f90", 1_759_147_082_000L, NOW - 60_000)) }
            sessions.start()
            val values =
                linkedMapOf(
                    "amount" to 9.99,
                    "currency" to "USD",
                    "trial_ends_at" to "2026-10-13T00:00:00.000Z",
                    "ab_group" to "B",
                    "items" to listOf(mapOf("sku" to "A1", "qty" to 2)),
                    "coupon" to null,
                )
            val event =
                CapturedEvent(
                    Type.TRACK,
                    "Purchase Completed",
                    values,
                    EventSource.APP,
                    "0192e4a1-7c3f-7a2b-8d4e-1f2a3b4c5d6e",
                    1_759_147_200_123L,
                    IdentityRef().apply { value = identity },
                    CALL_TIME,
                )

            val line = (single(pipeline().process(event)) as PipelineResult.Accepted).line

            assertEquals(Json.parseToJsonElement(File("src/test/resources/golden/track.json").readText()), Json.parseToJsonElement(line))
        }

        @Test
        fun `static context is read once, on first need`() {
            val pipeline = pipeline()

            repeat(3) { pipeline.process(captured()) }

            assertEquals(1, provider.staticReads)
        }

        @Test
        fun `disabled fields are passed to the provider and left out of the event`() {
            val disabled = setOf(ContextField.NETWORK_CARRIER, ContextField.DEVICE_MODEL, ContextField.TIMEZONE)

            val event = accepted(single(pipeline(disabled = disabled).process(captured())))

            assertEquals(disabled, provider.lastDisabled)
            assertNull(event.context.network.carrier)
            assertNull(event.context.device.model)
            assertNull(event.context.timezone)
            assertEquals("wifi", event.context.network.type)
        }

        @Test
        fun `super properties merge, the event's own key wins`() {
            val withSupers = IdentitySnapshot("user-42", DEVICE_ID, mapOf("plan" to "pro", "amount" to 1))

            val event = accepted(single(pipeline().process(captured(identity = withSupers)))) as TrackEvent

            assertEquals(mapOf("plan" to "pro", "amount" to 9.99), event.properties)
        }

        @Test
        fun `identify carries traits only, never super properties`() {
            val withSupers = IdentitySnapshot("user-42", DEVICE_ID, mapOf("plan" to "pro"))

            val event =
                accepted(
                    single(pipeline().process(captured(Type.IDENTIFY, null, mapOf("tier" to 2), identity = withSupers))),
                ) as IdentifyEvent

            assertEquals(mapOf("tier" to 2), event.traits)
        }

        @Test
        fun `screen events carry their name and merged properties`() {
            val event = accepted(single(pipeline().process(captured(Type.SCREEN, "checkout", emptyMap())))) as ScreenEvent

            assertEquals("checkout", event.name)
        }
    }

    @Nested
    inner class `session events` {
        @Test
        fun `the first foreground event gets Session Started right before it, in the new session`() {
            sessions.onForeground()

            val results = pipeline().process(captured())

            val started = accepted(results[0]) as TrackEvent
            val event = accepted(results[1])
            assertEquals("Session Started", started.name)
            assertEquals(EventSource.AUTO, started.source)
            assertEquals(emptyMap<String, Any?>(), started.properties)
            assertEquals(NOW, started.timestamp)
            assertEquals(started.context.session!!.id, event.context.session!!.id)
            assertEquals(NOW, event.context.session!!.startedAt)
        }

        @Test
        fun `Session Ended is backdated, carries the duration and the ended session`() {
            val pipeline = pipeline()
            sessions.onForeground()
            val first = accepted(pipeline.process(captured())[1])
            clock.advanceBy(60_000)
            elapsed.advanceBy(60_000)
            sessions.onBackground()
            clock.advanceBy(600_000)
            elapsed.advanceBy(600_000)

            val results = pipeline.processSessionEvents(sessions.onForeground(), identity, CALL_TIME)

            val ended = accepted(results.single()) as TrackEvent
            assertEquals("Session Ended", ended.name)
            assertEquals(EventSource.AUTO, ended.source)
            assertEquals(NOW + 60_000, ended.timestamp)
            assertEquals(mapOf("duration_ms" to 60_000L), ended.properties)
            assertEquals(first.context.session!!.id, ended.context.session!!.id)
            val next = pipeline.process(captured(messageId = "m-2", timestamp = clock.currentTimeMillis()))
            assertEquals("Session Started", (accepted(next[0]) as TrackEvent).name)
        }

        @Test
        fun `a session ended by a person change carries the previous user`() {
            val pipeline = pipeline()
            sessions.onForeground()
            pipeline.process(captured())
            val previous = IdentitySnapshot("user-42", DEVICE_ID, mapOf("plan" to "pro"))

            val ended =
                accepted(
                    pipeline
                        .processSessionEvents(
                            sessions.onPersonCleared(previous),
                            IdentitySnapshot(null, DEVICE_ID, emptyMap()),
                            CALL_TIME,
                        ).single(),
                ) as TrackEvent

            assertEquals("user-42", ended.userId)
            assertEquals("pro", ended.properties["plan"])
        }

        @Test
        fun `session events reach the hooks as automatic events`() {
            val seen = mutableListOf<String>()
            sessions.onForeground()

            pipeline(
                listOf(
                    hook {
                        seen += "${(it as TrackEvent).name}/${it.source}"
                        it
                    },
                ),
            ).process(captured())

            assertEquals(listOf("Session Started/AUTO", "Purchase Completed/APP"), seen)
        }

        @Test
        fun `a background event without a session has no session context`() {
            assertNull(accepted(single(pipeline().process(captured()))).context.session)
        }
    }

    @Nested
    inner class `hooks on the app's events` {
        @Test
        fun `run in list order, each seeing the previous result`() {
            val order = mutableListOf<Int>()
            val hooks =
                listOf(
                    hook {
                        order += 1
                        (it as TrackEvent).copy(properties = it.properties + ("a" to 1))
                    },
                    hook {
                        order += 2
                        (it as TrackEvent).copy(
                            name = "Order Placed",
                            properties =
                                it.properties + ("b" to (it.properties["a"] as Int + 1)),
                        )
                    },
                )

            val event = accepted(single(pipeline(hooks).process(captured()))) as TrackEvent

            assertEquals(listOf(1, 2), order)
            assertEquals("Order Placed", event.name)
            assertEquals(mapOf("amount" to 9.99, "a" to 1, "b" to 2), event.properties)
        }

        @Test
        fun `null drops the event as before_send, quietly`() {
            val result = single(pipeline(listOf(hook { null })).process(captured()))

            assertEquals(DropReason.BEFORE_SEND, (result as PipelineResult.Dropped).reason)
            assertEquals("m-1", result.messageId)
            assertEquals(0, errors(Code.HOOK_VIOLATION))
        }

        @Test
        fun `a throwing hook drops the event with an error, and later hooks do not run`() {
            var later = false

            val result =
                single(
                    pipeline(
                        listOf(
                            hook { throw IllegalStateException("bug") },
                            hook {
                                later = true
                                it
                            },
                        ),
                    ).process(captured()),
                )

            assertEquals(DropReason.BEFORE_SEND, (result as PipelineResult.Dropped).reason)
            assertEquals(1, errors(Code.HOOK_THREW))
            assertFalse(later)
        }

        @Test
        fun `a throwing hook rethrows in debug`() {
            assertThrows<IllegalStateException> {
                pipeline(
                    listOf(hook { throw IllegalStateException("bug") }),
                    debug = true,
                ).process(captured())
            }
        }

        @Test
        fun `an invalid property from a hook is dropped by the second validation`() {
            val result =
                pipeline(
                    listOf(
                        hook {
                            (it as TrackEvent).copy(properties = it.properties + ("at" to java.time.Instant.EPOCH))
                        },
                    ),
                ).process(captured())

            val event = accepted(single(result)) as TrackEvent
            assertEquals(mapOf("amount" to 9.99), event.properties)
            assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("PROPERTY_DROPPED") })
        }

        @Test
        fun `a renamed event with an invalid name is a violation`() {
            val result = single(pipeline(listOf(hook { (it as TrackEvent).copy(name = " ") })).process(captured()))

            assertEquals(DropReason.BEFORE_SEND, (result as PipelineResult.Dropped).reason)
            assertEquals(1, errors(Code.HOOK_VIOLATION))
        }
    }

    @Nested
    inner class `hooks on automatic events` {
        private val auto =
            captured(
                name = "Deep Link Opened",
                values = mapOf("path" to "/invite/abc", "nested" to mapOf("x" to 1)),
                source = EventSource.AUTO,
            )

        @Test
        fun `may change values, to null, to another type and nested`() {
            val hooks = listOf(hook { (it as TrackEvent).copy(properties = mapOf("path" to null, "nested" to mapOf("y" to listOf(1, 2)))) })

            val event = accepted(single(pipeline(hooks).process(auto))) as TrackEvent

            assertEquals(mapOf("path" to null, "nested" to mapOf("y" to listOf(1, 2))), event.properties)
            assertEquals(0, errors(Code.HOOK_VIOLATION))
        }

        @Test
        fun `renaming, adding or removing a top-level key, or returning null drops it`() {
            val breaches =
                listOf<(Event) -> Event?>(
                    { (it as TrackEvent).copy(name = "Link") },
                    { (it as TrackEvent).copy(properties = it.properties + ("extra" to 1)) },
                    { (it as TrackEvent).copy(properties = it.properties - "nested") },
                    { null },
                )

            breaches.forEach { breach ->
                val result = single(pipeline(listOf(hook(breach))).process(auto))
                assertEquals(DropReason.BEFORE_SEND, (result as PipelineResult.Dropped).reason)
            }
            assertEquals(4, logger.lines.count { it.message.startsWith("HOOK_VIOLATION") })
        }

        @Test
        fun `a breach throws in debug`() {
            assertThrows<IllegalStateException> { pipeline(listOf(hook { null }), debug = true).process(auto) }
            assertThrows<IllegalStateException> {
                pipeline(listOf(hook { (it as TrackEvent).copy(name = "Link") }), debug = true).process(auto)
            }
        }
    }

    @Nested
    inner class `results that are not a copy` {
        @Test
        fun `an event of another type is dropped`() {
            val other = accepted(single(pipeline().process(captured(Type.SCREEN, "checkout", emptyMap()))))

            val result = single(pipeline(listOf(hook { other })).process(captured()))

            assertEquals(DropReason.BEFORE_SEND, (result as PipelineResult.Dropped).reason)
            assertEquals(1, errors(Code.HOOK_VIOLATION))
        }

        @Test
        fun `an event with another message id is dropped`() {
            val impostor = accepted(single(pipeline().process(captured(messageId = "m-2"))))

            val result = single(pipeline(listOf(hook { impostor })).process(captured()))

            assertInstanceOf(PipelineResult.Dropped::class.java, result)
        }

        @Test
        fun `an event built from scratch is dropped`() {
            // Java can call the internal constructor; the context is then never the input's.
            val built =
                hook {
                    TrackEvent(
                        it.source,
                        it.messageId,
                        it.timestamp,
                        it.userId,
                        it.deviceId,
                        "Purchase Completed",
                        emptyMap(),
                        accepted(single(pipeline().process(captured()))).context,
                    )
                }

            assertInstanceOf(PipelineResult.Dropped::class.java, single(pipeline(listOf(built)).process(captured())))
        }
    }

    @Nested
    inner class `SDK Diagnostics` {
        @Test
        fun `never reaches a hook`() {
            var called = false

            val result =
                pipeline(
                    listOf(
                        hook {
                            called = true
                            null
                        },
                    ),
                ).process(
                    captured(
                        name = "SDK Diagnostics",
                        values = mapOf("dropped" to mapOf("total" to 1)),
                        source = EventSource.AUTO,
                        skipsHooks = true,
                    ),
                )

            assertFalse(called)
            assertInstanceOf(PipelineResult.Accepted::class.java, single(result))
        }
    }

    @Nested
    inner class `size limit` {
        @Test
        fun `an event over 32 KB is dropped as oversize with a warning`() {
            val big = (1..40).associate { "k$it" to "x".repeat(1_000) }

            val result = single(pipeline().process(captured(values = big)))

            assertEquals(DropReason.OVERSIZE, (result as PipelineResult.Dropped).reason)
            assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("EVENT_OVERSIZE") })
        }

        @Test
        fun `it throws in debug`() {
            val big = (1..40).associate { "k$it" to "x".repeat(1_000) }

            assertThrows<IllegalArgumentException> { pipeline(debug = true).process(captured(values = big)) }
        }

        @Test
        fun `exactly 32 KB is kept, one byte more is dropped`() {
            // Strings are cut at 1024 characters, so the size is built from many shorter values.
            val filler = (1..31).associate { "k%02d".format(it) to "y".repeat(1_000) }
            val base = (single(pipeline().process(captured(values = filler))) as PipelineResult.Accepted).line.toByteArray().size
            // Adding ,"z":"..." costs 7 bytes plus the value.
            val room = EventPipeline.MAX_EVENT_BYTES - base - 7

            val fits = single(pipeline().process(captured(values = filler + ("z" to "y".repeat(room)))))
            val over = single(pipeline().process(captured(values = filler + ("z" to "y".repeat(room + 1)))))

            assertEquals(EventPipeline.MAX_EVENT_BYTES, (fits as PipelineResult.Accepted).line.toByteArray().size)
            assertEquals(DropReason.OVERSIZE, (over as PipelineResult.Dropped).reason)
        }
    }

    private companion object {
        const val NOW = 1_759_147_200_123L
        const val DEVICE_ID = "0191d0c2-1a2b-7c3d-9e4f-5a6b7c8d9e0f"

        val STATIC =
            StaticValues(
                appName = "Rubicon",
                appVersion = "1.4.0",
                appBuild = "140",
                appNamespace = "com.rubicon.demo",
                deviceManufacturer = "Google",
                deviceModel = "Pixel 8",
                deviceType = "phone",
                deviceIsEmulator = false,
                osName = "Android",
                osVersion = "16",
                osApiLevel = 36,
            )

        val CALL_TIME =
            CallTimeValues(
                screenWidth = 1080,
                screenHeight = 2400,
                screenDensity = 2.625,
                networkType = "wifi",
                networkCarrier = "Vodafone TR",
                locale = "tr-TR",
                timezone = "Europe/Istanbul",
            )
    }
}
