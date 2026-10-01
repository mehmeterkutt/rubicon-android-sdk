package com.rubicon.analytics.internal.android

import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.LifecycleSource
import com.rubicon.analytics.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Lifecycle 2, Session 3.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class AndroidLifecycleSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val logger = RecordingLogger()
    private val log = Log(LogLevel.DEBUG, null, logger)
    private val changes = mutableListOf<String>()

    private val recorder =
        object : LifecycleSource.Listener {
            override fun onForeground() {
                changes += "foreground"
            }

            override fun onBackground() {
                changes += "background"
            }
        }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override fun getLifecycle(): Lifecycle = registry
    }

    @Test
    fun `the process owner is initialized before the observer is added`() {
        val calls = mutableListOf<String>()
        val owner = TestOwner()
        val source =
            AndroidLifecycleSource(app, {
                calls += "owner"
                owner
            }, { calls += "initialize" })

        source.start(log)

        assertEquals(listOf("initialize", "owner"), calls)
    }

    @Test
    fun `ON_START is the foreground and ON_STOP the background, each told once`() {
        val owner = TestOwner()
        val source = AndroidLifecycleSource(app, { owner }).also { it.addListener(recorder) }
        source.start(log)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertTrue(source.isForeground)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertFalse(source.isForeground)
        assertEquals(listOf("foreground", "background"), changes)
    }

    @Test
    fun `an observer added while started hears the foreground at once`() {
        val owner = TestOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val source = AndroidLifecycleSource(app, { owner }).also { it.addListener(recorder) }

        source.start(log)

        assertTrue(source.isForeground)
        assertEquals(listOf("foreground"), changes)
    }

    @Test
    fun `from another thread the observer is posted to the main thread`() {
        val owner = TestOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val source = AndroidLifecycleSource(app, { owner })

        val worker = Thread { source.start(log) }
        worker.start()
        worker.join()
        assertFalse(source.isForeground)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(source.isForeground)
    }

    @Test
    fun `a failure leaves it in the background with one warning`() {
        val source = AndroidLifecycleSource(app, { owner() }, { throw IllegalStateException("no Startup") })

        source.start(log)

        assertFalse(source.isForeground)
        assertEquals(1, logger.lines.count { it.level == LogLevel.WARN && it.message.startsWith("LIFECYCLE_UNAVAILABLE") })
    }

    private fun owner(): LifecycleOwner = TestOwner()

    // The real ProcessLifecycleOwner is a static singleton bound to the first Application of the
    // Robolectric sandbox, so tests with it depend on their order; it is covered, with and without
    // androidx Startup's provider, by the emulator suite (step 8.1).

    @Test
    fun `install facts come from the package`() {
        val info = shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
        info.versionName = "1.4.0"
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode = 140
        } else {
            @Suppress("DEPRECATION")
            run { info.versionCode = 140 }
        }
        info.firstInstallTime = 1_000
        info.lastUpdateTime = 2_000

        val installed = AndroidProcessInfo(app).installedApp()!!

        assertEquals("1.4.0", installed.version)
        assertEquals("140", installed.build)
        assertEquals(1_000, installed.firstInstallTime)
        assertEquals(2_000, installed.lastUpdateTime)
    }

    @Test
    fun `the platform uses it`() {
        assertTrue(AndroidPlatform.create(app, AndroidProcessInfo(app)).lifecycleSource is AndroidLifecycleSource)
    }
}
