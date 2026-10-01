package com.rubicon.analytics.internal.store

import com.rubicon.analytics.internal.json.JsonFields

/**
 * Strict reading for store codecs: a field that is missing or JSON `null` is absent, a field of the
 * wrong type makes the whole file corrupt. [decodeOrNull] turns that into `null`, which the
 * [StoreFile] treats as unreadable.
 */
internal object StoreFields {
    private class Corrupt : RuntimeException() {
        // Control flow only; no stack trace is needed.
        override fun fillInStackTrace(): Throwable = this
    }

    fun <T> decodeOrNull(block: () -> T): T? =
        try {
            block()
        } catch (e: Corrupt) {
            null
        }

    fun corrupt(): Nothing = throw Corrupt()

    private fun JsonFields.absent(key: String) = !has(key) || isNull(key)

    fun JsonFields.optionalString(key: String): String? = if (absent(key)) null else string(key) ?: corrupt()

    fun JsonFields.requiredString(key: String): String = optionalString(key) ?: corrupt()

    fun JsonFields.optionalLong(key: String): Long? = if (absent(key)) null else long(key) ?: corrupt()

    fun JsonFields.requiredLong(key: String): Long = optionalLong(key) ?: corrupt()

    fun JsonFields.optionalInt(key: String): Int? = if (absent(key)) null else int(key) ?: corrupt()

    fun JsonFields.optionalFields(key: String): JsonFields? = if (absent(key)) null else fields(key) ?: corrupt()

    fun JsonFields.optionalMap(key: String): Map<String, Any?>? = if (absent(key)) null else map(key) ?: corrupt()

    /** An object of whole numbers, such as counters; empty when absent. */
    fun JsonFields.intMap(key: String): Map<String, Int> {
        val nested = optionalFields(key) ?: return emptyMap()
        return nested.keys.associateWith { nested.int(it) ?: corrupt() }
    }
}
