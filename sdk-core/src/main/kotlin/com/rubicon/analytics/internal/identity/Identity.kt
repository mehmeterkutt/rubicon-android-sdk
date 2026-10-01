package com.rubicon.analytics.internal.identity

import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonState
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.validation.PropertyNormalizer
import com.rubicon.analytics.internal.validation.PropertyProblem

/** Who an event belongs to, taken when the event is tracked (Public API 9). */
internal class IdentitySnapshot(
    /** `null` while anonymous. */
    val userId: String?,
    val deviceId: String,
    val superProperties: Map<String, Any?>,
) {
    override fun toString(): String = "IdentitySnapshot(${superProperties.size} super properties)"
}

/**
 * An identity snapshot that may not exist yet: taken before the stores were loaded, it is filled when
 * the writer loads them, in call order, before the event it belongs to is processed.
 */
internal class IdentityRef {
    @Volatile
    var value: IdentitySnapshot? = null
        internal set
}

/** Whether an `identify` call produces an identify event; decided now, or at load. */
internal class IdentifyTicket {
    sealed interface Decision {
        object NoEvent : Decision

        class SendEvent(
            /** Normalized; sent with this event only and never stored (Identity 3). */
            val traits: Map<String, Any?>,
            val identity: IdentitySnapshot,
        ) : Decision
    }

    @Volatile
    var decision: Decision? = null
        internal set
}

/**
 * The identity of the instance (Identity 1 to 5; Context 5): the user id and super properties in
 * `PersonStore`, the device id in `DeviceStore`.
 *
 * - Every call changes memory at once on the calling thread and asks the writer to persist
 *   ([requestPersist]), so a `track` after `identify` carries the new id (Public API 9).
 * - Before [load], when the writer has not read the stores yet, calls are kept in call order and
 *   applied at [load]; snapshots taken meanwhile are filled at the same point (plan section 15).
 * - Validation runs on the calling thread before anything is kept: a blank or too long user id and
 *   an unsupported super property throw in debug and are ignored with a WARN in release.
 *
 * @param eventsAllowed `false` while opted out: `identify` then updates local state without an
 *   event (Privacy 1).
 * @param onPersonCleared called with the identity before `reset()` or a user switch cleared the
 *   person's state, for the session to end with the old user (Session 6, step 6.2).
 */
internal class Identity(
    private val personStore: PersonStore,
    private val deviceStore: DeviceStore,
    private val log: Log,
    private val debug: Boolean,
    private val eventsAllowed: () -> Boolean,
    private val requestPersist: () -> Unit,
    private val onPersonCleared: (IdentitySnapshot) -> Unit = {},
) {
    private val lock = Any()

    // Guarded by lock.
    private var loaded = false
    private val pending = ArrayList<() -> Unit>()

    /**
     * Applies the calls made before the stores were loaded, in order; on the writer, after
     * `PersonStore.load` and `DeviceStore.load`.
     */
    fun load() {
        synchronized(lock) {
            pending.forEach { it() }
            pending.clear()
            loaded = true
        }
    }

    /** The installation id; reads `device.json` on this thread only if the writer has not yet. */
    val deviceId: String
        get() {
            deviceStore.readEarly()
            return deviceStore.state.deviceId
        }

    /** The identity for an event tracked now. */
    fun snapshot(): IdentityRef {
        val ref = IdentityRef()
        whenLoaded { ref.value = current() }
        return ref
    }

    /**
     * Applies the rules of Identity 3 and 4. Returns `null` when [userId] is invalid and the call was
     * ignored (release); throws in debug.
     */
    fun identify(
        userId: String,
        traits: Map<String, Any?>,
    ): IdentifyTicket? {
        if (userId.isBlank() || userId.length > MAX_USER_ID_LENGTH) {
            val reason = if (userId.isBlank()) "blank" else "longer than $MAX_USER_ID_LENGTH characters"
            if (debug) throw IllegalArgumentException("identify: the user id is $reason")
            log.warn(Code.INVALID_USER_ID) { "identify was called with a user id that is $reason; the call is ignored" }
            return null
        }
        val normalized = PropertyNormalizer.normalize(traits)
        PropertyProblem.report(normalized.problems, debug, log)
        val ticket = IdentifyTicket()
        whenLoaded { ticket.decision = applyIdentify(userId, normalized.values) }
        return ticket
    }

    /** Back to anonymous: clears the person's state, never the device's (Identity 5). No event. */
    fun reset() {
        whenLoaded {
            clearPerson()
            persist()
        }
    }

    /**
     * Adds a super property, validated now with the property rules (Context 5); `null` is a value.
     * Returns `false` when [value] or [key] was dropped (release) and nothing changed.
     */
    fun register(
        key: String,
        value: Any?,
    ): Boolean {
        val normalized = PropertyNormalizer.normalize(mapOf(key to value))
        PropertyProblem.report(normalized.problems, debug, log)
        if (key !in normalized.values) return false
        val stored = normalized.values[key]
        whenLoaded {
            personStore.update { it.copy(superProperties = it.superProperties + (key to stored)) }
            persist()
        }
        return true
    }

    fun unregister(key: String) {
        whenLoaded {
            personStore.update { if (key in it.superProperties) it.copy(superProperties = it.superProperties - key) else it }
            persist()
        }
    }

    private fun applyIdentify(
        userId: String,
        traits: Map<String, Any?>,
    ): IdentifyTicket.Decision {
        val known = personStore.state.userId
        val send =
            when {
                known == null -> {
                    personStore.update { it.copy(userId = userId) }
                    true
                }
                known == userId -> traits.isNotEmpty()
                else -> {
                    clearPerson()
                    // Never the ids themselves (Logging 4a).
                    log.info { "A different user was identified; the previous person's state was reset" }
                    personStore.update { it.copy(userId = userId) }
                    true
                }
            }
        persist()
        return if (send && eventsAllowed()) IdentifyTicket.Decision.SendEvent(traits, current()) else IdentifyTicket.Decision.NoEvent
    }

    private fun clearPerson() {
        val previous = current()
        personStore.update { PersonState.ANONYMOUS }
        onPersonCleared(previous)
    }

    private fun current(): IdentitySnapshot {
        val person = personStore.state
        return IdentitySnapshot(person.userId, deviceStore.state.deviceId, person.superProperties)
    }

    private fun persist() {
        requestPersist()
    }

    // Runs now when the stores are loaded, else at load, in call order.
    private inline fun whenLoaded(crossinline block: () -> Unit) {
        synchronized(lock) {
            if (loaded) block() else pending += { block() }
        }
    }

    companion object {
        /** Identity 3: 1 to 255 characters, never trimmed. */
        const val MAX_USER_ID_LENGTH: Int = 255
    }
}
