package com.rubicon.analytics.internal.store

import com.rubicon.analytics.internal.json.Json
import com.rubicon.analytics.internal.json.JsonFields
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.KeyValueStore

/** Turns a store's value into the fields of its file and back; the version field is the [StoreFile]'s. */
internal interface StoreCodec<T : Any> {
    fun encode(value: T): Map<String, Any?>

    /** The value of [fields], or `null` when a field is missing or has the wrong type. */
    fun decode(fields: JsonFields): T?
}

/**
 * One store file with memory as the source of truth (Disk 7, Identity 1).
 *
 * - [load] reads the file once, on the writer. Missing: [load]'s `onMissing` value, written at once
 *   if there is one. Unreadable, corrupt or of a newer version: `onUnreadable` in memory, one WARN,
 *   and the file is never written for the rest of the process.
 * - [update] changes the value in memory at once, from any thread.
 * - [persist], on the writer only, writes the latest value if it changed since the last write, so
 *   any number of updates in between cost one write. A failed write logs an ERROR and is retried by
 *   the next [persist].
 */
internal class StoreFile<T : Any>(
    private val name: String,
    private val keyValueStore: KeyValueStore,
    private val log: Log,
    private val codec: StoreCodec<T>,
) {
    private val lock = Any()

    @Volatile
    private var current: T? = null

    // Guarded by lock.
    private var dirty = false

    @Volatile
    var isLoaded: Boolean = false
        private set

    /** `false` when the file exists but could not be used; it is then never written (Identity 1). */
    @Volatile
    var isWritable: Boolean = true
        private set

    val value: T?
        get() = current

    /** With [writeMissing] `false` a missing file's value stays unwritten until the next [persist]. */
    fun load(
        onMissing: () -> T?,
        onUnreadable: () -> T?,
        writeMissing: Boolean = true,
    ) {
        val loaded =
            when (val read = keyValueStore.read(name)) {
                is KeyValueStore.Read.Missing -> {
                    synchronized(lock) { dirty = true }
                    onMissing()
                }
                is KeyValueStore.Read.Unreadable -> unreadable(read.cause, onUnreadable)
                is KeyValueStore.Read.Present -> decode(read.content) ?: unreadable(null, onUnreadable)
            }
        synchronized(lock) { current = loaded }
        isLoaded = true
        if (loaded == null) {
            synchronized(lock) { dirty = false }
        } else if (writeMissing) {
            persist()
        }
    }

    fun update(transform: (T?) -> T?) {
        synchronized(lock) {
            val next = transform(current)
            if (next != current) {
                current = next
                dirty = true
            }
        }
    }

    /** Returns `false` when a change could not be written. */
    fun persist(): Boolean {
        if (!isWritable) return true
        val snapshot =
            synchronized(lock) {
                if (!dirty) return true
                dirty = false
                current
            }
        val written =
            if (snapshot == null) {
                keyValueStore.delete(name)
            } else {
                keyValueStore.write(name, Json.write(mapOf(VERSION_KEY to VERSION) + codec.encode(snapshot)))
            }
        if (!written) {
            synchronized(lock) { dirty = true }
            log.error(Code.STORAGE_WRITE_FAILED) { "Could not write $name; the change is kept in memory and written later" }
        }
        return written
    }

    private fun decode(content: String): T? {
        val fields = Json.readObject(content) ?: return null
        if (fields.long(VERSION_KEY) != VERSION.toLong()) return null
        return codec.decode(fields)
    }

    private fun unreadable(
        cause: Exception?,
        fallback: () -> T?,
    ): T? {
        isWritable = false
        log.warn(Code.STORE_UNREADABLE, cause) {
            "$name exists but cannot be read; using values in memory for this process and leaving the file untouched"
        }
        return fallback()
    }

    companion object {
        const val VERSION_KEY = "v"

        /** The format version this SDK writes and reads; a file with another one is unreadable (Disk 7). */
        const val VERSION = 1
    }
}
