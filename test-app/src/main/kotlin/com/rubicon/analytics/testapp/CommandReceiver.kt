package com.rubicon.analytics.testapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The suite's commands, sent with `am broadcast -n <package>/.CommandReceiver --es cmd <name>`:
 * - `track`: `count` events named `name` (default `Burst`) with an `index` property; with
 *   `killAfterStored = n` the process is killed with SIGKILL as soon as the SDK has stored n of
 *   them, while it is still writing the others;
 * - `flush`;
 * - `http`: one OkHttp call through the interceptor (does nothing without OkHttp);
 * - `json`: the org.json checks on this device's runtime (Serialization 5).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = context.applicationContext as TestApp
        when (intent.getStringExtra("cmd")) {
            "track" -> track(app, intent)
            "flush" -> app.analytics.flush()
            "http" -> {
                val pending = goAsync()
                Thread {
                    try {
                        HttpProbe.call(app, "${TestApp.API_BASE}/api/users/12345/orders?token=secret")
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            "json" -> JsonProbe.run(app)
        }
    }

    private fun track(
        app: TestApp,
        intent: Intent,
    ) {
        val name = intent.getStringExtra("name") ?: "Burst"
        val count = intent.getIntExtra("count", 1)
        app.killAfterStored = intent.getIntExtra("killAfterStored", 0)
        for (index in 0 until count) app.analytics.track(name, mapOf("index" to index))
    }
}
