package com.rubicon.analytics.internal.android

import android.os.Build

/**
 * `device.is_emulator` (Context 3): the markers the Android emulator, Genymotion and older SDK
 * images leave in `Build`. A heuristic, as in PostHog; it needs no permission. `Build.DEVICE` is not
 * read (Context 3).
 */
internal object Emulator {
    fun isCurrent(): Boolean = isEmulator(Build.FINGERPRINT, Build.MODEL, Build.MANUFACTURER, Build.HARDWARE, Build.PRODUCT)

    fun isEmulator(
        fingerprint: String?,
        model: String?,
        manufacturer: String?,
        hardware: String?,
        product: String?,
    ): Boolean {
        val f = fingerprint.orEmpty()
        val m = model.orEmpty()
        val h = hardware.orEmpty()
        val p = product.orEmpty()
        return f.startsWith("generic") ||
            f.startsWith("unknown") ||
            f.contains("emulator") ||
            m.contains("google_sdk") ||
            m.contains("Emulator") ||
            m.contains("Android SDK built for") ||
            manufacturer.orEmpty().contains("Genymotion") ||
            h == "goldfish" ||
            h == "ranchu" ||
            h.contains("vbox86") ||
            p.startsWith("sdk") ||
            p.contains("_sdk") ||
            p.contains("emulator") ||
            p.contains("simulator") ||
            p.contains("vbox86p")
    }
}
