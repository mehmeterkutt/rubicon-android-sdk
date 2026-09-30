package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.platform.LifecycleSource
import com.rubicon.analytics.internal.platform.NetworkMonitor
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FakeEnvironmentTest {
    @Nested
    inner class `fake clocks` {
        @Test
        fun `the wall clock moves only when told, in both directions`() {
            val clock = FakeClock(start = 1_000)

            assertEquals(1_000, clock.currentTimeMillis())
            clock.advanceBy(500)
            assertEquals(1_500, clock.currentTimeMillis())
            clock.advanceBy(-2_000)
            assertEquals(-500, clock.currentTimeMillis())
            clock.set(42)
            assertEquals(42, clock.currentTimeMillis())
        }

        @Test
        fun `the default start is a fixed instant`() {
            assertEquals(FakeClock.DEFAULT_START, FakeClock().currentTimeMillis())
            assertEquals(FakeElapsedClock.DEFAULT_START, FakeElapsedClock().elapsedRealtimeMillis())
        }

        @Test
        fun `the elapsed clock never moves back`() {
            val clock = FakeElapsedClock(start = 10)

            clock.advanceBy(5)

            assertEquals(15, clock.elapsedRealtimeMillis())
            assertThrows<IllegalArgumentException> { clock.advanceBy(-1) }
        }

        @Test
        fun `the same seed gives the same sequence`() {
            val a = SeededRandom(7)
            val b = SeededRandom(7)

            assertEquals(List(5) { a.nextLong() }, List(5) { b.nextLong() })
            assertNotEquals(SeededRandom(7).nextLong(), SeededRandom(8).nextLong())
            assertEquals(7, a.seed)
        }
    }

    @Nested
    inner class `network monitor` {
        @Test
        fun `listeners hear only changes, until removed`() {
            val monitor = FakeNetworkMonitor(connected = true)
            val heard = mutableListOf<Boolean>()
            val listener = NetworkMonitor.Listener { heard += it }
            monitor.addListener(listener)

            monitor.setConnected(true)
            monitor.setConnected(false)
            monitor.setConnected(false)
            monitor.setConnected(true)
            monitor.removeListener(listener)
            monitor.setConnected(false)

            assertEquals(listOf(false, true), heard)
            assertFalse(monitor.isConnected())
        }
    }

    @Nested
    inner class `lifecycle source` {
        @Test
        fun `listeners hear only changes, until removed`() {
            val source = FakeLifecycleSource()
            val heard = mutableListOf<String>()
            val listener =
                object : LifecycleSource.Listener {
                    override fun onForeground() {
                        heard += "foreground"
                    }

                    override fun onBackground() {
                        heard += "background"
                    }
                }
            source.addListener(listener)

            source.moveToBackground()
            source.moveToForeground()
            source.moveToForeground()
            source.moveToBackground()
            source.removeListener(listener)
            source.moveToForeground()

            assertEquals(listOf("foreground", "background"), heard)
            assertTrue(source.isForeground)
        }
    }

    @Nested
    inner class `context and process` {
        @Test
        fun `the context provider returns what the test set and counts reads`() {
            val provider = FakeContextProvider(staticValues = mapOf("os" to mapOf("name" to "Android")))
            provider.callTimeValues = mapOf("timezone" to "Europe/Istanbul")

            assertEquals(mapOf("os" to mapOf("name" to "Android")), provider.staticValues())
            assertEquals(mapOf("timezone" to "Europe/Istanbul"), provider.callTimeValues())
            provider.callTimeValues()
            assertEquals(1, provider.staticReads)
            assertEquals(2, provider.callTimeReads)
        }

        @Test
        fun `process info reports what the test set`() {
            val info = FakeProcessInfo(osVersion = "5.0", apiLevel = 21)

            assertEquals("5.0", info.osVersion)
            assertEquals(21, info.apiLevel)
            assertEquals(true, info.isMainProcess())
            info.mainProcess = null
            assertNull(info.isMainProcess())
        }
    }

    @Nested
    inner class `test platform` {
        @Test
        fun `wires its fakes into the platform`() {
            val test = TestPlatform()
            val platform = test.platform

            assertSame(test.clock, platform.clock)
            assertSame(test.elapsedClock, platform.elapsedClock)
            assertSame(test.random, platform.random)
            assertSame(test.networkMonitor, platform.networkMonitor)
            assertSame(test.transport, platform.httpTransport)
            assertSame(test.contextProvider, platform.contextProvider)
            assertSame(test.processInfo, platform.processInfo)
            assertSame(test.lifecycleSource, platform.lifecycleSource)
            assertSame(test.eventStore, platform.eventStore)
            assertSame(test.keyValueStore, platform.keyValueStore)
            assertSame(test.storageRoot, platform.storageRoot)
            assertSame(test.fileSystem, platform.fileSystem)
        }

        @Test
        fun `runs every SDK coroutine on the test dispatcher`() {
            val dispatcher = StandardTestDispatcher()
            val dispatchers = TestPlatform(dispatcher = dispatcher).platform.dispatchers

            assertSame(dispatcher, dispatchers.writer)
            assertSame(dispatcher, dispatchers.uploader)
            assertSame(dispatcher, dispatchers.watchdog)
        }

        @Test
        fun `a part can be replaced`() {
            val clock = FakeClock(start = 0)

            assertEquals(0, TestPlatform(clock = clock).platform.clock.currentTimeMillis())
        }
    }
}
