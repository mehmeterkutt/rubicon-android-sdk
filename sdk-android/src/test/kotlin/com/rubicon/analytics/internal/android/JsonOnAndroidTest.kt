package com.rubicon.analytics.internal.android

import com.rubicon.analytics.internal.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The JSON layer on Android's own org.json (Robolectric loads it from android-all). sdk-core pins the
// same behaviours on android-json (Serialization 5). 23 and 35 are the lowest and highest levels
// Robolectric runs here: it has no API 21 or 22, and API 36 needs Java 21 for the tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class JsonOnAndroidTest {
    @Test
    fun `writes null, whole doubles, slashes and exponents as the JVM tests expect`() {
        assertEquals("""{"coupon":null}""", Json.write(mapOf("coupon" to null)))
        assertEquals("""{"price":50}""", Json.write(mapOf("price" to 50.0)))
        assertEquals("""{"url":"https:\/\/a.com\/b"}""", Json.write(mapOf("url" to "https://a.com/b")))
        assertEquals("""{"x":1.0E21}""", Json.write(mapOf("x" to 1.0e21)))
    }

    @Test
    fun `keeps the insertion order of keys, unlike android-json`() {
        assertEquals("""{"z":1,"a":2,"m":3}""", Json.write(linkedMapOf("z" to 1, "a" to 2, "m" to 3)))
    }

    @Test
    fun `reads numbers as the smallest of Int, Long and Double`() {
        val back = Json.readObject("""{"small":7,"big":5000000000,"ratio":0.5}""")!!.toMap()

        assertEquals(Integer.valueOf(7), back["small"])
        assertEquals(java.lang.Long.valueOf(5_000_000_000L), back["big"])
        assertEquals(java.lang.Double.valueOf(0.5), back["ratio"])
    }

    @Test
    fun `round-trips nested values and non-ASCII text`() {
        val map =
            mapOf(
                "text" to "ğüşİıöç 日本 😀 \"q\" \\ \n",
                "list" to listOf(1, null, listOf(true)),
                "map" to mapOf("k" to mapOf("deep" to "v")),
            )

        assertEquals(map, Json.readObject(Json.write(map))!!.toMap())
    }

    @Test
    fun `rejects trailing content and anything but an object`() {
        assertNull(Json.readObject("""{"a":1}}x"""))
        assertNull(Json.readObject("[1]"))
        assertNull(Json.readObject("""{"type":"track""""))
    }
}
