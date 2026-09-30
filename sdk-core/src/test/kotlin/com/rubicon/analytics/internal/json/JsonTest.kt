package com.rubicon.analytics.internal.json

import com.rubicon.analytics.testing.SeededRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.json.JSONException
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Date
import kotlin.random.Random
import kotlinx.serialization.json.Json as IndependentJson

class JsonTest {
    // kotlinx-serialization-json, test-only: a parser unrelated to org.json (Serialization 5).
    private fun parseIndependently(text: String): JsonElement = IndependentJson.parseToJsonElement(text)

    // Structural comparison through the independent parser: key order, spacing and escaping are ignored.
    private fun assertSameJson(
        expected: String,
        actual: String,
    ) = assertEquals(parseIndependently(expected), parseIndependently(actual))

    @Nested
    inner class `writing lines` {
        @Test
        fun `writes null as JSON null`() {
            assertEquals("""{"coupon":null}""", Json.write(mapOf("coupon" to null)))
        }

        @Test
        fun `writes null inside nested maps and lists`() {
            assertSameJson("""{"a":{"b":null},"c":[1,null]}""", Json.write(mapOf("a" to mapOf("b" to null), "c" to listOf(1, null))))
        }

        @Test
        fun `writes every normalized type, compact`() {
            val map =
                linkedMapOf(
                    "s" to "text",
                    "t" to true,
                    "f" to false,
                    "i" to 7,
                    "l" to 9_007_199_254_740_993L,
                    "d" to 1.5,
                    "n" to null,
                    "list" to listOf("a", 1, 2.5, false, null, listOf(1), mapOf("k" to "v")),
                    "map" to mapOf("nested" to mapOf("deep" to listOf(1, 2))),
                )

            val line = Json.write(map)

            assertSameJson(
                """{"s":"text","t":true,"f":false,"i":7,"l":9007199254740993,"d":1.5,"n":null,""" +
                    """"list":["a",1,2.5,false,null,[1],{"k":"v"}],"map":{"nested":{"deep":[1,2]}}}""",
                line,
            )
            assertFalse(line.any { it.isWhitespace() }, "compact: $line")
        }

        @Test
        fun `writes an empty map, list and string`() {
            assertSameJson(
                """{"m":{},"l":[],"s":""}""",
                Json.write(mapOf("m" to emptyMap<String, Any?>(), "l" to emptyList<Any?>(), "s" to "")),
            )
        }

        @ParameterizedTest
        @ValueSource(doubles = [Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY])
        fun `refuses NaN and Infinity, which org json cannot write`(value: Double) {
            assertThrows<IllegalArgumentException> { Json.write(mapOf("x" to value)) }
            assertThrows<IllegalArgumentException> { Json.write(mapOf("x" to listOf(value))) }
        }

        @Test
        fun `refuses values the normalizer never produces`() {
            listOf(1.5f, Date(0), setOf(1), 'c', 1.toByte(), Any()).forEach { value ->
                assertThrows<IllegalArgumentException>(value.javaClass.name) { Json.write(mapOf("x" to value)) }
            }
            assertThrows<IllegalArgumentException> { Json.write(mapOf("x" to mapOf(1 to "a"))) }
            assertThrows<IllegalArgumentException> { Json.write(mapOf("x" to mapOf(null to "a"))) }
        }
    }

    // Each org.json behaviour the SDK relies on or works around; a changed runtime fails here.
    @Nested
    inner class `org json behaviours` {
        @Test
        fun `a Kotlin null removes the key, which is why the layer writes NULL`() {
            assertEquals("{}", JSONObject().put("coupon", null as Any?).toString())
        }

        @Test
        fun `a whole-number double is written as an integer`() {
            assertEquals("""{"price":50}""", Json.write(mapOf("price" to 50.0)))
        }

        @Test
        fun `a slash is escaped`() {
            val line = Json.write(mapOf("url" to "https://a.com/b"))

            assertEquals("""{"url":"https:\/\/a.com\/b"}""", line)
            assertSameJson("""{"url":"https://a.com/b"}""", line)
        }

        @Test
        fun `a very large double uses exponent form`() {
            val line = Json.write(mapOf("x" to 1.0e21, "y" to 1.5e-7))

            assertEquals("""{"x":1.0E21,"y":1.5E-7}""", line)
            parseIndependently(line)
        }

        @Test
        fun `NaN makes org json throw`() {
            assertThrows<JSONException> { JSONObject().put("x", Double.NaN) }
        }

        @Test
        fun `opt methods return defaults for missing or wrong fields, which is why reading is typed`() {
            val obj = JSONObject("""{"t":"123"}""")

            assertEquals(0L, obj.optLong("timestamp"))
            assertEquals(123L, obj.optLong("t"))
        }
    }

