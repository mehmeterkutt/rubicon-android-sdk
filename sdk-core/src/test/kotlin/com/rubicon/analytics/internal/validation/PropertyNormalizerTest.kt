package com.rubicon.analytics.internal.validation

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.json.Json
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.IssueCounter
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.validation.PropertyProblem.Kind
import com.rubicon.analytics.testing.RecordingLogger
import com.rubicon.analytics.testing.SeededRandom
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.serialization.json.Json as IndependentJson

class PropertyNormalizerTest {
    private enum class Plan { FREE, PRO }

    private data class Custom(
        val secret: String,
    )

    private fun normalize(vararg pairs: Pair<Any?, Any?>) = PropertyNormalizer.normalize(mapOf(*pairs))

    // The normalized value of one property, or null when it was dropped (with its problem kinds).
    private fun convert(value: Any?): Pair<Any?, List<Kind>> {
        val result = normalize("p" to value)
        return result.values["p"] to result.problems.map { it.kind }
    }

    private fun assertKept(
        expected: Any?,
        value: Any?,
    ) {
        val result = normalize("p" to value)
        assertTrue(result.values.containsKey("p"), "kept: $value")
        assertEquals(expected, result.values["p"])
        assertEquals(emptyList<PropertyProblem>(), result.problems)
    }

    private fun assertDropped(
        kind: Kind,
        value: Any?,
    ) {
        val result = normalize("p" to value, "other" to "kept")
        assertFalse(result.values.containsKey("p"), "dropped: $value")
        assertEquals(mapOf("other" to "kept"), result.values)
        assertEquals(listOf(kind), result.problems.map { it.kind })
        assertEquals("p", result.problems.single().path)
    }

    @Nested
    inner class `conversion table` {
        @Test
        fun `String, Boolean, Int, Long, Double and null are kept as they are`() {
            assertKept("a", "a")
            assertKept(true, true)
            assertKept(7, 7)
            assertKept(7L, 7L)
            assertKept(1.5, 1.5)
            assertKept(null, null)
        }

        @Test
        fun `Float goes to Double through its decimal text`() {
            assertKept(0.1, 0.1f)
            assertKept(1.5, 1.5f)
            assertEquals(0.1, convert(0.1f).first as Double, 0.0)
        }

        @Test
        fun `Byte and Short become Int`() {
            assertKept(7, 7.toByte())
            assertKept(-7, (-7).toShort())
        }

        @Test
        fun `BigDecimal becomes Double, unsupported if not finite`() {
            assertKept(19.99, BigDecimal("19.99"))
            assertDropped(Kind.OUT_OF_RANGE, BigDecimal("1e400"))
        }

        @Test
        fun `BigInteger becomes Long if it fits, unsupported otherwise`() {
            assertKept(123L, BigInteger.valueOf(123))
            assertKept(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE))
            assertKept(Long.MIN_VALUE, BigInteger.valueOf(Long.MIN_VALUE))
            assertDropped(Kind.OUT_OF_RANGE, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
        }

        @Test
        fun `Char becomes a one-character string`() {
            assertKept("A", 'A')
            assertKept("\uFFFD", '\uD800')
        }

        @Test
        fun `arrays, including primitive arrays, become lists`() {
            assertKept(listOf(1, "a", null), arrayOf<Any?>(1, "a", null))
            assertKept(listOf(1, 2), intArrayOf(1, 2))
            assertKept(listOf(1L, 2L), longArrayOf(1, 2))
            assertKept(listOf(1.5), doubleArrayOf(1.5))
            assertKept(listOf(0.1), floatArrayOf(0.1f))
            assertKept(listOf(true, false), booleanArrayOf(true, false))
            assertKept(listOf(1, 2), shortArrayOf(1, 2))
            assertKept(listOf(1, 2), byteArrayOf(1, 2))
            assertKept(listOf("a", "b"), charArrayOf('a', 'b'))
        }

        @Test
        fun `sets and other collections become lists in iteration order`() {
            assertKept(listOf("a", "b"), linkedSetOf("a", "b"))
            assertKept(listOf(3, 1), ArrayDeque(listOf(3, 1)))
        }

        @Test
        fun `Date becomes the ISO string, unsupported outside the years 0000 to 9999`() {
            assertKept("1970-01-01T00:00:00.000Z", Date(0))
            assertKept("2026-10-13T08:00:00.000Z", Date(1_791_878_400_000L))
            assertDropped(Kind.OUT_OF_RANGE, Date(253_402_300_800_000L))
        }

        @Test
        fun `an enum becomes its name`() {
            assertKept("PRO", Plan.PRO)
        }

        @Test
        fun `a map with string keys is copied`() {
            assertKept(mapOf("k" to 1, "n" to null), mapOf("k" to 1, "n" to null))
        }

        @Test
        fun `NaN and Infinity are unsupported, as Double and as Float`() {
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY).forEach {
                assertDropped(Kind.NOT_FINITE, it)
            }
        }

