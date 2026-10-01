package com.rubicon.analytics.internal.store

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.internal.event.wireName
import com.rubicon.analytics.internal.json.JsonFields
import com.rubicon.analytics.internal.log.IssueCounter
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.internal.store.StoreFields.corrupt
import com.rubicon.analytics.internal.store.StoreFields.decodeOrNull
import com.rubicon.analytics.internal.store.StoreFields.intMap
import com.rubicon.analytics.internal.store.StoreFields.optionalFields
import com.rubicon.analytics.internal.store.StoreFields.optionalInt
import com.rubicon.analytics.internal.store.StoreFields.optionalLong
import com.rubicon.analytics.internal.store.StoreFields.optionalMap
import com.rubicon.analytics.internal.store.StoreFields.optionalString
import com.rubicon.analytics.internal.store.StoreFields.requiredString

/**
 * What belongs to the device and survives `reset()` (Identity 5). `clearLocalData()` renews
 * [deviceId] and clears [retry] and [diagnostics], keeping the rest (Privacy 3).
 */
internal data class DeviceState(
    val deviceId: String,
    /** The user's stored choice; `null` while there is none (Privacy 2). */
    val consent: StoredConsent?,
    /** The app version and build last seen, for install and update events (Lifecycle 4). */
    val lastVersion: String?,
    val lastBuild: String?,
    val retry: RetryState,
    val diagnostics: DiagnosticsState,
) {
    companion object {
        fun initial(deviceId: String): DeviceState = DeviceState(deviceId, null, null, null, RetryState.NONE, DiagnosticsState.EMPTY)
    }
}

/** The stored consent choice: `granted` or `denied` (Privacy 2). */
internal enum class StoredConsent(
    val wireName: String,
) {
    GRANTED("granted"),
    DENIED("denied"),
}

/** The upload pipeline's wait, restored at startup (Retry 5). */
internal data class RetryState(
    /** Wall-clock epoch milliseconds before which no upload starts. */
    val pausedUntil: Long?,
    val pauseKind: PauseKind?,
    /** Position in the backoff table (Retry 2). */
    val step: Int,
    /** The kind of the last failure, such as `timeout`. */
    val lastFailure: String?,
    /** Failed attempts per segment name, for the poison batch rule (Retry 3). */
    val batches: Map<String, Int>,
) {
    companion object {
        val NONE = RetryState(null, null, 0, null, emptyMap())
    }
}

/** Groups B, C and E of Retry 1: whether the network may end the wait and how long it may be. */
internal enum class PauseKind(
    val wireName: String,
) {
    TRANSIENT("transient"),
    THROTTLE("throttle"),
    CONFIG("config"),
}

/** The counters of `SDK Diagnostics`, kept across restarts (Logging 5). */
internal data class DiagnosticsState(
    /** When counting began, epoch milliseconds; `null` while nothing is counted. */
    val periodStart: Long?,
    /** When the last `SDK Diagnostics` event was written, epoch milliseconds. */
    val lastSentAt: Long?,
    /** Drop counts by reason wire name, such as `queue_full`. */
    val dropped: Map<String, Int>,
    /** Counts by issue code, such as `HOST_BLOCKED`. */
    val issues: Map<String, Int>,
) {
    companion object {
        val EMPTY = DiagnosticsState(null, null, emptyMap(), emptyMap())
    }
}

/** Counts to add to a [DiagnosticsState]. */
internal data class DiagnosticsCount(
    val dropped: Map<String, Int> = emptyMap(),
    val issues: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = dropped.isEmpty() && issues.isEmpty()

    operator fun plus(other: DiagnosticsCount): DiagnosticsCount = DiagnosticsCount(dropped.sum(other.dropped), issues.sum(other.issues))

    /** [state] with these counts added; counting begins at [now] if it had not begun. */
    fun addTo(
        state: DiagnosticsState,
        now: Long,
    ): DiagnosticsState =
        state.copy(
            periodStart = state.periodStart ?: now,
            dropped = state.dropped.sum(dropped),
            issues = state.issues.sum(issues),
        )

    private companion object {
        fun Map<String, Int>.sum(other: Map<String, Int>): Map<String, Int> {
            if (other.isEmpty()) return this
            val result = LinkedHashMap(this)
            // Saturates instead of wrapping around on a device that counts for years.
            for ((key, n) in other) result[key] = ((result[key] ?: 0).toLong() + n).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return result
        }
    }
}

