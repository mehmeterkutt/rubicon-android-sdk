package com.rubicon.analytics.testapp

/** The app has no OkHttp (step 8.3): nothing to call. */
object HttpProbe {
    @Suppress("UNUSED_PARAMETER")
    fun call(
        app: TestApp,
        url: String,
    ) = Unit
}