        @Test
        fun `java time values are unsupported with the conversion hint of Serialization 2`() {
            val result = normalize("trial_ends_at" to Instant.ofEpochMilli(0))

            assertEquals(emptyMap<String, Any?>(), result.values)
            assertEquals(
                "Property \"trial_ends_at\" dropped: Instant is not supported.\n" +
                    "Pass Date.from(value), value.toEpochMilli() or a string instead.",
                result.problems.single().message,
            )
            assertDropped(Kind.JAVA_TIME, LocalDate.of(2026, 10, 13))
        }

        @Test
        fun `JSONObject and JSONArray are unsupported, with a hint`() {
            assertDropped(Kind.JSON_TYPE, JSONObject())
            assertDropped(Kind.JSON_TYPE, JSONArray())
            assertTrue(
                normalize("p" to JSONObject())
                    .problems
                    .single()
                    .message
                    .endsWith("Pass a Map or a List instead."),
            )
        }

        @Test
        fun `anything else is unsupported`() {
            listOf(1u, 1 to 2, Custom("x"), Any(), AtomicInteger(1), StringBuilder("s"), Thread.State.NEW::class).forEach {
                assertDropped(Kind.UNSUPPORTED_TYPE, it)
            }
        }
    }

    @Nested
    inner class `nested collections` {
        @Test
        fun `a bad element drops only that element`() {
            val result = normalize("list" to listOf(1, Any(), 3, Double.NaN))

            assertEquals(mapOf("list" to listOf(1, 3)), result.values)
            assertEquals(listOf("list[1]", "list[3]"), result.problems.map { it.path })
        }

        @Test
        fun `paths name nested keys and positions`() {
            val result = normalize("cart" to mapOf("items" to listOf(mapOf("sku" to "A1"), mapOf("sku" to Any()))))

            assertEquals("cart.items[1].sku", result.problems.single().path)
            assertEquals(mapOf("cart" to mapOf("items" to listOf(mapOf("sku" to "A1"), emptyMap<String, Any?>()))), result.values)
        }

        @Test
        fun `a nested entry with a key that is not a string is dropped`() {
            val result = normalize("m" to mapOf(1 to "a", "b" to 2))

            assertEquals(mapOf("m" to mapOf("b" to 2)), result.values)
            assertEquals(Kind.INVALID_KEY, result.problems.single().kind)
            assertEquals("m", result.problems.single().path)
            assertEquals("Integer", result.problems.single().type)
        }
    }

    @Nested
    inner class `property keys` {
        @Test
        fun `a blank key is dropped`() {
            val result = normalize("" to 1, "  " to 2, "ok" to 3)

            assertEquals(mapOf("ok" to 3), result.values)
            assertEquals(listOf(Kind.INVALID_KEY, Kind.INVALID_KEY), result.problems.map { it.kind })
        }

        @Test
        fun `a key of 255 characters is kept, 256 is dropped`() {
            val key254 = "k".repeat(254)
            val key255 = "k".repeat(255)
            val key256 = "k".repeat(256)

            val result = normalize(key254 to 1, key255 to 2, key256 to 3)

            assertEquals(setOf(key254, key255), result.values.keys)
            assertEquals("256 characters", result.problems.single().type)
        }

        @Test
        fun `keys are never trimmed`() {
            assertEquals(setOf(" padded "), normalize(" padded " to 1).values.keys)
        }

        @Test
        fun `a key that is not a string is dropped`() {
            val result = PropertyNormalizer.normalize(mapOf(1 to "a", null to "b", "ok" to "c"))

            assertEquals(mapOf("ok" to "c"), result.values)
            assertEquals(listOf("Integer", "null"), result.problems.map { it.type })
        }
    }

    @Nested
    inner class `string values` {
        @Test
        fun `1023 and 1024 characters are kept, 1025 are cut to 1024 without a marker`() {
            assertKept("a".repeat(1023), "a".repeat(1023))
            assertKept("a".repeat(1024), "a".repeat(1024))

            val (value, kinds) = convert("a".repeat(1025))

            assertEquals("a".repeat(1024), value)
            assertEquals(listOf(Kind.TRUNCATED), kinds)
        }

        @Test
        fun `truncation never splits a surrogate pair`() {
            val text = "a".repeat(1023) + "😀" + "b"

            assertEquals("a".repeat(1023), convert(text).first)
        }

        @Test
        fun `unpaired surrogates become U+FFFD, pairs are kept`() {
            assertKept("a\uFFFDb", "a\uD800b")
            assertKept("\uFFFDx", "\uDC00x")
            assertKept("x\uFFFD", "x\uD83D")
            assertKept("😀 ok", "😀 ok")
            assertKept("\uFFFD\uFFFD", "\uDC00\uD800")
            assertKept("\uFFFD😀", "\uD800😀")
        }
    }

    @Nested
    inner class `nesting depth` {
        @Test
        fun `three levels of nesting are allowed, the fourth is dropped`() {
            val three = mapOf("cart" to mapOf("items" to listOf(mapOf("sku" to "A1", "qty" to 2))))
            // Four containers: a, b and c are levels 1 to 3, d would be level 4.
            val four = mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1))), "keep" to 1))

            assertEquals(three, PropertyNormalizer.normalize(three).values)
            val result = PropertyNormalizer.normalize(four)
            assertEquals(mapOf("a" to mapOf("b" to mapOf("c" to emptyMap<String, Any?>()), "keep" to 1)), result.values)
            assertEquals("a.b.c.d", result.problems.single().path)
            assertEquals(Kind.TOO_DEEP, result.problems.single().kind)
        }

        @Test
        fun `a map that contains itself stops at the depth limit`() {
            val loop = HashMap<String, Any?>()
            loop["self"] = loop
            loop["n"] = 1

            val result = PropertyNormalizer.normalize(mapOf("loop" to loop))

            assertEquals(Kind.TOO_DEEP, result.problems.single().kind)
            assertEquals(3, depthOf(result.values["loop"]))
        }

        @Test
        fun `a list that contains itself stops at the depth limit`() {
            val loop = ArrayList<Any?>()
            loop.add(loop)

            assertEquals(Kind.TOO_DEEP, normalize("loop" to loop).problems.single().kind)
        }

        private fun depthOf(value: Any?): Int =
            when (value) {
                is Map<*, *> -> 1 + (value.values.maxOfOrNull(::depthOf) ?: 0)
                is List<*> -> 1 + (value.maxOfOrNull(::depthOf) ?: 0)
                else -> 0
            }
    }

    @Nested
    inner class `property count` {
        private fun properties(
            count: Int,
            prefix: String = "p",
        ) = (1..count).associate { "$prefix$it" to it }

        @Test
        fun `255 and 256 properties are kept, the 257th is dropped`() {
            assertEquals(255, PropertyNormalizer.normalize(properties(255)).values.size)
            assertEquals(emptyList<PropertyProblem>(), PropertyNormalizer.normalize(properties(256)).problems)

            val result = PropertyNormalizer.normalize(properties(257))

            assertEquals(256, result.values.size)
            assertFalse(result.values.containsKey("p257"))
            assertEquals(1, result.problems.single().count)
            assertEquals(Kind.TOO_MANY, result.problems.single().kind)
        }

        @Test
        fun `super properties merge in and the event's own key wins`() {
            val merged = PropertyNormalizer.mergeSuperProperties(mapOf("plan" to "event", "a" to 1), mapOf("plan" to "super", "b" to 2))

            assertEquals(mapOf("plan" to "event", "a" to 1, "b" to 2), merged.values)
            assertEquals(emptyList<PropertyProblem>(), merged.problems)
        }

        @Test
        fun `above 256, super properties are dropped first`() {
            val merged = PropertyNormalizer.mergeSuperProperties(properties(250), properties(10, "s"))

            assertEquals(256, merged.values.size)
            assertTrue(merged.values.keys.containsAll(properties(250).keys))
            assertEquals(4, merged.problems.single().count)
        }

        @Test
        fun `256 own properties leave no room for super properties`() {
            val merged = PropertyNormalizer.mergeSuperProperties(properties(256), mapOf("p1" to "super", "s" to 1))

            assertEquals(properties(256), merged.values)
            assertEquals(1, merged.problems.single().count)
        }
    }

    @Nested
    inner class `event names` {
        @Test
        fun `a name of 1 to 255 characters is valid and never trimmed`() {
            assertNull(PropertyNormalizer.checkName("a"))
            assertNull(PropertyNormalizer.checkName(" Purchase Completed "))
            assertNull(PropertyNormalizer.checkName("n".repeat(255)))
        }

        @Test
        fun `a blank name or one of 256 characters is invalid`() {
            listOf("", "   ", "n".repeat(256)).forEach {
                assertEquals(Kind.INVALID_NAME, PropertyNormalizer.checkName(it)?.kind)
            }
        }
    }

    @Nested
    inner class `deep copies` {
        @Test
        fun `changing the caller's maps and lists afterwards changes nothing`() {
            val tags = mutableListOf("a")
            val nested = mutableMapOf<String, Any?>("tags" to tags)
            val input = mutableMapOf<String, Any?>("nested" to nested, "n" to 1)

            val result = PropertyNormalizer.normalize(input)
            input["n"] = 2
            nested["extra"] = true
            tags += "b"

            assertEquals(mapOf("nested" to mapOf("tags" to listOf("a")), "n" to 1), result.values)
            assertNotSame(tags, (result.values["nested"] as Map<*, *>)["tags"])
        }

        @Test
        @Suppress("UNCHECKED_CAST")
        fun `the result cannot be changed`() {
            val result = normalize("m" to mapOf("k" to listOf(1)))

            assertThrows<UnsupportedOperationException> { (result.values as MutableMap<String, Any?>)["x"] = 1 }
            val nested = result.values["m"] as MutableMap<String, Any?>
            assertThrows<UnsupportedOperationException> { nested["x"] = 1 }
            assertThrows<UnsupportedOperationException> { (nested["k"] as MutableList<Any?>).add(2) }
        }
    }

    @Nested
    inner class `problem reporting` {
        private val logger = RecordingLogger()
        private val counted = mutableListOf<Code>()
        private val log = Log(LogLevel.DEBUG, null, logger, IssueCounter { counted += it })

        @Test
        fun `in release every problem is logged with its code and nothing is thrown`() {
            val result = normalize("t" to Instant.EPOCH, "long" to "x".repeat(2_000), "" to 1)

            PropertyProblem.report(result.problems, debug = false, log = log)

            assertEquals(listOf(Code.PROPERTY_DROPPED, Code.PROPERTY_TRUNCATED, Code.PROPERTY_DROPPED), counted)
            assertEquals(LogLevel.WARN, logger.lines.first().level)
            assertTrue(logger.messages.first().startsWith("PROPERTY_DROPPED: Property \"t\" dropped"))
        }

        @Test
        fun `in debug the first problem that throws becomes IllegalArgumentException`() {
            val result = normalize("long" to "x".repeat(2_000), "bad" to Any())

            val error = assertThrows<IllegalArgumentException> { PropertyProblem.report(result.problems, debug = true, log = log) }

            assertEquals("Property \"bad\" dropped: Object is not supported.", error.message)
            assertEquals(listOf(Code.PROPERTY_TRUNCATED), counted)
        }

        @Test
        fun `a truncation never throws, even in debug, and is logged once per key`() {
            repeat(2) {
                PropertyProblem.report(normalize("bio" to "x".repeat(2_000), "note" to "y".repeat(2_000)).problems, debug = true, log = log)
            }

            assertEquals(2, logger.lines.count { it.level == LogLevel.WARN })
            assertEquals(4, counted.size)
        }

        @Test
        fun `messages name keys and types, never values`() {
            val secret = "s3cr3t-value"
            val result = normalize("custom" to Custom(secret), "pair" to (secret to 1), "t" to Instant.EPOCH, "n" to Double.NaN)

            result.problems.forEach { assertFalse(it.message.contains(secret), it.message) }
            assertEquals(listOf("Custom", "Pair", "Instant", "Double"), result.problems.map { it.type })
        }

        @Test
        fun `each kind has a message and the documented code`() {
            Kind.entries.forEach { kind ->
                val problem = PropertyProblem(kind, "p", "Type", count = 3)
                assertTrue(problem.message.isNotBlank())
                assertTrue(problem.toString().contains(kind.name))
            }
            assertEquals(Code.INVALID_EVENT_NAME, Kind.INVALID_NAME.code)
            assertEquals(Code.TOO_MANY_PROPERTIES, Kind.TOO_MANY.code)
            assertFalse(Kind.TRUNCATED.throwsInDebug)
            assertEquals(1, Kind.entries.count { !it.throwsInDebug })
        }
    }

    @Test
    fun `seeded random maps never make it throw and always give valid JSON`() {
        val random = SeededRandom(2025)
        repeat(3_000) {
            val result = PropertyNormalizer.normalize(randomMap(random, depth = 0))
            val line = Json.write(result.values)

            IndependentJson.parseToJsonElement(line)
            PropertyProblem.report(result.problems, debug = false, log = Log(LogLevel.OFF, null, RecordingLogger()))
        }
    }

    private fun randomMap(
        random: Random,
        depth: Int,
    ): Map<Any?, Any?> {
        val map = LinkedHashMap<Any?, Any?>()
        repeat(random.nextInt(0, 8)) { map[randomKey(random)] = randomValue(random, depth) }
        if (depth > 0 && random.nextInt(20) == 0) map["self"] = map
        return map
    }

    private fun randomKey(random: Random): Any? =
        when (random.nextInt(12)) {
            0 -> null
            1 -> random.nextInt()
            2 -> ""
            3 -> "k".repeat(256)
            else -> randomString(random, 12)
        }

    private fun randomValue(
        random: Random,
        depth: Int,
    ): Any? =
        when (random.nextInt(24)) {
            0 -> null
            1 -> random.nextBoolean()
            2 -> random.nextInt()
            3 -> random.nextLong()
            4 -> random.nextDouble()
            5 -> listOf(Double.NaN, Double.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)[random.nextInt(3)]
            6 -> random.nextFloat()
            7 -> BigDecimal(random.nextDouble() * 1e6)
            8 -> BigInteger.valueOf(random.nextLong()).shiftLeft(random.nextInt(0, 3))
            9 -> Date(random.nextLong(-100_000_000_000_000L, 300_000_000_000_000L))
            10 -> Instant.ofEpochMilli(random.nextLong(0, 1_000_000))
            11 -> Plan.entries[random.nextInt(2)]
            12 -> Any()
            13 -> random.nextInt(0, 65_536).toChar()
            14 -> randomString(random, 2_000)
            15 -> intArrayOf(random.nextInt(), random.nextInt())
            // Only simple values in sets: a set hashes its elements, and a map that contains itself
            // cannot be hashed, so no caller could build such a set.
            16 -> setOf(random.nextInt(), randomString(random, 5), null)
            17, 18 -> List(random.nextInt(0, 4)) { randomValue(random, depth + 1) }
            else -> if (depth < 6) randomMap(random, depth + 1) else "leaf"
        }

    // Random UTF-16 units: unpaired surrogates, controls and quotes included.
    private fun randomString(
        random: Random,
        maxLength: Int,
    ): String = String(CharArray(random.nextInt(0, maxLength)) { random.nextInt(0, 65_536).toChar() })
}