/** The last valid remote configuration and its ETag (Configuration 1); its values are read in 5.3. */
internal data class RemoteConfigCache(
    val etag: String?,
    val values: Map<String, Any?>,
)

/**
 * `device.json` and `remote-config.json` (Disk 7).
 *
 * - `device.json` missing: a new [DeviceState.deviceId] from [newDeviceId], written at once.
 *   Unreadable: a temporary id in memory for this process, the file left untouched (Identity 1).
 * - `remote-config.json` missing or unreadable: no cache; an unreadable one is never overwritten.
 *
 * The drop and issue counters of `SDK Diagnostics` live here (Logging 5). Counts that arrive before
 * [load], such as the warning about an unreadable `device.json` itself, are kept and added once it
 * is loaded. `DeviceStoreTest` pins the exact keys of both files.
 */
internal class DeviceStore(
    keyValueStore: KeyValueStore,
    log: Log,
    private val clock: Clock,
    private val newDeviceId: () -> String,
) {
    private val device = StoreFile(FILE_NAME, keyValueStore, log, DeviceCodec)
    private val remote = StoreFile(REMOTE_CONFIG_FILE_NAME, keyValueStore, log, RemoteConfigCodec)

    private val countLock = Any()

    // Counts made before load; guarded by countLock.
    private var early = DiagnosticsCount()

    /** Counts every WARN and ERROR by code; the `Log` of the instance is built with it. */
    val issueCounter: IssueCounter = IssueCounter { code -> count(DiagnosticsCount(issues = mapOf(code.name to 1))) }

    private val loadLock = Any()

    /**
     * Reads both files and writes a new `device.json` if it was missing; the writer's first task
     * (Privacy 2). When [readEarly] already read them, only that write is left.
     */
    fun load() {
        read(writeMissing = true)
        persist()
    }

    /**
     * Reads the files on the calling thread if the writer has not yet, for the two getters that must
     * answer at once, `deviceId` and `isOptedOut()` (Public API 9). Never writes: a new id is written
     * by the writer's [load]. The files are read once, whoever comes first.
     */
    fun readEarly() {
        if (!device.isLoaded) read(writeMissing = false)
    }

    private fun read(writeMissing: Boolean) {
        synchronized(loadLock) {
            if (device.isLoaded) return
            device.load(
                onMissing = { DeviceState.initial(newDeviceId()) },
                onUnreadable = { DeviceState.initial(newDeviceId()) },
                writeMissing = writeMissing,
            )
            remote.load(onMissing = { null }, onUnreadable = { null })
        }
        synchronized(countLock) {
            if (!early.isEmpty) {
                device.update {
                    it?.let { state ->
                        state.copy(diagnostics = early.addTo(state.diagnostics, clock.currentTimeMillis()))
                    }
                }
            }
            early = DiagnosticsCount()
        }
    }

    /** Counts [count] events dropped for [reason] (Queue 5, Logging 5). */
    fun countDropped(
        reason: DropReason,
        count: Int = 1,
    ) {
        if (count > 0) count(DiagnosticsCount(dropped = mapOf(reason.wireName to count)))
    }

    private fun count(delta: DiagnosticsCount) {
        synchronized(countLock) {
            if (device.isLoaded) {
                device.update { it?.let { state -> state.copy(diagnostics = delta.addTo(state.diagnostics, clock.currentTimeMillis())) } }
            } else {
                early = early + delta
            }
        }
    }

    val state: DeviceState
        get() = checkNotNull(device.value) { "DeviceStore used before load" }

    /** `false` when `device.json` exists but could not be read; its id is temporary. */
    val isDeviceFileWritable: Boolean
        get() = device.isWritable

    fun update(transform: (DeviceState) -> DeviceState) {
        device.update { transform(it ?: state) }
    }

    val remoteConfig: RemoteConfigCache?
        get() = remote.value

    /** Replaces the cache; `null` deletes it. */
    fun updateRemoteConfig(cache: RemoteConfigCache?) {
        remote.update { cache }
    }

    /** Writes whatever changed; on the writer only. Returns `false` when a write failed. */
    fun persist(): Boolean = device.persist() and remote.persist()

    private object DeviceCodec : StoreCodec<DeviceState> {
        override fun encode(value: DeviceState): Map<String, Any?> =
            mapOf(
                DEVICE_ID to value.deviceId,
                CONSENT to value.consent?.wireName,
                LAST_VERSION to value.lastVersion,
                LAST_BUILD to value.lastBuild,
                RETRY to
                    mapOf(
                        PAUSED_UNTIL to value.retry.pausedUntil,
                        PAUSE_KIND to value.retry.pauseKind?.wireName,
                        STEP to value.retry.step,
                        LAST_FAILURE to value.retry.lastFailure,
                        BATCHES to value.retry.batches,
                    ),
                DIAGNOSTICS to
                    mapOf(
                        PERIOD_START to value.diagnostics.periodStart,
                        LAST_SENT_AT to value.diagnostics.lastSentAt,
                        DROPPED to value.diagnostics.dropped,
                        ISSUES to value.diagnostics.issues,
                    ),
            )

        override fun decode(fields: JsonFields): DeviceState? =
            decodeOrNull {
                val retry = fields.optionalFields(RETRY)
                val diagnostics = fields.optionalFields(DIAGNOSTICS)
                DeviceState(
                    deviceId = fields.requiredString(DEVICE_ID).ifBlank { corrupt() },
                    consent =
                        fields.optionalString(CONSENT)?.let { name ->
                            StoredConsent.entries.find { it.wireName == name } ?: corrupt()
                        },
                    lastVersion = fields.optionalString(LAST_VERSION),
                    lastBuild = fields.optionalString(LAST_BUILD),
                    retry =
                        retry?.let {
                            RetryState(
                                pausedUntil = it.optionalLong(PAUSED_UNTIL),
                                pauseKind =
                                    it.optionalString(PAUSE_KIND)?.let { name ->
                                        PauseKind.entries.find { k -> k.wireName == name }
                                            ?: corrupt()
                                    },
                                step = it.optionalInt(STEP) ?: 0,
                                lastFailure = it.optionalString(LAST_FAILURE),
                                batches = it.intMap(BATCHES),
                            )
                        } ?: RetryState.NONE,
                    diagnostics =
                        diagnostics?.let {
                            DiagnosticsState(
                                periodStart = it.optionalLong(PERIOD_START),
                                lastSentAt = it.optionalLong(LAST_SENT_AT),
                                dropped = it.intMap(DROPPED),
                                issues = it.intMap(ISSUES),
                            )
                        } ?: DiagnosticsState.EMPTY,
                )
            }
    }

    private object RemoteConfigCodec : StoreCodec<RemoteConfigCache> {
        override fun encode(value: RemoteConfigCache): Map<String, Any?> = mapOf(ETAG to value.etag, VALUES to value.values)

        override fun decode(fields: JsonFields): RemoteConfigCache? =
            decodeOrNull { RemoteConfigCache(fields.optionalString(ETAG), fields.optionalMap(VALUES).orEmpty()) }
    }

    companion object {
        const val FILE_NAME = "device.json"
        const val REMOTE_CONFIG_FILE_NAME = "remote-config.json"

        const val DEVICE_ID = "device_id"
        const val CONSENT = "consent"
        const val LAST_VERSION = "last_version"
        const val LAST_BUILD = "last_build"
        const val RETRY = "retry"
        const val PAUSED_UNTIL = "paused_until"
        const val PAUSE_KIND = "pause_kind"
        const val STEP = "step"
        const val LAST_FAILURE = "last_failure"
        const val BATCHES = "batches"
        const val DIAGNOSTICS = "diagnostics"
        const val PERIOD_START = "period_start"
        const val LAST_SENT_AT = "last_sent_at"
        const val DROPPED = "dropped"
        const val ISSUES = "issues"
        const val ETAG = "etag"
        const val VALUES = "values"
    }
}
