package com.rubicon.analytics.internal.validation

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.util.IsoDates
import com.rubicon.analytics.internal.validation.PropertyProblem.Kind
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.Date

/**
 * Turns whatever the developer passes as properties, traits or super properties into an immutable
 * deep copy made only of `String`, `Boolean`, `Int`, `Long`, `Double`, `null`, `List` and
 * `Map<String, *>` (Public API 3, Event Model 3, Serialization 2 and 3), in one pass.
 *
 * It never throws and never keeps a reference to the caller's maps or lists; a bad value costs only
 * that value, and a bad element of a collection only that element. What it dropped or changed is
 * returned as [PropertyProblem]s for the caller to log or, in debug, throw
 * ([PropertyProblem.report]).
 */
@RubiconInternal
public object PropertyNormalizer {
    public const val MAX_KEY_LENGTH: Int = 255
    public const val MAX_NAME_LENGTH: Int = 255
    public const val MAX_STRING_LENGTH: Int = 1024
    public const val MAX_PROPERTIES: Int = 256
    public const val MAX_DEPTH: Int = 3

    public class Result(
        public val values: Map<String, Any?>,
        public val problems: List<PropertyProblem>,
    )

    public fun normalize(properties: Map<*, *>): Result {
        val problems = mutableListOf<PropertyProblem>()
        val values = LinkedHashMap<String, Any?>()
        var extra = 0
        for ((key, value) in properties) {
            val name = checkKey(key, "", problems) ?: continue
            if (values.size == MAX_PROPERTIES) {
                extra++
                continue
            }
            val normalized = normalizeValue(value, name, level = 1, problems)
            if (normalized !== Dropped) values[name] = normalized
        }
        if (extra > 0) problems += PropertyProblem(Kind.TOO_MANY, "", count = extra)
        return Result(Collections.unmodifiableMap(values), problems)
    }

    /**
     * Merges super properties into an event's normalized properties: the event's own key wins, and
     * above [MAX_PROPERTIES] the super properties are dropped first (Event Model 1, Serialization 3).
     */
    public fun mergeSuperProperties(
        own: Map<String, Any?>,
        superProperties: Map<String, Any?>,
    ): Result {
        val merged = LinkedHashMap<String, Any?>()
        val room = MAX_PROPERTIES - own.size
        var dropped = 0
        for ((key, value) in superProperties) {
            if (key in own) continue
            if (merged.size < room) merged[key] = value else dropped++
        }
        merged.putAll(own)
        val problems = if (dropped > 0) listOf(PropertyProblem(Kind.TOO_MANY, "", count = dropped)) else emptyList()
        return Result(Collections.unmodifiableMap(merged), problems)
    }

