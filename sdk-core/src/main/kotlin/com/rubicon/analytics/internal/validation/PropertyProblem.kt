package com.rubicon.analytics.internal.validation

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log

/**
 * Something the normalizer dropped or changed. It names the property's [path] and the value's
 * [type], never the value itself (Logging 4a).
 *
 * @property path the key, with nested keys after dots and list positions in brackets:
 *   `cart.items[2]`. Empty for the event name and for a problem about the whole map.
 * @property type the simple class name of the offending value or key, when there is one.
 */
@RubiconInternal
public class PropertyProblem(
    public val kind: Kind,
    public val path: String,
    public val type: String? = null,
    /** For [Kind.TOO_MANY]: how many properties were dropped. */
    public val count: Int = 0,
) {
    public enum class Kind(
        public val code: Code,
        /** Whether `debug = true` turns it into an exception (Serialization 2, 3). */
        public val throwsInDebug: Boolean,
    ) {
        /** A value of a type the SDK does not send. */
        UNSUPPORTED_TYPE(Code.PROPERTY_DROPPED, true),

        /** A `java.time` value; the message says how to convert it. */
        JAVA_TIME(Code.PROPERTY_DROPPED, true),

        /** An `org.json` value; the message suggests a map or a list. */
        JSON_TYPE(Code.PROPERTY_DROPPED, true),

        /** NaN or Infinity. */
        NOT_FINITE(Code.PROPERTY_DROPPED, true),

        /** A number or date that cannot be sent without losing it: a `BigInteger` beyond `Long`, a year beyond 0000 to 9999. */
        OUT_OF_RANGE(Code.PROPERTY_DROPPED, true),

        /** A key that is not a string, is blank, or is longer than 255 characters. */
        INVALID_KEY(Code.PROPERTY_DROPPED, true),

        /** A map or list nested deeper than 3 levels. */
        TOO_DEEP(Code.PROPERTY_DROPPED, true),

        /** A string longer than 1024 characters, cut to 1024. Logged once per key, never thrown. */
        TRUNCATED(Code.PROPERTY_TRUNCATED, false),

        /** More than 256 properties; the extra ones were dropped, super properties first. */
        TOO_MANY(Code.TOO_MANY_PROPERTIES, true),

        /** An event or screen name that is blank or longer than 255 characters. */
        INVALID_NAME(Code.INVALID_EVENT_NAME, true),
    }

    /** The log line and exception message: keys and types only. */
    public val message: String
        get() =
            when (kind) {
                Kind.UNSUPPORTED_TYPE -> "Property \"$path\" dropped: $type is not supported."
                // The exact text of Serialization decision 2.
                Kind.JAVA_TIME ->
                    "Property \"$path\" dropped: $type is not supported.\n" +
                        "Pass Date.from(value), value.toEpochMilli() or a string instead."
                Kind.JSON_TYPE -> "Property \"$path\" dropped: $type is not supported. Pass a Map or a List instead."
                Kind.NOT_FINITE -> "Property \"$path\" dropped: NaN and Infinity are not supported."
                Kind.OUT_OF_RANGE -> "Property \"$path\" dropped: this $type cannot be sent without losing its value."
                Kind.INVALID_KEY -> "Property key${where()} dropped: keys are non-blank strings of at most 255 characters ($type)."
                Kind.TOO_DEEP -> "Property \"$path\" dropped: values nest at most 3 levels deep."
                Kind.TRUNCATED -> "Property \"$path\" truncated to 1024 characters."
                Kind.TOO_MANY -> "$count properties dropped: an event has at most 256, super properties are dropped first."
                Kind.INVALID_NAME -> "Event dropped: its name is blank or longer than 255 characters."
            }

    private fun where(): String = if (path.isEmpty()) "" else " in \"$path\""

    override fun toString(): String = "PropertyProblem($kind, \"$path\")"

    public companion object {
        /**
         * Logs [problems] with their codes, or, with [debug], throws [IllegalArgumentException] for the
         * first one that throws in debug. A truncation is only logged, once per key.
         */
        public fun report(
            problems: List<PropertyProblem>,
            debug: Boolean,
            log: Log,
        ) {
            for (problem in problems) {
                if (debug && problem.kind.throwsInDebug) throw IllegalArgumentException(problem.message)
                val repeatKey = if (problem.kind == Kind.TRUNCATED) problem.path else null
                log.warn(problem.kind.code, repeatKey = repeatKey) { problem.message }
            }
        }
    }
}
