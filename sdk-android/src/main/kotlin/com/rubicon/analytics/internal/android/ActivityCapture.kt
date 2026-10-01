package com.rubicon.analytics.internal.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.ScreenName
import com.rubicon.analytics.internal.AutomaticCapture
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Automatic screen views and deep links from the activity callbacks (Lifecycle 3, 6; Privacy 5b).
 *
 * Screens: one per activity shown, named by [ScreenName] or the class. Not again for the same
 * activity resumed while the app stays in the foreground (a dialog closing), nor for an activity
 * recreated by a configuration change (rotation); again after the app returned from the background.
 *
 * Deep links: a `VIEW` intent with data, when the activity is first created (not when it is
 * restored), when a `singleTop` activity receives a new intent (through `OnNewIntentProvider`,
 * androidx.activity 1.7+) and, for other activities that call `setIntent()`, when it resumes. Each
 * intent counts once: seen intents are remembered weakly, by identity.
 *
 * Activity callbacks run on the main thread, so the state needs no lock. Activities are held only
 * weakly (non-negotiable 5).
 */
@RubiconInternal
internal class ActivityCapture(
    private val capture: AutomaticCapture,
    private val screens: Boolean,
    private val deepLinks: Boolean,
) : Application.ActivityLifecycleCallbacks {
    private var lastResumed: WeakReference<Activity>? = null
    private var lastResumedClass: String? = null
    private var recreating = false
    private var started = 0

    // Intent does not override equals, so this is a weak identity set.
    private val seenIntents = WeakHashMap<Intent, Boolean>()

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        if (!deepLinks) return
        safely {
            // A restored activity's link was counted when it was first opened.
            if (savedInstanceState == null) deepLink(activity, activity.intent) else activity.intent?.let { seenIntents[it] = true }
            NewIntents.listen(activity, ::deepLink)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityResumed(activity: Activity) {
        safely {
            val same = lastResumed?.get() === activity || (recreating && activity.javaClass.name == lastResumedClass)
            recreating = false
            lastResumed = WeakReference(activity)
            lastResumedClass = activity.javaClass.name
            if (screens && !same) screen(activity)
            // A link set with setIntent() in onNewIntent.
            if (deepLinks) deepLink(activity, activity.intent)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        noteRecreation(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        noteRecreation(activity)
        // The app left the foreground: the next resume is a new visit, even of the same activity.
        if (started == 0 && !activity.isChangingConfigurations) {
            lastResumed = null
            lastResumedClass = null
        }
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ): Unit = Unit

    override fun onActivityDestroyed(activity: Activity) {
        noteRecreation(activity)
    }

    private fun noteRecreation(activity: Activity) {
        if (activity.isChangingConfigurations && lastResumed?.get() === activity) recreating = true
    }

    private fun screen(activity: Activity) {
        val annotated = activity.javaClass.getAnnotation(ScreenName::class.java)?.value
        if (annotated != null) {
            capture.screen(Autocapture.SCREEN_VIEWS, annotated, mapOf(NAME_SOURCE to "annotation"))
            return
        }
        val simple = activity.javaClass.simpleName
        if (simple.isEmpty()) return
        capture.screen(Autocapture.SCREEN_VIEWS, simple.removeSuffix(ACTIVITY).ifEmpty { simple }, mapOf(NAME_SOURCE to "class"))
    }

    private fun deepLink(
        activity: Activity,
        intent: Intent?,
    ) {
        if (intent == null || intent.action != Intent.ACTION_VIEW || seenIntents.containsKey(intent)) return
        val uri = intent.data ?: return
        seenIntents[intent] = true
        capture.track(Autocapture.DEEP_LINKS, DEEP_LINK_OPENED, DeepLink.properties(uri, referrer(activity, intent)))
    }

    // The link's own intent first: for a new intent, the activity's referrer describes its first one.
    private fun referrer(
        activity: Activity,
        intent: Intent,
    ): Uri? =
        DeepLink.intentReferrer(intent)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) activity.referrer else null

    // A callback must never throw into the app (non-negotiable 1).
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // The screen or link is missed; the app goes on.
        }
    }

    companion object {
        const val DEEP_LINK_OPENED: String = "Deep Link Opened"
        const val NAME_SOURCE: String = "name_source"
        private const val ACTIVITY = "Activity"

        /** Registers the callbacks when screens or deep links are configured; nothing otherwise. */
        fun attach(
            context: Context,
            enabled: Set<Autocapture>,
            capture: AutomaticCapture,
        ) {
            val screens = Autocapture.SCREEN_VIEWS in enabled
            val deepLinks = Autocapture.DEEP_LINKS in enabled
            if (!screens && !deepLinks) return
            val app = context.applicationContext as? Application ?: return
            app.registerActivityLifecycleCallbacks(ActivityCapture(capture, screens, deepLinks))
        }
    }
}
