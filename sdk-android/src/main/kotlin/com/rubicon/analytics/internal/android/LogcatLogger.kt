package com.rubicon.analytics.internal.android

import android.util.Log
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconLogger

/**
 * The Android default logger (Logging 1): Logcat with the tag `Rubicon`, used when the developer sets
 * no `logging.logger` and when theirs throws. The only code in the SDK that calls `android.util.Log`
 * (`checkSourceRules`). The level was already checked by the SDK (Logging 3).
 */
internal object LogcatLogger : RubiconLogger {
    const val TAG: String = "Rubicon"

    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, message, throwable)
            LogLevel.INFO -> Log.i(TAG, message, throwable)
            LogLevel.WARN -> Log.w(TAG, message, throwable)
            LogLevel.ERROR -> Log.e(TAG, message, throwable)
            LogLevel.OFF -> Unit
        }
    }
}
