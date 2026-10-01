package com.rubicon.analytics.testapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs in `:remote`: the SDK there is a no-op, so this creates no file and sends nothing. */
class RemoteReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val analytics = (context.applicationContext as TestApp).analytics
        analytics.track("From Remote")
        analytics.flush()
    }
}
