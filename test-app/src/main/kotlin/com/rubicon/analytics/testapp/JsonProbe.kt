package com.rubicon.analytics.testapp

import android.content.Context
import com.rubicon.analytics.internal.json.Json
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The org.json behaviours of step 2.4, on this device's runtime instead of android-json
 * (Serialization 5). Writes `name<TAB>result` lines to `files/json.txt`; the suite checks the
 * results and parses every written line with an independent parser.
 */
object JsonProbe {
    fun run(context: Context) {
        val results = LinkedHashMap<String, String>()
        results["null_kept"] = Json.write(mapOf("coupon" to null))
        results["kotlin_null_removes"] = JSONObject().put("coupon", null as Any?).toString()
        results["whole_double"] = Json.write(mapOf("price" to 50.0))
        results["slash"] = Json.write(mapOf("url" to "https://a.com/b"))
        results["exponent"] = Json.write(mapOf("x" to 1.0e21, "y" to 1.5e-7))
        results["nan_throws"] =
            try {
                JSONObject().put("x", Double.NaN)
                "false"
            } catch (e: JSONException) {
                "true"
            }
        results["opt_default"] = JSONObject("""{"t":"123"}""").optLong("timestamp").toString()
        val all =
            mapOf(
                "s" to "text",
                "b" to true,
                "i" to 7,
                "l" to 9_007_199_254_740_993L,
                "d" to 1.5,
                "n" to null,
                "list" to listOf("a", 1, null, listOf(true)),
                "map" to mapOf("k" to mapOf("deep" to "v")),
            )
        results["round_trip"] = (Json.readObject(Json.write(all))!!.toMap() == all).toString()
        val text = "ğüşİıöç 日本 😀 \"quoted\" back\\slash /slash \n\t\r\b\u000c \u0001    "
        val special = Json.write(mapOf(text to text))
        results["special_line"] = special
        results["special_round_trip"] = (Json.readObject(special)!!.toMap() == mapOf(text to text)).toString()
        val big = "x".repeat(32 * 1024)
        results["line_32kb"] = (Json.readObject(Json.write(mapOf("big" to big)))!!.string("big") == big).toString()
        File(context.filesDir, "json.txt").writeText(results.entries.joinToString("\n") { "${it.key}\t${it.value.replace("\n", "\\n")}" })
    }
}
