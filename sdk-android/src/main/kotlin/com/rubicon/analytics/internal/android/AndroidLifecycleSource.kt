package com.rubicon.analytics.internal.android

import android.content.Context
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
 * Foreground and background from `ProcessLifecycleOwner` (Lifecycle 2, Session 3): `ON_START` is
 * the foreground, `ON_STOP` the background, about 700 ms after the last activity stops.
 *
 * - [start] initializes `ProcessLifecycleInitializer` through `AppInitializer`, which does nothing if
 *   androidx Startup already ran it, so an app that removed the Startup provider still works.
 * - The observer is added on the main thread; from another thread it is posted, never waited for.
 *   Added while the app is started, it receives `ON_START` at once.
 * - If any of it fails: never in the foreground, one WARN.
 */
@RubiconInternal
internal class AndroidLifecycleSource(
    private val context: Context,
    private val owner: () -> LifecycleOwner = { ProcessLifecycleOwner.get() },
    private val initialize: () -> Unit = {
        AppInitializer.getInstance(context).initializeComponent(ProcessLifecycleInitializer::class.java)
    },
) : LifecycleSource {
    private val listeners = CopyOnWriteArrayList<LifecycleSource.Listener>()

    @Volatile
    override var isForeground: Boolean = false
        private set

    private val observer =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> change(true)
                Lifecycle.Event.ON_STOP -> change(false)
                else -> Unit
            }
        }

    override fun start(log: Log) {
        val register = Runnable { register(log) }
        if (Looper.myLooper() == Looper.getMainLooper()) register.run() else Handler(Looper.getMainLooper()).post(register)
    }

    private fun register(log: Log) {
        try {
            initialize()
            owner().lifecycle.addObserver(observer)
        } catch (t: Throwable) {
            log.warn(Code.LIFECYCLE_UNAVAILABLE, t) { "The app's foreground cannot be observed; no lifecycle events or sessions" }
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
}
