package com.rubicon.analytics.internal.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.AppInitializer
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.LifecycleSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground and background (Lifecycle 2, Session 3): `ON_START` of `ProcessLifecycleOwner` is the
 * foreground, `ON_STOP` the background, about 700 ms after the last activity stops.
 *
 * - [attach] runs when the SDK is created, on the main thread (posted from another thread, never
 *   waited for), so the first activity of a cold start is already in the foreground.
 * - It initializes `ProcessLifecycleInitializer` through `AppInitializer`, which does nothing if
 *   androidx Startup already ran it. With the Startup provider removed, lifecycle 2.6 and later
 *   refuse that and throw; then an activity counter takes over: the first start is the foreground,
 *   the background comes [BACKGROUND_DELAY_MS] after the count reaches zero unless an activity
 *   starts, and a rotation is not counted. One INFO line says so.
 * - If neither works (no `Application`): never in the foreground, one WARN.
 */
@RubiconInternal
internal class AndroidLifecycleSource(
    private val context: Context,
    private val owner: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
    private val initialize: () -> Unit = {
        AppInitializer.getInstance(context).initializeComponent(ProcessLifecycleInitializer::class.java)
    },
) : LifecycleSource {
    private enum class Mode { NOT_ATTACHED, OWNER, FALLBACK, UNAVAILABLE }

    private val listeners = CopyOnWriteArrayList<LifecycleSource.Listener>()
    private val lock = Any()

    @Volatile
    override var isForeground: Boolean = false
        private set

    @Volatile
    private var mode = Mode.NOT_ATTACHED

    @Volatile
    private var failure: Throwable? = null

    // Guarded by lock: the instance's log once the writer started, and whether the mode was reported.
    private var log: Log? = null
    private var reported = false

    private val observer =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> change(true)
                Lifecycle.Event.ON_STOP -> change(false)
                else -> Unit
            }
        }

    /** Starts observing; called by the factory for a new instance. */
    fun attach() {
        val register = Runnable { register() }
        if (Looper.myLooper() == Looper.getMainLooper()) register.run() else Handler(Looper.getMainLooper()).post(register)
    }

    /** Gives the instance's log, for the line that reports a fallback; the writer calls it at startup. */
    override fun start(log: Log) {
        synchronized(lock) { this.log = log }
        report()
    }

    private fun register() {
        if (mode != Mode.NOT_ATTACHED) return
        try {
            initialize()
            owner().lifecycle.addObserver(observer)
            mode = Mode.OWNER
            return
        } catch (t: Throwable) {
            failure = t
        }
        val app = context.applicationContext as? Application
        if (app == null) {
            mode = Mode.UNAVAILABLE
        } else {
            app.registerActivityLifecycleCallbacks(ActivityCounter())
            mode = Mode.FALLBACK
        }
        report()
    }

    private fun report() {
        val log =
            synchronized(lock) {
                val log = log ?: return
                if (reported || mode == Mode.NOT_ATTACHED || mode == Mode.OWNER) return
                reported = true
                log
            }
        if (mode == Mode.FALLBACK) {
            log.info {
                "ProcessLifecycleOwner could not be initialized (androidx Startup's provider removed?); foreground is detected from activities"
            }
        } else {
            log.warn(Code.LIFECYCLE_UNAVAILABLE, failure) { "The app's foreground cannot be observed; no lifecycle events or sessions" }
        }
    }

    override fun addListener(listener: LifecycleSource.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: LifecycleSource.Listener) {
        listeners -= listener
    }

    private fun change(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        for (listener in listeners) {
            try {
                if (foreground) listener.onForeground() else listener.onBackground()
            } catch (t: Throwable) {
                // A listener only sends a message to the writer; nothing to undo.
            }
        }
    }

    // The fallback. Activity callbacks run on the main thread, so the counts need no lock.
    private inner class ActivityCounter : Application.ActivityLifecycleCallbacks {
        private val handler = Handler(Looper.getMainLooper())
        private var started = 0

        // Activities stopped by a configuration change, whose new instance is about to start.
        private var recreating = 0
        private val background = Runnable { if (started == 0) change(false) }

        override fun onActivityStarted(activity: Activity) {
            if (recreating > 0) {
                recreating--
                return
            }
            started++
            handler.removeCallbacks(background)
            if (started == 1) change(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity.isChangingConfigurations) {
                recreating++
                return
            }
            if (started == 0) return
            started--
            if (started == 0) handler.postDelayed(background, BACKGROUND_DELAY_MS)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ): Unit = Unit

        override fun onActivityResumed(activity: Activity): Unit = Unit

        override fun onActivityPaused(activity: Activity): Unit = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ): Unit = Unit

        override fun onActivityDestroyed(activity: Activity): Unit = Unit
    }

    companion object {
        /** `ProcessLifecycleOwner.TIMEOUT_MS`, so both paths report the background alike. */
        const val BACKGROUND_DELAY_MS: Long = 700
    }
}
