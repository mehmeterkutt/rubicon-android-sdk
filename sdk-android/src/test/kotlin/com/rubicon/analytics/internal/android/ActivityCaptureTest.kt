package com.rubicon.analytics.internal.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.core.app.OnNewIntentProvider
import androidx.core.util.Consumer
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.ScreenName
import com.rubicon.analytics.internal.AutomaticCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

class CheckoutActivity : Activity()

@ScreenName("checkout")
class PaymentFlowActivity : Activity()

class SettingsActivity : Activity()

// What ComponentActivity does from androidx.activity 1.7, without its newer lifecycle.
class LinkActivity :
    Activity(),
    OnNewIntentProvider {
    private val listeners = mutableListOf<Consumer<Intent>>()

    override fun addOnNewIntentListener(listener: Consumer<Intent>) {
        listeners += listener
    }

    override fun removeOnNewIntentListener(listener: Consumer<Intent>) {
        listeners -= listener
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listeners.forEach { it.accept(intent) }
    }
}

// Lifecycle 3 and 6, Privacy 5b. Robolectric has no API 21 or 22: the referrer before
// Activity.getReferrer is the intent's extra, tested directly; the emulator suite runs API 21.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class ActivityCaptureTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val captured = mutableListOf<Captured>()
    private val registered = mutableListOf<Application.ActivityLifecycleCallbacks>()

    private class Captured(
        val screen: Boolean,
        val gate: Autocapture,
        val name: String,
        val properties: Map<String, Any?>,
    )

    private val capture =
        object : AutomaticCapture {
            override fun track(
                gate: Autocapture,
                name: String,
                properties: Map<String, Any?>,
            ) {
                captured += Captured(false, gate, name, properties)
            }

            override fun screen(
                gate: Autocapture,
                name: String,
                properties: Map<String, Any?>,
            ) {
                captured += Captured(true, gate, name, properties)
            }
        }

    @After
    fun unregister() {
        registered.forEach { app.unregisterActivityLifecycleCallbacks(it) }
    }

    private fun attach(
        screens: Boolean = true,
        deepLinks: Boolean = true,
    ) {
        val callbacks = ActivityCapture(capture, screens, deepLinks)
        app.registerActivityLifecycleCallbacks(callbacks)
        registered += callbacks
    }

    private fun screens() = captured.filter { it.screen }.map { it.name }

    private fun links() = captured.filter { !it.screen }

    private fun link(
        url: String,
        action: String = Intent.ACTION_VIEW,
    ) = Intent(action, Uri.parse(url)).putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://com.example.mail"))

    private inline fun <reified T : Activity> launch(intent: Intent? = null): ActivityController<T> =
        (if (intent == null) Robolectric.buildActivity(T::class.java) else Robolectric.buildActivity(T::class.java, intent)).setup()

    @Test
    fun `the name is the class without Activity, or the annotation, with its source`() {
        attach()

        launch<CheckoutActivity>()
        launch<PaymentFlowActivity>()

        assertEquals(listOf("Checkout", "checkout"), screens())
        assertEquals(listOf("class", "annotation"), captured.map { it.properties["name_source"] })
        assertTrue(captured.all { it.gate == Autocapture.SCREEN_VIEWS })
    }

    @Test
    fun `the same activity resumed again in the foreground is not sent again`() {
        attach()
        val activity = launch<CheckoutActivity>()

        // A dialog or the permission prompt pauses and resumes the activity.
        activity.pause().resume()

        assertEquals(listOf("Checkout"), screens())
    }

    @Test
    fun `rotation is not a new screen`() {
        attach()
        val activity = launch<CheckoutActivity>()
        val before = activity.get()

        activity.configurationChange(Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })

        assertTrue("the activity was recreated", before !== activity.get())
        assertEquals(listOf("Checkout"), screens())
    }

    @Test
    fun `returning from the background shows the screen again`() {
        attach()
        val activity = launch<CheckoutActivity>()

        activity.pause().stop()
        activity.restart().start().resume()

        assertEquals(listOf("Checkout", "Checkout"), screens())
    }

    @Test
    fun `going to another activity and back sends each`() {
        attach()
        val checkout = launch<CheckoutActivity>()
        checkout.pause()
        val settings = launch<SettingsActivity>()
        checkout.stop()

        settings.pause()
        checkout.restart().start().resume()
        settings.stop().destroy()

        assertEquals(listOf("Checkout", "Settings", "Checkout"), screens())
    }

    @Test
    fun `a deep link sends scheme, host, path, utm parameters and the referrer only`() {
        attach()

        launch<CheckoutActivity>(link("https://shop.example.com/reset/42?utm_source=mail&utm_campaign=fall&token=secret#frag"))

        val link = links().single()
        assertEquals(Autocapture.DEEP_LINKS, link.gate)
        assertEquals("Deep Link Opened", link.name)
        assertEquals(
            mapOf(
                "scheme" to "https",
                "host" to "shop.example.com",
                "path" to "/reset/42",
                "utm_source" to "mail",
                "utm_campaign" to "fall",
                "referrer" to "com.example.mail",
            ),
            link.properties,
        )
        assertFalse(link.properties.values.any { it.toString().contains("secret") || it.toString().contains("frag") })
    }

    @Test
    fun `a restored or rotated activity does not send its link again`() {
        attach()
        val intent = link("myapp://product/7")

        val activity = launch<CheckoutActivity>(intent)
        activity.configurationChange(Configuration(app.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        Robolectric
            .buildActivity(CheckoutActivity::class.java, link("myapp://product/8"))
            .create(Bundle())
            .start()
            .resume()

        assertEquals(listOf("/7"), links().map { it.properties["path"] })
    }

    @Test
    fun `a singleTop activity's new intent is captured once`() {
        attach()
        val activity = launch<LinkActivity>()
        val intent = link("myapp://promo?utm_medium=push")

        activity.newIntent(intent)
        activity.newIntent(intent)

        assertEquals(
            listOf(
                mapOf("scheme" to "myapp", "host" to "promo", "utm_medium" to "push", "referrer" to "com.example.mail"),
            ),
            links().map {
                it.properties
            },
        )
    }

    @Test
    fun `other activities report a new link through setIntent, and going back does not repeat a link`() {
        attach()
        val first = launch<CheckoutActivity>(link("myapp://product/1"))
        first.pause()
        val second = launch<SettingsActivity>(link("myapp://product/2"))
        first.stop()
        second.pause()
        first.restart().start().resume()

        first.pause()
        first.get().intent = link("myapp://product/3")
        first.resume()

        assertEquals(listOf("/1", "/2", "/3"), links().map { it.properties["path"] })
    }

    @Test
    fun `only VIEW intents with data are links`() {
        attach()

        launch<CheckoutActivity>(link("myapp://product/7", action = Intent.ACTION_SEND))
        launch<SettingsActivity>(Intent(Intent.ACTION_VIEW))

        assertEquals(emptyList<Captured>(), links())
    }

    @Test
    fun `deep links are off by default, screens on`() {
        val defaults = AnalyticsConfig("rk_test").autocapture.enabled
        assertFalse(Autocapture.DEEP_LINKS in defaults)
        val before = registeredCallbacks()
        ActivityCapture.attach(app, defaults, capture)
        registered += registeredCallbacks() - before.toSet()

        launch<CheckoutActivity>(link("myapp://product/7"))

        assertEquals(listOf("Checkout"), screens())
        assertEquals(emptyList<Captured>(), links())
    }

    @Test
    fun `nothing is registered when neither type is enabled`() {
        val before = registeredCallbacks()

        ActivityCapture.attach(app, setOf(Autocapture.APP_LIFECYCLE), capture)

        assertEquals(before, registeredCallbacks())
    }

    @Suppress("UNCHECKED_CAST")
    private fun registeredCallbacks(): List<Application.ActivityLifecycleCallbacks> {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks").apply { isAccessible = true }
        return synchronized(field.get(app)!!) { ArrayList(field.get(app) as List<Application.ActivityLifecycleCallbacks>) }
    }

    @Test
    fun `the referrer comes from the intent's extras, else from the activity`() {
        assertEquals("com.example.mail", DeepLink.intentReferrer(link("myapp://x"))!!.host)
        assertEquals(
            "com.example.news",
            DeepLink
                .intentReferrer(
                    Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://com.example.news"),
                )!!
                .host,
        )
        assertEquals(null, DeepLink.intentReferrer(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `opaque links and web referrers keep only what they have`() {
        assertEquals(mapOf("scheme" to "mailto"), DeepLink.properties(Uri.parse("mailto:someone@example.com"), null))
        assertEquals(
            mapOf("scheme" to "https", "host" to "a.example", "path" to "/x", "referrer" to "www.google.com"),
            DeepLink.properties(Uri.parse("https://a.example/x?utm_source=&q=1"), Uri.parse("https://www.google.com/")),
        )
    }
}
