package com.rubicon.analytics.testapp

import android.app.Application
import android.os.Process
import android.os.StrictMode
import com.rubicon.analytics.Analytics
import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.LogLevel
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Creates the SDK as an app should, in `onCreate`, inside a StrictMode policy that kills the process
 * on any disk read or write: the factory must not touch the disk on the main thread (Public API 9).
 * Every process runs this, including `:remote`, where the SDK must do nothing.
 */
class TestApp : Application() {
    lateinit var analytics: Analytics
        private set

    /** Set by the suite: SIGKILL right after this many events were stored, while others are written. */
    @Volatile
    var killAfterStored: Int = 0

    private val stored = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        val config = config()
        val previous = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        try {
            analytics = Analytics(this, config)
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    private fun config(): AnalyticsConfig {
        val queued = File(filesDir, QUEUED_FILE)
        return AnalyticsConfig(API_KEY).apply {
            host = HOST
            autocapture.enabled += Autocapture.DEEP_LINKS
            logging.level = LogLevel.DEBUG
            // The ids the SDK stored, for the process kill test; written on the SDK's worker.
            logging.eventObserver =
                EventObserver { update ->
                    if (update is EventUpdate.Queued) {
                        FileWriter(queued, true).use { it.write(update.event.messageId + "\n") }
                        if (stored.incrementAndGet() == killAfterStored) Process.killProcess(Process.myPid())
                    }
                }
        }
    }

    companion object {
        const val API_KEY = "rk_test_app"

        /** The suite's fake server, reached through `adb reverse tcp:18765`. */
        const val HOST = "http://127.0.0.1:18765"

        /** Another name for the same server: the interceptor captures it, unlike the SDK's own host. */
        const val API_BASE = "http://localhost:18765"
        const val QUEUED_FILE = "queued.txt"
    }
}
