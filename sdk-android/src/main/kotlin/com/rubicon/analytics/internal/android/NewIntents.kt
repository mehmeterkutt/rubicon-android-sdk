package com.rubicon.analytics.internal.android

import android.app.Activity
import android.content.Intent
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import java.lang.ref.WeakReference

/**
 * `singleTop` deep links (Lifecycle 6, D13): activity callbacks do not report `onNewIntent`, so the
 * SDK listens through `OnNewIntentProvider`, which `ComponentActivity` implements from
 * androidx.activity 1.7. androidx is `compileOnly`: [Provider], the only code that names its
 * classes, is loaded only after a class presence check. Other activities need `setIntent()`.
 */
internal object NewIntents {
    private val available: Boolean by lazy {
        try {
            Class.forName("androidx.core.app.OnNewIntentProvider")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun listen(
        activity: Activity,
        onIntent: (Activity, Intent) -> Unit,
    ) {
        if (available) Provider.listen(activity, onIntent)
    }

    private object Provider {
        fun listen(
            activity: Activity,
            onIntent: (Activity, Intent) -> Unit,
        ) {
            if (activity !is OnNewIntentProvider) return
            // The activity owns the listener; the listener holds the activity weakly.
            val reference = WeakReference(activity)
            activity.addOnNewIntentListener(
                Consumer { intent ->
                    try {
                        reference.get()?.let { onIntent(it, intent) }
                    } catch (t: Throwable) {
                        // The link is missed; the app goes on.
                    }
                },
            )
        }
    }
}
