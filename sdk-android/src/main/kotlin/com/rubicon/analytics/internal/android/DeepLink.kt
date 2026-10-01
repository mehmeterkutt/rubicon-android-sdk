package com.rubicon.analytics.internal.android

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * What a deep link may reveal (Privacy 5b): scheme, host, path, the five `utm_*` parameters and
 * the referrer's package or host. Never another query parameter, never the fragment; the list is
 * fixed, because an extensible one invites tokens. The path is sent as it is: tokens in a path are
 * the app's to mask in `beforeSend`. Unknown or empty values are left out.
 */
internal object DeepLink {
    val UTM_PARAMETERS: List<String> = listOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")

    fun properties(
        uri: Uri,
        referrer: Uri?,
    ): Map<String, Any?> {
        val properties = LinkedHashMap<String, Any?>()
        properties.putKnown("scheme", uri.scheme)
        properties.putKnown("host", uri.host)
        properties.putKnown("path", uri.path)
        if (uri.isHierarchical) {
            for (name in UTM_PARAMETERS) properties.putKnown(name, uri.getQueryParameter(name))
        }
        properties.putKnown("referrer", referrer?.let(::referrerName))
        return properties
    }

    /** The referrer the sender put in the intent (`EXTRA_REFERRER`, or `EXTRA_REFERRER_NAME` from API 22). */
    fun intentReferrer(intent: Intent): Uri? {
        @Suppress("DEPRECATION")
        val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_REFERRER)
        if (uri != null) return uri
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return null
        return intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)?.let(Uri::parse)
    }

    // `android-app://com.example.mail` names the package, an http(s) referrer its host.
    private fun referrerName(referrer: Uri): String? = referrer.host

    private fun MutableMap<String, Any?>.putKnown(
        key: String,
        value: String?,
    ) {
        if (!value.isNullOrBlank()) this[key] = value
    }
}
