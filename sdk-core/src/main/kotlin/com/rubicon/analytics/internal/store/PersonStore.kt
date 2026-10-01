package com.rubicon.analytics.internal.store

import com.rubicon.analytics.internal.json.JsonFields
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.internal.store.StoreFields.decodeOrNull
import com.rubicon.analytics.internal.store.StoreFields.optionalFields
import com.rubicon.analytics.internal.store.StoreFields.optionalMap
import com.rubicon.analytics.internal.store.StoreFields.optionalString
import com.rubicon.analytics.internal.store.StoreFields.requiredLong
import com.rubicon.analytics.internal.store.StoreFields.requiredString

/**
 * What belongs to the person using the app, cleared by `reset()` and `clearLocalData()` (Identity 5).
 * A new persisted value goes here unless it should carry over when another person logs in on this
 * device; then it belongs to [DeviceStore]. `PersonStoreTest` pins the exact keys.
 */
internal data class PersonState(
    /** `null` while anonymous. */
    val userId: String?,
    /** Normalized values, as `register` stored them (Context 5). */
    val superProperties: Map<String, Any?>,
    val session: SessionState?,
) {
    companion object {
        val ANONYMOUS = PersonState(userId = null, superProperties = emptyMap(), session = null)
    }
}

/** The open session (Session 6); times are wall-clock epoch milliseconds. */
internal data class SessionState(
    val id: String,
    val startedAt: Long,
    val lastActivity: Long,
)

/**
 * `person.json` (Disk 7): `{"v":1,"user_id":..,"super_properties":{..},"session":{"id","started_at",
 * "last_activity"}}`. Missing: anonymous, written at once. Unreadable: anonymous in memory, the file
 * left untouched (Identity 1).
 */
internal class PersonStore(
    keyValueStore: KeyValueStore,
    log: Log,
) {
    private val file = StoreFile(FILE_NAME, keyValueStore, log, Codec)

    /** Reads the file; on the writer, before anything else uses the store. */
    fun load() {
        file.load(onMissing = { PersonState.ANONYMOUS }, onUnreadable = { PersonState.ANONYMOUS })
    }

    val state: PersonState
        get() = checkNotNull(file.value) { "PersonStore used before load" }

    /** Changes the state in memory at once; [persist] writes it later. */
    fun update(transform: (PersonState) -> PersonState) {
        file.update { transform(it ?: PersonState.ANONYMOUS) }
    }

    /** Writes the latest state if it changed; on the writer only. */
    fun persist(): Boolean = file.persist()

    private object Codec : StoreCodec<PersonState> {
        override fun encode(value: PersonState): Map<String, Any?> =
            mapOf(
                USER_ID to value.userId,
                SUPER_PROPERTIES to value.superProperties,
                SESSION to
                    value.session?.let {
                        mapOf(SESSION_ID to it.id, STARTED_AT to it.startedAt, LAST_ACTIVITY to it.lastActivity)
                    },
            )

        override fun decode(fields: JsonFields): PersonState? =
            decodeOrNull {
                PersonState(
                    userId = fields.optionalString(USER_ID),
                    superProperties = fields.optionalMap(SUPER_PROPERTIES).orEmpty(),
                    session =
                        fields.optionalFields(SESSION)?.let {
                            SessionState(
                                id = it.requiredString(SESSION_ID),
                                startedAt = it.requiredLong(STARTED_AT),
                                lastActivity = it.requiredLong(LAST_ACTIVITY),
                            )
                        },
                )
            }
    }

    companion object {
        const val FILE_NAME = "person.json"
        const val USER_ID = "user_id"
        const val SUPER_PROPERTIES = "super_properties"
        const val SESSION = "session"
        const val SESSION_ID = "id"
        const val STARTED_AT = "started_at"
        const val LAST_ACTIVITY = "last_activity"
    }
}