    @Nested
    inner class `round trip` {
        @Test
        fun `every normalized type comes back`() {
            val map =
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

            assertEquals(map, Json.readObject(Json.write(map))!!.toMap())
        }

        @Test
        fun `numbers come back as the smallest of Int, Long and Double`() {
            val back = Json.readObject(Json.write(mapOf("small" to 7L, "whole" to 50.0, "big" to 5_000_000_000L)))!!.toMap()

            // Asserted per key: in one mapOf, Kotlin would infer every integer literal as Long.
            assertEquals(Integer.valueOf(7), back["small"])
            assertEquals(Integer.valueOf(50), back["whole"])
            assertEquals(java.lang.Long.valueOf(5_000_000_000L), back["big"])
        }

        @Test
        fun `non-ASCII, escaped and control characters survive`() {
            val text = "ğüşİıöç 日本 😀 \"quoted\" back\\slash /slash \n\t\r\b\u000c \u0001 \u2028 \u2029"
            val line = Json.write(mapOf(text to text))

            assertEquals(mapOf(text to text), Json.readObject(line)!!.toMap())
            assertEquals(JsonPrimitive(text), (parseIndependently(line) as JsonObject)[text])
            assertFalse(line.contains('\n'), "a line never contains a raw line break")
        }

        @Test
        fun `a 32 KB line survives`() {
            val value = "x".repeat(32 * 1024)
            val line = Json.write(mapOf("big" to value))

            assertTrue(line.length > 32 * 1024)
            assertEquals(value, Json.readObject(line)!!.string("big"))
            parseIndependently(line)
        }

        @Test
        fun `seeded random maps are valid JSON to an independent parser and read back the same`() {
            val random = SeededRandom(7)
            repeat(2_000) {
                val map = randomMap(random, depth = 0)
                val line = Json.write(map)

                assertEquals(numbersAsLong(map), fromIndependent(parseIndependently(line)), line)
                assertEquals(numbersAsLong(map), numbersAsLong(Json.readObject(line)!!.toMap()), line)
            }
        }
    }

    @Nested
    inner class `reading fields` {
        private val device =
            Json.readObject(
                """{"v":1,"device_id":"0191","consent":"granted","last_build":140,"retry":{"step":3},""" +
                    """"missing_is_fine":null,"future_key":[1,2],"step":"3","ratio":0.5,"huge":1.0E30,"flag":true}""",
            )!!

        @Test
        fun `reads fields of the right type`() {
            assertEquals(1, device.int("v"))
            assertEquals(1L, device.long("v"))
            assertEquals("0191", device.string("device_id"))
            assertEquals(140L, device.long("last_build"))
            assertEquals(3, device.fields("retry")?.int("step"))
            assertEquals(mapOf("step" to 3), device.map("retry"))
            assertEquals(0.5, device.double("ratio"))
            assertEquals(1.0, device.double("v"))
            assertEquals(true, device.boolean("flag"))
        }

        @Test
        fun `a missing key is null, never a default`() {
            assertFalse(device.has("user_id"))
            assertNull(device.string("user_id"))
            assertNull(device.long("timestamp"))
            assertNull(device.int("timestamp"))
            assertNull(device.double("timestamp"))
            assertNull(device.boolean("opted_out"))
            assertNull(device.fields("session"))
            assertNull(device.map("session"))
        }

        @Test
        fun `a wrongly typed value is null, never coerced`() {
            assertNull(device.long("step"))
            assertNull(device.int("device_id"))
            assertNull(device.string("last_build"))
            assertNull(device.long("ratio"))
            assertNull(device.long("huge"))
            assertNull(device.boolean("consent"))
            assertNull(device.double("consent"))
            assertNull(device.fields("future_key"))
            assertNull(device.int("huge"))
        }

        @Test
        fun `an int must fit`() {
            val fields = Json.readObject("""{"big":5000000000}""")!!

            assertEquals(5_000_000_000L, fields.long("big"))
            assertNull(fields.int("big"))
        }

        @Test
        fun `JSON null is present but has no value`() {
            assertTrue(device.has("missing_is_fine"))
            assertTrue(device.isNull("missing_is_fine"))
            assertFalse(device.isNull("v"))
            assertFalse(device.isNull("absent"))
            assertNull(device.string("missing_is_fine"))
        }

        @Test
        fun `extra keys are kept and can be listed`() {
            assertTrue(device.keys.containsAll(listOf("future_key", "v", "retry")))
            assertEquals(listOf(1, 2), device.toMap()["future_key"])
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "[1,2]", "\"text\"", "12", "null", "{\"type\":\"track\"", "{\"a\":1}}x", "{a:1", "not json"])
        fun `anything but one JSON object is null`(text: String) {
            assertNull(Json.readObject(text), text)
        }
    }

    private fun randomMap(
        random: Random,
        depth: Int,
    ): Map<String, Any?> = (0 until random.nextInt(0, 6)).associate { randomString(random) to randomValue(random, depth) }

    private fun randomValue(
        random: Random,
        depth: Int,
    ): Any? =
        when (random.nextInt(if (depth < 3) 9 else 7)) {
            0 -> null
            1 -> random.nextBoolean()
            2 -> random.nextInt()
            3 -> random.nextLong()
            4 -> random.nextDouble(-1e6, 1e6)
            5, 6 -> randomString(random)
            7 -> List(random.nextInt(0, 4)) { randomValue(random, depth + 1) }
            else -> randomMap(random, depth + 1)
        }

    // Hostile characters included: quotes, backslashes, controls, astral and separator code points.
    private fun randomString(random: Random): String {
        val pool = "aZ09 \"\\/\n\t\u0000\u001f\u2028ğş日😀{}[]:,"
        val chars = mutableListOf<String>()
        repeat(random.nextInt(0, 12)) {
            val start = random.nextInt(pool.length)
            chars += if (pool[start].isHighSurrogate()) pool.substring(start, start + 2) else pool[start].toString()
        }
        return chars.joinToString("").filterNot { it.isLowSurrogate() && chars.none { c -> c.length == 2 && c[1] == it } }
    }

    // Both parsers may return a whole number where a Double was written; compare numbers as Long when whole.
    private fun numbersAsLong(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.mapValues { numbersAsLong(it.value) }
            is List<*> -> value.map(::numbersAsLong)
            is Int -> value.toLong()
            is Double -> if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong() else value
            else -> value
        }

    private fun fromIndependent(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonObject -> element.mapValues { fromIndependent(it.value) }
            is JsonArray -> element.map(::fromIndependent)
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    else -> numbersAsLong(element.longOrNull ?: element.content.toDouble())
                }
        }
}
