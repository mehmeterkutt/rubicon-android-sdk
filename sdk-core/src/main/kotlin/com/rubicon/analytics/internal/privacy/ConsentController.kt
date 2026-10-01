package com.rubicon.analytics.internal.privacy

import com.rubicon.analytics.Consent
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.StoredConsent

/** Where collection stands (Privacy 1, 4). */
internal enum class ConsentState {
    /** Collected and sent. */
    GRANTED,

    /** Collected and written, not sent until the user decides. */
    PENDING,

    /** Opted out: nothing new; what was queued with consent is still sent. */
    DENIED,
}

/**
 * The user's consent (Privacy 1, 2, 4).
 *
 * - The state starts from `privacy.defaultConsent`; with `privacy.persistConsent` a stored choice in
 *   `DeviceStore` wins. Without it the stored value is neither read nor written.
 * - [optOut] and [optIn] apply in memory at once, from any thread, and tell the writer through
 *   [onChange], which ends the session, deletes waiting events or flushes (step 6.3b). No event is
 *   produced; the change is logged at INFO.
 * - Before the stored choice is known, calls are kept in call order and applied when it is: by the
 *   writer's [load], or by [isOptedOut], which may read `device.json` once on the calling thread
 *   (Public API 9, Privacy 2).
 */
internal class ConsentController(
    private val defaultConsent: Consent,
    private val persistConsent: Boolean,
    private val deviceStore: DeviceStore,
    private val log: Log,
    private val onChange: (from: ConsentState, to: ConsentState) -> Unit,
) {
    private val lock = Any()

    // Guarded by lock.
    private var loaded = false
    private val pending = ArrayList<ConsentState>()

    @Volatile
    private var current = ConsentState.GRANTED

    /** The state now; read by the writer and the uploader after load. */
    val state: ConsentState
        get() = current

    /**
     * The uploader's consent gate (step 6.4): closed only while pending. After an opt-out, events
     * collected with consent are still sent (Privacy 1); those of a pending state were deleted.
     */
    val allowsUploads: Boolean
        get() = current != ConsentState.PENDING

    /** On the writer, after `DeviceStore.load`. */
    fun load() {
        synchronized(lock) {
            if (loaded) return
            current = initial()
            loaded = true
            val changes = pending.toList()
            pending.clear()
            changes.forEach { apply(it) }
        }
    }

    /** `true` while opted out or pending: nothing is being sent now (Privacy 4). */
    fun isOptedOut(): Boolean {
        if (!synchronized(lock) { loaded }) {
            deviceStore.readEarly()
            load()
        }
        return current != ConsentState.GRANTED
    }

    fun optOut() {
        change(ConsentState.DENIED)
    }

    fun optIn() {
        change(ConsentState.GRANTED)
    }

    private fun change(to: ConsentState) {
        synchronized(lock) {
            if (loaded) apply(to) else pending += to
        }
    }

    private fun apply(to: ConsentState) {
        val from = current
        if (persistConsent) {
            val stored = if (to == ConsentState.GRANTED) StoredConsent.GRANTED else StoredConsent.DENIED
            deviceStore.update { it.copy(consent = stored) }
        }
        if (from == to) return
        current = to
        val message =
            if (to ==
                ConsentState.DENIED
            ) {
                "The user opted out; nothing new is collected"
            } else {
                "The user opted in; collection resumes"
            }
        log.info { message }
        onChange(from, to)
    }

    private fun initial(): ConsentState {
        val stored = if (persistConsent) deviceStore.state.consent else null
        return when (stored) {
            StoredConsent.GRANTED -> ConsentState.GRANTED
            StoredConsent.DENIED -> ConsentState.DENIED
            null -> if (defaultConsent == Consent.PENDING) ConsentState.PENDING else ConsentState.GRANTED
        }
    }
}
