package com.rubicon.analytics.internal.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

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

        source.attach()

        assertEquals(listOf("initialize", "owner"), calls)
    }

    @Test
    fun `ON_START is the foreground and ON_STOP the background, each told once`() {
        val owner = TestOwner()
        val source = AndroidLifecycleSource(app, { owner }).also { it.addListener(recorder) }
        source.attach()

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

        source.attach()

        assertTrue(source.isForeground)
        assertEquals(listOf("foreground"), changes)
    }

    @Test
    fun `from another thread the observer is posted to the main thread`() {
        val owner = TestOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val source = AndroidLifecycleSource(app, { owner })

        val worker = Thread { source.attach() }
        worker.start()
        worker.join()
        assertFalse(source.isForeground)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(source.isForeground)
    }

    // Lifecycle 2.6+ with androidx Startup's provider removed.
    private fun withoutStartup(context: Context = app) =
        AndroidLifecycleSource(context, { TestOwner() }, { throw IllegalStateException("cannot be initialized lazily") })
            .also { it.addListener(recorder) }

    @Test
    fun `when the owner cannot be initialized, activities are counted, with one INFO line`() {
        val source = withoutStartup()
        source.start(log)
        source.attach()

        val first = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(source.isForeground)
        first.pause().stop()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue("a move between activities is not a background", source.isForeground)
        second.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AndroidLifecycleSource.BACKGROUND_DELAY_MS - 1))
        assertTrue("the background waits for the delay", source.isForeground)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertFalse(source.isForeground)
        assertEquals(listOf("foreground", "background"), changes)
        assertEquals(1, logger.lines.count { it.level == LogLevel.INFO && it.message.contains("detected from activities") })
        assertEquals(0, logger.lines.count { it.level == LogLevel.WARN })
    }

    @Test
    fun `the counter does not count a rotation`() {
        val source = withoutStartup()
        source.attach()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()

        activity.configurationChange(Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(source.isForeground)
        activity.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(listOf("foreground", "background"), changes)
    }

    @Test
    fun `the log line comes even when the log arrives after the attach`() {
        val source = withoutStartup()
        source.attach()

        source.start(log)
        source.start(log)

        assertEquals(1, logger.lines.count { it.level == LogLevel.INFO && it.message.contains("detected from activities") })
    }

    @Test
    fun `without an Application it stays in the background with one warning`() {
        val notAnApp =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
            }
        val source = withoutStartup(notAnApp)

        source.attach()
        source.start(log)

        assertFalse(source.isForeground)
        assertEquals(1, logger.lines.count { it.level == LogLevel.WARN && it.message.startsWith("LIFECYCLE_UNAVAILABLE") })
    }

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
