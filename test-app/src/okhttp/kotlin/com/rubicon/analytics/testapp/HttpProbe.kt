package com.rubicon.analytics.testapp

import com.rubicon.analytics.network.RubiconNetworkInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/** One call through the app's own OkHttp client with the interceptor (Lifecycle 1). */
object HttpProbe {
    fun call(
        app: TestApp,
        url: String,
    ) {
        val client = OkHttpClient.Builder().addInterceptor(RubiconNetworkInterceptor(app.analytics, setOf("localhost"))).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
    }
}
