package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.internal.platform.InstalledApp
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.StoreTestSupport
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.FakeProcessInfo
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Lifecycle 1, 4 and 6.
class AppLifecycleTest {
    private val support = StoreTestSupport()
    private val memory = InMemoryKeyValueStore()
    private val clock = FakeClock(1_759_147_000_000L)
    private val processInfo = FakeProcessInfo()

    private fun deviceStore() = DeviceStore(memory, support.log, clock, support.newDeviceId).apply { load() }

    private fun lifecycle(
        store: DeviceStore = deviceStore(),
        autocapture: Set<Autocapture> = Autocapture.entries.toSet(),
    ) = AppLifecycle(store, processInfo, autocapture)

    private fun app(
        version: String?,
        build: String?,
        installed: Long = INSTALL_TIME,
        updated: Long = installed,
    ) = InstalledApp(version, build, installed, updated)

    private fun record(
        version: String?,
        build: String?,
    ) {
        val store = deviceStore()
        store.update { it.copy(lastVersion = version, lastBuild = build) }
        store.persist()
    }

    private fun firstForeground(store: DeviceStore = deviceStore()) = lifecycle(store).onForeground()!!.first()

    @Nested
    inner class `install and update` {
        @Test
        fun `first launch reports an install with the install time`() {
            processInfo.installed = app("1.4.0", "140")
            val store = deviceStore()

            val event = firstForeground(store)

            assertEquals(AppLifecycle.INSTALLED, event.name)
            assertEquals(mapOf("version" to "1.4.0", "build" to "140", "installed_at" to "2025-09-29T11:56:40.000Z"), event.properties)
            assertEquals("1.4.0", store.state.lastVersion)
            assertEquals("140", store.state.lastBuild)
        }

        @Test
        fun `no record and differing install times is an update with no previous version`() {
            processInfo.installed = app("2.0.0", "200", updated = INSTALL_TIME + 86_400_000)

            val event = firstForeground()

            assertEquals(AppLifecycle.UPDATED, event.name)
            assertEquals(mapOf("version" to "2.0.0", "build" to "200", "updated_at" to "2025-09-30T11:56:40.000Z"), event.properties)
        }

        @Test
        fun `a different build is an update with the previous version`() {
            record("1.4.0", "140")
            processInfo.installed = app("1.5.0", "150", updated = INSTALL_TIME + 1)

            val event = firstForeground()

            assertEquals(AppLifecycle.UPDATED, event.name)
            assertEquals("1.4.0", event.properties["previous_version"])
            assertEquals("140", event.properties["previous_build"])
            assertNull(event.properties["is_downgrade"])
            assertEquals(false, "is_downgrade" in event.properties)
        }

        @Test
        fun `a lower build is a downgrade`() {
            record("1.5.0", "150")
            processInfo.installed = app("1.4.0", "140", updated = INSTALL_TIME + 1)

            val event = firstForeground()

            assertEquals(AppLifecycle.UPDATED, event.name)
            assertEquals(true, event.properties["is_downgrade"])
        }

        @Test
        fun `the same build reports nothing, only the open`() {
            record("1.4.0", "140")
            processInfo.installed = app("1.4.0", "140", updated = INSTALL_TIME + 1)

            assertEquals(listOf(AppLifecycle.OPENED), lifecycle().onForeground()!!.map { it.name })
        }

        @Test
        fun `a reinstall after the data was removed is a fresh install`() {
            record("1.4.0", "140")
            memory.delete(DeviceStore.FILE_NAME)
            processInfo.installed = app("1.4.0", "140", installed = INSTALL_TIME + 1_000)

            assertEquals(AppLifecycle.INSTALLED, firstForeground().name)
        }

        @Test
        fun `only the first foreground of the process checks`() {
            processInfo.installed = app("1.4.0", "140")
            val lifecycle = lifecycle()
            lifecycle.onForeground()
            lifecycle.onBackground()
            processInfo.installed = app("1.5.0", "150", updated = INSTALL_TIME + 1)

            assertEquals(listOf(AppLifecycle.OPENED), lifecycle.onForeground()!!.map { it.name })
        }

        @Test
        fun `with the gate off nothing is reported but the record is still written`() {
            processInfo.installed = app("1.4.0", "140")
            val store = deviceStore()

            val events = lifecycle(store, setOf(Autocapture.APP_LIFECYCLE)).onForeground()!!

            assertEquals(listOf(AppLifecycle.OPENED), events.map { it.name })
            assertEquals("140", store.state.lastBuild)
        }

        @Test
        fun `an unreadable package reports nothing and writes nothing`() {
            processInfo.installed = null
            val store = deviceStore()

            assertEquals(listOf(AppLifecycle.OPENED), lifecycle(store).onForeground()!!.map { it.name })
            assertNull(store.state.lastBuild)
        }
    }

    @Nested
    inner class `open and background` {
        @Test
        fun `the first open is not from the background, later ones are`() {
            val lifecycle = lifecycle(autocapture = setOf(Autocapture.APP_LIFECYCLE))

            val first = lifecycle.onForeground()!!.single()
            val background = lifecycle.onBackground()!!.single()
            val second = lifecycle.onForeground()!!.single()

            assertEquals(mapOf("from_background" to false), first.properties)
            assertEquals(AppLifecycle.BACKGROUNDED, background.name)
            assertEquals(emptyMap<String, Any?>(), background.properties)
            assertEquals(mapOf("from_background" to true), second.properties)
        }

        @Test
        fun `a repeated signal produces nothing`() {
            val lifecycle = lifecycle()

            assertNull(lifecycle.onBackground())
            lifecycle.onForeground()
            assertNull(lifecycle.onForeground())
        }

        @Test
        fun `with APP_LIFECYCLE off the transitions are still followed, without events`() {
            val lifecycle = lifecycle(autocapture = emptySet())

            assertEquals(emptyList<AppLifecycle.AutoEvent>(), lifecycle.onForeground())
            assertEquals(emptyList<AppLifecycle.AutoEvent>(), lifecycle.onBackground())
            assertNull(lifecycle.onBackground())
        }
    }

    private companion object {
        // 2025-09-29T11:56:40Z
        const val INSTALL_TIME = 1_759_147_000_000L
    }
}
