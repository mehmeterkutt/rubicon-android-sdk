package com.rubicon.analytics.network

import android.net.Uri
import com.rubicon.analytics.internal.android.DeepLink
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The properties `sdk-android` produces, against the golden files of `sdk-core` (step 8.4); the core
 * checks the rest of these events against the same files.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenPropertiesTest {
    private fun goldenProperties(name: String): Map<String, Any?> {
        val properties = JSONObject(File("../sdk-core/src/test/resources/golden/$name.json").readText()).getJSONObject("properties")
        return properties.keys().asSequence().associateWith { key -> normalize(properties.get(key)) }
    }

    // Numbers compared as Long, whatever width each side chose.
    private fun normalize(value: Any?): Any? = if (value is Number) value.toLong() else value

    @Test
    fun `Deep Link Opened properties match the golden file`() {
        val properties =
            DeepLink.properties(
                Uri.parse(
                    "https://shop.example.com/promo/fall?utm_source=newsletter&utm_medium=email&utm_campaign=fall_sale&token=secret#top",
                ),
                Uri.parse("android-app://com.google.android.gm"),
            )

        assertEquals(goldenProperties("deep-link-opened"), properties.mapValues { normalize(it.value) })
    }

    @Test
    fun `Network Request properties match the golden file`() {
        val request = Request.Builder().url("https://api.shop.example.com/users/12345/orders?page=2#top").build()
        val response =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ByteArray(5120).toResponseBody())
                .build()

        val properties = NetworkRequestProperties.of(request, response, null, 184, maskPathIds = true)

        assertEquals(goldenProperties("network-request"), properties.mapValues { normalize(it.value) })
    }
}
