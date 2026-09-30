package com.rubicon.analytics.internal.json

import com.rubicon.analytics.RubiconInternal
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The only code in the SDK that touches `org.json` (Serialization 1). `org.json` is Android's own:
 * the platform provides it at runtime, and JVM tests use `android-json`, a packaging of the same
 * source.
 *
 * Writing takes normalized values only: `String`, `Boolean`, `Int`, `Long`, `Double`, `null`, and
 * `List` or `Map<String, *>` of those, as the property normalizer produces them (Serialization 2).
 * Anything else is a bug in the caller and throws [IllegalArgumentException]. Known `org.json`
 * behaviours, each pinned by a test:
 *
 * - a Kotlin `null` would remove the key, so `null` is written as `JSONObject.NULL`: `{"coupon":null}`;
 * - a whole-number `Double` is written as an integer: `50.0` becomes `50` (the backend treats numbers
 *   as one numeric type);
 * - `/` is escaped as `\/`, which is valid JSON; tests compare JSON structurally, never as text;
 * - very large or small doubles may use exponent form, such as `1.0E21`, which is valid JSON;
 * - NaN and Infinity make `org.json` throw; the normalizer drops them, and [write] refuses them;
 * - read back, a number is an `Int` if it fits, else a `Long`, else a `Double`: `7L` returns as `7`,
 *   and `50.0`, written as `50`, returns as the `Int` `50`;
 * - Android keeps the insertion order of keys; `android-json`, the JVM test copy, does not. Nothing
 *   may depend on key order, and tests compare structurally.
 *
 * Reading exists only for the small store files (Disk 7) and for the corruption and age check of
 * stored lines (Disk 3, 5); events are never parsed back into objects. [JsonFields] reads each
 * field with a type check and never falls back to a default the way `opt*` methods do.
 */
@RubiconInternal
public object Json {
    /** A compact JSON object for [map]: one event line, or a store file. */
    public fun write(map: Map<String, Any?>): String = toJsonObject(map).toString()

    /**
     * Parses [text] as one JSON object; `null` when it is not valid JSON, not an object, or has
     * anything but whitespace after the object (`org.json` would ignore it).
     */
    public fun readObject(text: String): JsonFields? =
        try {
            val tokener = JSONTokener(text)
            val value = tokener.nextValue() as? JSONObject
            if (value != null && tokener.nextClean() == END) JsonFields(value) else null
        } catch (e: JSONException) {
            null
        }

    // What JSONTokener.nextClean returns once the input is used up.
    private const val END = '\u0000'

    private fun toJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, toJsonValue(value))
        return obj
    }

    private fun toJsonValue(value: Any?): Any =
        when (value) {
            null -> JSONObject.NULL
            is String, is Boolean, is Int, is Long -> value
            is Double -> {
                require(!value.isNaN() && !value.isInfinite()) { "NaN and Infinity must be dropped before JSON" }
                value
            }
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((key, nested) in value) {
                    require(key is String) { "Map keys must be strings, found ${key?.javaClass?.name}" }
                    obj.put(key, toJsonValue(nested))
                }
                obj
            }
            is List<*> -> JSONArray().also { array -> value.forEach { array.put(toJsonValue(it)) } }
            else -> throw IllegalArgumentException("Not a normalized value: ${value.javaClass.name}")
        }
}

/**
 * Typed, checked access to a parsed JSON object. Every getter returns `null` when the key is
 * missing, holds JSON `null`, or holds a value of another type; nothing is coerced, so a string
 * `"123"` is not a number and a number is not a string.
 */
@RubiconInternal
public class JsonFields internal constructor(
    private val obj: JSONObject,
) {
    public fun has(key: String): Boolean = obj.has(key)

    /** Whether [key] is present with the JSON value `null`. */
    public fun isNull(key: String): Boolean = obj.has(key) && obj.get(key) === JSONObject.NULL

    public val keys: Set<String>
        get() = keysOf(obj).toSet()

    public fun string(key: String): String? = value(key) as? String

    public fun boolean(key: String): Boolean? = value(key) as? Boolean

    /** An integral number that fits a `Long`; `1.5` and `1.0E30` give `null`. */
    public fun long(key: String): Long? = integral(value(key))

    /** An integral number that fits an `Int`. */
    public fun int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** Any number, as a `Double`. */
    public fun double(key: String): Double? = (value(key) as? Number)?.toDouble()

    public fun fields(key: String): JsonFields? = (value(key) as? JSONObject)?.let(::JsonFields)

    /** A nested object as normalized values (see [Json]); JSON `null` inside it becomes `null`. */
    public fun map(key: String): Map<String, Any?>? = (value(key) as? JSONObject)?.let(::toMap)

    /** The whole object as normalized values. */
    public fun toMap(): Map<String, Any?> = toMap(obj)

    private fun value(key: String): Any? = if (obj.has(key)) obj.get(key).takeUnless { it === JSONObject.NULL } else null

    private companion object {
        // Android declares keys() as a raw Iterator; every key of a JSONObject is a String.
        fun keysOf(obj: JSONObject): Sequence<String> = obj.keys().asSequence().map { it as String }

        fun integral(value: Any?): Long? =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }

        fun toMap(obj: JSONObject): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            for (key in keysOf(obj)) map[key] = fromJson(obj.get(key))
            return map
        }

        fun fromJson(value: Any?): Any? =
            when (value) {
                JSONObject.NULL, null -> null
                is JSONObject -> toMap(value)
                is JSONArray -> List(value.length()) { fromJson(value.get(it)) }
                is Int, is Long -> value
                is Number -> value.toDouble()
                else -> value
            }
    }
}