    /** An event or screen name: 1 to 255 characters and not blank; never trimmed (Serialization 3). */
    public fun checkName(name: String): PropertyProblem? =
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) PropertyProblem(Kind.INVALID_NAME, "") else null

    // Marks a value that was dropped; `null` is a real value.
    private object Dropped

    private fun checkKey(
        key: Any?,
        parent: String,
        problems: MutableList<PropertyProblem>,
    ): String? {
        val problem =
            when {
                key !is String -> key?.javaClass?.simpleName ?: "null"
                key.isBlank() -> "blank"
                key.length > MAX_KEY_LENGTH -> "${key.length} characters"
                else -> return key
            }
        problems += PropertyProblem(Kind.INVALID_KEY, parent, problem)
        return null
    }

    private fun normalizeValue(
        value: Any?,
        path: String,
        level: Int,
        problems: MutableList<PropertyProblem>,
    ): Any? {
        fun drop(kind: Kind): Dropped {
            problems += PropertyProblem(kind, path, value?.javaClass?.simpleName)
            return Dropped
        }

        return when (value) {
            null, is Boolean, is Int, is Long -> value
            is String -> string(value, path, problems)
            is Double -> if (value.isFinite()) value else drop(Kind.NOT_FINITE)
            // Through its decimal text, so 0.1f is sent as 0.1, not 0.10000000149011612.
            is Float -> if (value.isFinite()) value.toString().toDouble() else drop(Kind.NOT_FINITE)
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is BigDecimal -> value.toDouble().takeIf { it.isFinite() } ?: drop(Kind.OUT_OF_RANGE)
            is BigInteger -> if (value.bitLength() <= 63) value.toLong() else drop(Kind.OUT_OF_RANGE)
            is Char -> string(value.toString(), path, problems)
            is Date -> IsoDates.format(value.time) ?: drop(Kind.OUT_OF_RANGE)
            is Enum<*> -> value.name
            is Map<*, *> -> container(level, ::drop) { nested(value, path, level, problems) }
            is Collection<*> -> container(level, ::drop) { list(value, path, level, problems) }
            is Array<*> -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is IntArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is LongArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is DoubleArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is FloatArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is BooleanArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is ShortArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is ByteArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            is CharArray -> container(level, ::drop) { list(value.asList(), path, level, problems) }
            else -> drop(kindOfUnsupported(value))
        }
    }

    // java.time and org.json are recognized by name so their classes are never loaded (Serialization 2).
    private fun kindOfUnsupported(value: Any): Kind {
        val name = value.javaClass.name
        return when {
            name.startsWith("java.time.") -> Kind.JAVA_TIME
            name == "org.json.JSONObject" || name == "org.json.JSONArray" -> Kind.JSON_TYPE
            else -> Kind.UNSUPPORTED_TYPE
        }
    }

    // A map or list at [level] is allowed up to MAX_DEPTH; deeper, it is dropped, which also stops a
    // map that contains itself.
    private inline fun container(
        level: Int,
        drop: (Kind) -> Dropped,
        copy: () -> Any,
    ): Any = if (level > MAX_DEPTH) drop(Kind.TOO_DEEP) else copy()

    private fun nested(
        map: Map<*, *>,
        path: String,
        level: Int,
        problems: MutableList<PropertyProblem>,
    ): Map<String, Any?> {
        val copy = LinkedHashMap<String, Any?>()
        for ((key, value) in map) {
            val name = checkKey(key, path, problems) ?: continue
            val normalized = normalizeValue(value, "$path.$name", level + 1, problems)
            if (normalized !== Dropped) copy[name] = normalized
        }
        return Collections.unmodifiableMap(copy)
    }

    private fun list(
        items: Collection<*>,
        path: String,
        level: Int,
        problems: MutableList<PropertyProblem>,
    ): List<Any?> {
        val copy = ArrayList<Any?>(items.size)
        items.forEachIndexed { index, item ->
            val normalized = normalizeValue(item, "$path[$index]", level + 1, problems)
            if (normalized !== Dropped) copy += normalized
        }
        return Collections.unmodifiableList(copy)
    }

    // Unpaired surrogates become U+FFFD; then the string is cut to 1024 characters, never inside a
    // surrogate pair, with no marker (Serialization 2, 3).
    private fun string(
        value: String,
        path: String,
        problems: MutableList<PropertyProblem>,
    ): String {
        val clean = replaceUnpairedSurrogates(value)
        if (clean.length <= MAX_STRING_LENGTH) return clean
        problems += PropertyProblem(Kind.TRUNCATED, path, "String")
        val end = if (Character.isHighSurrogate(clean[MAX_STRING_LENGTH - 1])) MAX_STRING_LENGTH - 1 else MAX_STRING_LENGTH
        return clean.substring(0, end)
    }

    private fun replaceUnpairedSurrogates(value: String): String {
        var i = 0
        var out: StringBuilder? = null
        while (i < value.length) {
            val c = value[i]
            val paired =
                when {
                    Character.isHighSurrogate(c) -> i + 1 < value.length && Character.isLowSurrogate(value[i + 1])
                    Character.isLowSurrogate(c) -> false
                    else -> null
                }
            when (paired) {
                true -> {
                    out?.append(c)?.append(value[i + 1])
                    i += 2
                    continue
                }
                false -> {
                    if (out == null) out = StringBuilder(value.length).append(value, 0, i)
                    out.append('\uFFFD')
                }
                null -> out?.append(c)
            }
            i++
        }
        return out?.toString() ?: value
    }
}
