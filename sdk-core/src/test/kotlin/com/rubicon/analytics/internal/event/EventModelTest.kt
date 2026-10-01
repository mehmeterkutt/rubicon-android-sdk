package com.rubicon.analytics.internal.event

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.Event
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.IdentifyEvent
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.TrackEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

class EventModelTest {
    private fun assertSameIdentity(
        expected: Event,
        actual: Event,
    ) {
        assertEquals(expected.source, actual.source)
        assertEquals(expected.messageId, actual.messageId)
        assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.userId, actual.userId)
        assertEquals(expected.deviceId, actual.deviceId)
        assertSame(expected.context, actual.context)
    }

    @Nested
    inner class `limited copy` {
        @Test
        fun `a track copy changes only the name and the properties`() {
            val renamed = SampleEvents.track.copy(name = "Order Placed")
            val stripped = SampleEvents.track.copy(properties = mapOf("amount" to 1.0))

            assertEquals("Order Placed", renamed.name)
            assertEquals(SampleEvents.track.properties, renamed.properties)
            assertEquals("Purchase Completed", stripped.name)
            assertEquals(mapOf("amount" to 1.0), stripped.properties)
            assertSameIdentity(SampleEvents.track, renamed)
            assertSameIdentity(SampleEvents.track, stripped)
        }

        @Test
        fun `a screen copy changes only the name and the properties`() {
            val copy = SampleEvents.screen.copy(name = "Cart", properties = emptyMap())

            assertEquals("Cart", copy.name)
            assertEquals(emptyMap<String, Any?>(), copy.properties)
            assertSameIdentity(SampleEvents.screen, copy)
        }

        @Test
        fun `each copy keeps what it is not given`() {
            assertEquals(SampleEvents.screen.properties, SampleEvents.screen.copy(name = "Cart").properties)
            assertEquals("Checkout", SampleEvents.screen.copy(properties = emptyMap()).name)
            assertEquals(SampleEvents.identify.traits, SampleEvents.identify.copy().traits)
            assertSameIdentity(SampleEvents.identify, SampleEvents.identify.copy())
        }

        @Test
        fun `an identify copy changes only the traits`() {
            val copy = SampleEvents.identify.copy(traits = mapOf("plan" to "free"))

            assertEquals(mapOf("plan" to "free"), copy.traits)
            assertSameIdentity(SampleEvents.identify, copy)
        }

        @Test
        fun `copy without arguments returns an equal new event`() {
            val copy = SampleEvents.track.copy()

            assertFalse(copy === SampleEvents.track)
            assertEquals(SampleEvents.track.name, copy.name)
            assertSameIdentity(SampleEvents.track, copy)
        }
    }

    @Nested
    inner class `read-only for hooks` {
        private val publicTypes =
            listOf(Event::class, TrackEvent::class, ScreenEvent::class, IdentifyEvent::class) +
                listOf(
                    com.rubicon.analytics.EventContext::class,
                    com.rubicon.analytics.AppContext::class,
                    com.rubicon.analytics.DeviceContext::class,
                    com.rubicon.analytics.OsContext::class,
                    com.rubicon.analytics.ScreenContext::class,
                    com.rubicon.analytics.NetworkContext::class,
                    com.rubicon.analytics.SessionContext::class,
                    com.rubicon.analytics.LibraryContext::class,
                )

        @Test
        fun `events and their context have no setters`() {
            publicTypes.forEach { type ->
                val setters = type.java.methods.filter { it.name.startsWith("set") }
                assertEquals(emptyList<String>(), setters.map { it.name }, type.simpleName)
            }
        }

        @Test
        fun `events and their context have no fields that can be written`() {
            publicTypes.forEach { type ->
                type.java.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .forEach {
                        assertTrue(
                            Modifier.isFinal(it.modifiers) && Modifier.isPrivate(it.modifiers),
                            "${type.simpleName}.${it.name}",
                        )
                    }
            }
        }

        @Test
        fun `the only public way to a new event is copy`() {
            listOf(TrackEvent::class, ScreenEvent::class, IdentifyEvent::class).forEach { type ->
                val creators =
                    type.java.methods
                        // Synthetic methods such as copy${'$'}default are compiler helpers, not API.
                        .filter { it.returnType == type.java && !it.isSynthetic }
                        .map { it.name }
                        .toSet()
                assertEquals(setOf("copy"), creators, type.simpleName)
            }
        }
    }

    @Nested
    inner class `safe descriptions` {
        @Test
        fun `toString names the type, the name and the message id only`() {
            assertEquals(
                "TrackEvent(name=Purchase Completed, messageId=0192e4a1-7c3f-7a2b-8d4e-1f2a3b4c5d6e)",
                SampleEvents.track.toString(),
            )
            assertEquals("ScreenEvent(name=Checkout, messageId=0192e4a1-6b2e-7f10-9a3b-2c4d6e8f0a1b)", SampleEvents.screen.toString())
            assertEquals("IdentifyEvent(messageId=0192e4a1-5a1d-7e0f-8b2c-1d3e5f7a9b0c)", SampleEvents.identify.toString())
        }

        @Test
        fun `toString never contains properties, traits, user or device ids`() {
            listOf(SampleEvents.track, SampleEvents.screen, SampleEvents.identify).forEach { event ->
                val text = event.toString() + event.context.toString()
                listOf("user-42", SampleEvents.DEVICE_ID, "USD", "9.99", "pro", "annotation", "Pixel 8", "Vodafone").forEach {
                    assertFalse(text.contains(it), "$it in $text")
                }
            }
        }

        @Test
        fun `updates describe counts and reasons, not content`() {
            assertEquals("Queued(${SampleEvents.track.messageId})", EventUpdate.Queued(SampleEvents.track).toString())
            assertEquals("Sent(2 events)", EventUpdate.Sent(listOf("a", "b")).toString())
            assertEquals("Dropped(1 events, BEFORE_SEND)", EventUpdate.Dropped(listOf("a"), DropReason.BEFORE_SEND).toString())
        }
    }

    @Nested
    inner class `wire names` {
        @Test
        fun `sources and drop reasons are lower case on the wire`() {
            assertEquals(listOf("app", "auto"), EventSource.entries.map { it.wireName })
            assertEquals(
                listOf(
                    "queue_full",
                    "expired",
                    "backpressure",
                    "before_send",
                    "storage_error",
                    "corrupt",
                    "api_key_changed",
                    "oversize",
                    "rejected",
                    "retry_exhausted",
                ),
                DropReason.entries.map { it.wireName },
            )
        }
    }

    @Nested
    inner class `golden files` {
        private fun golden(name: String): JsonElement = Json.parseToJsonElement(File("src/test/resources/golden/$name.json").readText())

        private fun wire(event: Event): JsonElement = Json.parseToJsonElement(EventWire.line(event))

        @Test
        fun `a track event matches track json`() {
            assertEquals(golden("track"), wire(SampleEvents.track))
        }

        @Test
        fun `a screen event matches screen json`() {
            assertEquals(golden("screen"), wire(SampleEvents.screen))
        }

        @Test
        fun `an identify event matches identify json`() {
            assertEquals(golden("identify"), wire(SampleEvents.identify))
        }

        @Test
        fun `an anonymous user is sent as a null user id`() {
            assertEquals(JsonNull, wire(SampleEvents.screen).jsonObject["user_id"])
        }

        @Test
        fun `unknown context values and empty groups are left out`() {
            val context = wire(SampleEvents.identify).jsonObject["context"]!!.jsonObject

            assertEquals(setOf("os", "library"), context.keys)
            assertFalse(
                wire(SampleEvents.screen)
                    .jsonObject["context"]!!
                    .jsonObject["network"]!!
                    .jsonObject
                    .containsKey("carrier"),
            )
        }

        @Test
        fun `each line is one compact line`() {
            listOf(SampleEvents.track, SampleEvents.screen, SampleEvents.identify).forEach { event ->
                // Key order is not part of the contract: android-json does not keep it.
                assertFalse(EventWire.line(event).contains('\n'))
            }
        }
    }
}
