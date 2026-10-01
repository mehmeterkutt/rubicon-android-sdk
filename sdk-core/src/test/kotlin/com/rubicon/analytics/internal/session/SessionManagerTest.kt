package com.rubicon.analytics.internal.session

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.PersonState
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.store.SessionState
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeElapsedClock
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionManagerTest {
    private val clock = FakeClock(T0)
    private val elapsed = FakeElapsedClock()
    private val keyValueStore = InMemoryKeyValueStore()
    private val log = Log(LogLevel.DEBUG, null, RecordingLogger())
    private var ids = 0
    private var persists = 0

    private fun personStore() = PersonStore(keyValueStore, log).apply { load() }

    private var person = personStore()

    private fun manager(
        store: PersonStore = person,
        emitEvents: Boolean = true,
    ) = SessionManager(store, clock, elapsed, TIMEOUT, emitEvents, { "s${++ids}" }, log) { persists++ }

    private var sessions = manager()

    /** Time passes on both clocks, as it does on a device. */
    private fun pass(ms: Long) {
        clock.advanceBy(ms)
        elapsed.advanceBy(ms)
    }

    private fun event(): SessionOutcome = sessions.onEvent(clock.currentTimeMillis())

    /** A new process with the same files: what a restart looks like. */
    private fun restart(): List<SessionEvent> {
        person.persist()
        person = personStore()
        sessions = manager()
        return sessions.start()
    }

    private fun List<SessionEvent>.ended() = single() as SessionEvent.Ended

    @Nested
    inner class `starting a session` {
        @Test
        fun `the first event in the foreground starts it, with Session Started right before`() {
            sessions.onForeground()

            val outcome = event()

            val started = outcome.before.single() as SessionEvent.Started
            assertEquals("s1", outcome.session!!.id)
            assertEquals(T0, outcome.session!!.startedAt)
            assertEquals(T0, started.session.startedAt)
            assertEquals("s1", started.session.id)
            assertEquals(SessionState("s1", T0, T0), person.state.session)
        }

        @Test
        fun `later events in the foreground continue it without session events`() {
            sessions.onForeground()
            event()
            pass(60_000)

            val outcome = event()

            assertEquals("s1", outcome.session!!.id)
            assertTrue(outcome.before.isEmpty())
        }

        @Test
        fun `the foreground alone keeps it alive, with or without events`() {
            sessions.onForeground()
            event()
            pass(3 * 60 * 60 * 1000L)

            assertEquals("s1", event().session!!.id)
        }

        @Test
        fun `a process started in the background has no session until the foreground`() {
            val background = event()

            assertNull(background.session)
            assertTrue(background.before.isEmpty())
            sessions.onForeground()
            assertEquals("s1", event().session!!.id)
        }

        @Test
        fun `ids are kept even with session events switched off`() {
            sessions = manager(emitEvents = false)
            sessions.onForeground()

            val outcome = event()

            assertEquals("s1", outcome.session!!.id)
            assertTrue(outcome.before.isEmpty())
        }
    }

    @Nested
    inner class `the background countdown` {
        @Test
        fun `returning before the timeout continues the same session`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            pass(TIMEOUT - 1)

            assertTrue(sessions.onForeground().isEmpty())
            assertEquals("s1", event().session!!.id)
        }

        @Test
        fun `returning after the timeout ends it, backdated to the backgrounding`() {
            sessions.onForeground()
            event()
            pass(90_000)
            sessions.onBackground()
            val backgroundedAt = clock.currentTimeMillis()
            pass(TIMEOUT)

            val ended = sessions.onForeground().ended()

            assertEquals("s1", ended.session.id)
            assertEquals(backgroundedAt, ended.session.lastActivity)
            assertEquals(90_000, ended.durationMs)
            assertNull(ended.identity)
            val next = event()
            assertEquals("s2", next.session!!.id)
            assertInstanceOf(SessionEvent.Started::class.java, next.before.single())
        }

        @Test
        fun `a background event uses a valid session but never extends it`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            val backgroundedAt = clock.currentTimeMillis()
            pass(TIMEOUT / 2)

            assertEquals("s1", event().session!!.id)
            pass(TIMEOUT / 2)

            val ended = sessions.onForeground().ended()
            assertEquals(backgroundedAt, ended.session.lastActivity)
        }

        @Test
        fun `the first background event after the timeout ends it and carries no session`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            pass(TIMEOUT)

            val outcome = event()

            assertNull(outcome.session)
            assertEquals(
                "s1",
                outcome.before
                    .ended()
                    .session.id,
            )
            assertNull(event().session)
            assertEquals(1, ids)
        }

        @Test
        fun `a clock change while backgrounded does not count`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            clock.advanceBy(10 * TIMEOUT)
            elapsed.advanceBy(TIMEOUT - 1)

            assertTrue(sessions.onForeground().isEmpty())
            assertEquals("s1", event().session!!.id)
        }
    }

    @Nested
    inner class `maximum duration` {
        @Test
        fun `a session ends after 12 hours and a new one starts in the foreground`() {
            sessions.onForeground()
            event()
            pass(SessionManager.MAX_DURATION_MS - 1)
            event()
            pass(1)

            val outcome = event()

            val ended = outcome.before[0] as SessionEvent.Ended
            assertEquals("s1", ended.session.id)
            assertEquals(SessionManager.MAX_DURATION_MS - 1, ended.durationMs)
            assertInstanceOf(SessionEvent.Started::class.java, outcome.before[1])
            assertEquals("s2", outcome.session!!.id)
        }

        @Test
        fun `it is also found on returning to the foreground`() {
            sessions.onForeground()
            event()
            pass(SessionManager.MAX_DURATION_MS - 60_000)
            sessions.onBackground()
            pass(60_000)

            assertEquals(
                "s1",
                sessions
                    .onForeground()
                    .ended()
                    .session.id,
            )
        }
    }

    @Nested
    inner class `across restarts` {
        @Test
        fun `a restart within the timeout continues the session`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            pass(TIMEOUT - 1_000)

            assertTrue(restart().isEmpty())
            sessions.onForeground()
            assertEquals("s1", event().session!!.id)
        }

        @Test
        fun `a restart after the timeout ends it, backdated, and clears it`() {
            sessions.onForeground()
            event()
            pass(40_000)
            sessions.onBackground()
            pass(TIMEOUT)

            val ended = restart().ended()

            assertEquals("s1", ended.session.id)
            assertEquals(T0 + 40_000, ended.session.lastActivity)
            assertEquals(40_000, ended.durationMs)
            assertNull(person.state.session)
        }

        @Test
        fun `a process started in the background keeps counting from the last activity`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            pass(TIMEOUT - 10_000)
            restart()

            assertEquals("s1", event().session!!.id)
            pass(10_000)
            assertNull(event().session)
        }

        @Test
        fun `a restart past the maximum ends it`() {
            sessions.onForeground()
            event()
            pass(SessionManager.MAX_DURATION_MS - 1_000)
            event()
            sessions.onBackground()
            pass(2_000)

            assertEquals("s1", restart().ended().session.id)
        }

        @Test
        fun `a clock moved back ends the saved session`() {
            sessions.onForeground()
            event()
            pass(60_000)
            sessions.onBackground()
            clock.advanceBy(-86_400_000)

            val ended = restart().ended()

            assertEquals("s1", ended.session.id)
            assertNull(person.state.session)
        }

        @Test
        fun `a restart with no saved session does nothing`() {
            assertTrue(restart().isEmpty())
        }
    }

    @Nested
    inner class `last activity` {
        @Test
        fun `is written when the app goes to the background`() {
            sessions.onForeground()
            event()
            pass(5_000)

            sessions.onBackground()

            assertEquals(T0 + 5_000, person.state.session!!.lastActivity)
        }

        @Test
        fun `is written at most every 30 seconds in the foreground`() {
            sessions.onForeground()
            event()
            val written = mutableListOf<Long>()
            repeat(12) {
                pass(10_000)
                event()
                written += person.state.session!!.lastActivity
            }

            assertEquals(listOf(T0 + 30_000, T0 + 60_000, T0 + 90_000, T0 + 120_000), written.distinct().drop(1))
            assertEquals(T0, written.first())
        }

        @Test
        fun `a crash in the foreground leaves an end at most 30 seconds early`() {
            sessions.onForeground()
            event()
            pass(25_000)
            event()
            pass(25_000)
            event()

            // The process dies here without onStop.
            pass(TIMEOUT + 60_000)
            val ended = restart().ended()

            assertTrue(T0 + 50_000 - ended.session.lastActivity <= 30_000)
        }
    }

    @Nested
    inner class `reset and a change of user` {
        private val previous = IdentitySnapshot("user-42", "device-1", mapOf("plan" to "pro"))

        @Test
        fun `ends the session first as the previous user, and the next event starts a new one`() {
            sessions.onForeground()
            event()
            pass(20_000)
            person.update { PersonState.ANONYMOUS }

            val ended = sessions.onPersonCleared(previous).ended()

            assertEquals("s1", ended.session.id)
            assertSame(previous, ended.identity)
            assertEquals(T0 + 20_000, ended.session.lastActivity)
            val next = event()
            assertInstanceOf(SessionEvent.Started::class.java, next.before.single())
            assertEquals("s2", next.session!!.id)
        }

        @Test
        fun `a cleared person never gets the old session written back`() {
            sessions.onForeground()
            event()
            person.update { PersonState.ANONYMOUS }

            sessions.onBackground()

            assertNull(person.state.session)
        }

        @Test
        fun `without an open session nothing is produced`() {
            assertTrue(sessions.onPersonCleared(previous).isEmpty())
        }

        @Test
        fun `in the background the next event carries no session`() {
            sessions.onForeground()
            event()
            sessions.onBackground()
            sessions.onPersonCleared(previous)

            assertNull(event().session)
        }
    }

    @Test
    fun `opting out ends the session silently`() {
        sessions.onForeground()
        event()

        sessions.endSilently()

        assertNull(person.state.session)
        val next = event()
        assertNotEquals("s1", next.session!!.id)
    }

    @Test
    fun `every change asks the writer to persist`() {
        sessions.onForeground()
        event()
        sessions.onBackground()

        assertTrue(persists >= 2)
    }

    private companion object {
        const val T0 = 1_759_147_200_000L
        const val TIMEOUT = 300_000L
    }
}
