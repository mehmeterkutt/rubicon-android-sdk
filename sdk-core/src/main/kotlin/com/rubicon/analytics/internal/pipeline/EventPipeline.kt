package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.AppContext
import com.rubicon.analytics.BeforeSend
import com.rubicon.analytics.ContextField
import com.rubicon.analytics.DeviceContext
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.Event
import com.rubicon.analytics.EventContext
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.IdentifyEvent
import com.rubicon.analytics.LibraryContext
import com.rubicon.analytics.NetworkContext
import com.rubicon.analytics.OsContext
import com.rubicon.analytics.ScreenContext
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.SessionContext
import com.rubicon.analytics.TrackEvent
import com.rubicon.analytics.internal.BuildInfo
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.context.ContextProvider
import com.rubicon.analytics.internal.context.StaticValues
import com.rubicon.analytics.internal.event.EventWire
import com.rubicon.analytics.internal.identity.IdentityRef
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.session.SessionEvent
import com.rubicon.analytics.internal.session.SessionManager
import com.rubicon.analytics.internal.validation.PropertyNormalizer
import com.rubicon.analytics.internal.validation.PropertyProblem

/**
 * An event as captured on the calling thread (Public API 9, Context 2): what the developer passed,
 * normalized, with its id, time, identity and the time-dependent context of that moment.
 */
internal class CapturedEvent(
    val type: Type,
    /** The event or screen name; `null` for identify. */
    val name: String?,
    /** Normalized properties, or traits for identify. */
    val values: Map<String, Any?>,
    val source: EventSource,
    val messageId: String,
    val timestamp: Long,
    val identity: IdentityRef,
    val callTime: CallTimeValues,
    /** `SDK Diagnostics` reports on the SDK itself and never reaches a hook (Logging 5). */
    val skipsHooks: Boolean = false,
) {
    enum class Type { TRACK, SCREEN, IDENTIFY }

    override fun toString(): String = "CapturedEvent($type, $messageId)"
}

/** What became of one event in the pipeline. */
internal sealed interface PipelineResult {
    val messageId: String

    /** Ready to append; [line] is exactly what is stored and sent (Disk 3). */
    class Accepted(
        val event: Event,
        val line: String,
    ) : PipelineResult {
        override val messageId: String get() = event.messageId
    }

    class Dropped(
        override val messageId: String,
        val reason: DropReason,
    ) : PipelineResult
}

/**
 * The internal steps an event passes on the writer (Public API 4, Event Model 3): session, context,
 * super properties, the `beforeSend` chain, a second validation, and serialization with the 32 KB
 * check (Serialization 3). Session events the session step produces pass the same steps, right
 * before the event that caused them (Session 5).
 *
 * Hook rules (Public API 4, plan section 15): a hook that throws, or returns `null`, drops the event
 * (`before_send`). On an automatic event the name and the top-level keys must stay and `null` is not
 * allowed; a result that is not a `copy` of its input is never accepted. A breach drops the event
 * with `HOOK_VIOLATION`. With `debug` on, a throwing hook's exception and every breach are thrown.
 *
 * Runs on the writer only.
 */
internal class EventPipeline(
    private val hooks: List<BeforeSend>,
    private val disabledFields: Set<ContextField>,
    private val debug: Boolean,
    private val contextProvider: ContextProvider,
    private val sessions: SessionManager,
    private val newMessageId: () -> String,
    private val log: Log,
) {
    // Collected once, on first need, on the worker (Context 2).
    private val staticValues: StaticValues by lazy(LazyThreadSafetyMode.NONE) { contextProvider.staticValues(disabledFields) }

    /** The results for [captured], with the session events it caused first. */
    fun process(captured: CapturedEvent): List<PipelineResult> {
        val identity = checkNotNull(captured.identity.value) { "The identity of ${captured.messageId} was not resolved" }
        val outcome = sessions.onEvent(captured.timestamp)
        val results = ArrayList<PipelineResult>(outcome.before.size + 1)
        for (sessionEvent in outcome.before) results += process(sessionEvent(sessionEvent, captured.callTime, identity))
        results += process(captured, identity, outcome.session)
        return results
    }

    /**
     * Session events produced outside an event: on returning to the foreground, at process start, or
     * at a reset or user switch (Session 5). [current] is the identity now; an end caused by a person
     * change carries its own.
     */
    fun processSessionEvents(
        events: List<SessionEvent>,
        current: IdentitySnapshot,
        callTime: CallTimeValues,
    ): List<PipelineResult> = events.map { process(sessionEvent(it, callTime, current)) }

    private fun process(prepared: Prepared): PipelineResult = process(prepared.captured, prepared.identity, prepared.session)

    private fun process(
        captured: CapturedEvent,
        identity: IdentitySnapshot,
        session: SessionContext?,
    ): PipelineResult {
        val built = build(captured, identity, session)
        val hooked =
            if (captured.skipsHooks) {
                built
            } else {
                runHooks(built)
                    ?: return PipelineResult.Dropped(captured.messageId, DropReason.BEFORE_SEND)
            }
        val validated = validateAgain(hooked)
        val line = EventWire.line(validated)
        val size = line.toByteArray(Charsets.UTF_8).size
        if (size > MAX_EVENT_BYTES) {
            if (debug) throw IllegalArgumentException("An event is $size bytes, over the limit of $MAX_EVENT_BYTES")
            log.warn(Code.EVENT_OVERSIZE) { "An event of $size bytes is over the limit of $MAX_EVENT_BYTES; it was dropped" }
            return PipelineResult.Dropped(captured.messageId, DropReason.OVERSIZE)
        }
        return PipelineResult.Accepted(validated, line)
    }

    private fun build(
        captured: CapturedEvent,
        identity: IdentitySnapshot,
        session: SessionContext?,
    ): Event {
        val context = context(captured.callTime, session)
        return when (captured.type) {
            CapturedEvent.Type.IDENTIFY ->
                IdentifyEvent(
                    captured.source,
                    captured.messageId,
                    captured.timestamp,
                    identity.userId,
                    identity.deviceId,
                    captured.values,
                    context,
                )
            CapturedEvent.Type.TRACK ->
                TrackEvent(
                    captured.source,
                    captured.messageId,
                    captured.timestamp,
                    identity.userId,
                    identity.deviceId,
                    checkNotNull(captured.name),
                    withSuperProperties(captured.values, identity),
                    context,
                )
            CapturedEvent.Type.SCREEN ->
                ScreenEvent(
                    captured.source,
                    captured.messageId,
                    captured.timestamp,
                    identity.userId,
                    identity.deviceId,
                    checkNotNull(captured.name),
                    withSuperProperties(captured.values, identity),
                    context,
                )
        }
    }

    // The event's own key wins; above 256 the super properties go first (Context 5, Serialization 3).
    private fun withSuperProperties(
        own: Map<String, Any?>,
        identity: IdentitySnapshot,
    ): Map<String, Any?> {
        if (identity.superProperties.isEmpty()) return own
        val merged = PropertyNormalizer.mergeSuperProperties(own, identity.superProperties)
        PropertyProblem.report(merged.problems, debug, log)
        return merged.values
    }

    // A disabled field is left out here too, whatever the provider returned (Context 6).
    private fun context(
        callTime: CallTimeValues,
        session: SessionContext?,
    ): EventContext {
        val s = staticValues
        return EventContext(
            app =
                AppContext(
                    on(ContextField.APP_NAME, s.appName),
                    on(ContextField.APP_VERSION, s.appVersion),
                    on(ContextField.APP_BUILD, s.appBuild),
                    on(ContextField.APP_NAMESPACE, s.appNamespace),
                ),
            device =
                DeviceContext(
                    on(ContextField.DEVICE_MANUFACTURER, s.deviceManufacturer),
                    on(ContextField.DEVICE_MODEL, s.deviceModel),
                    on(ContextField.DEVICE_TYPE, s.deviceType),
                    on(ContextField.DEVICE_IS_EMULATOR, s.deviceIsEmulator),
                ),
            os =
                OsContext(
                    on(ContextField.OS_NAME, s.osName),
                    on(ContextField.OS_VERSION, s.osVersion),
                    on(ContextField.OS_API_LEVEL, s.osApiLevel),
                ),
            screen =
                ScreenContext(
                    on(ContextField.SCREEN_WIDTH, callTime.screenWidth),
                    on(ContextField.SCREEN_HEIGHT, callTime.screenHeight),
                    on(ContextField.SCREEN_DENSITY, callTime.screenDensity),
                ),
            network =
                NetworkContext(
                    on(ContextField.NETWORK_TYPE, callTime.networkType),
                    on(ContextField.NETWORK_CARRIER, callTime.networkCarrier),
                ),
            locale = on(ContextField.LOCALE, callTime.locale),
            timezone = on(ContextField.TIMEZONE, callTime.timezone),
            session = session,
            library = LibraryContext(LIBRARY_NAME, BuildInfo.VERSION),
        )
    }

    private fun <T> on(
        field: ContextField,
        value: T,
    ): T? = if (field in disabledFields) null else value

    private fun runHooks(event: Event): Event? {
        var current = event
        for (hook in hooks) {
            val result =
                try {
                    hook.process(current)
                } catch (e: Exception) {
                    if (debug) throw e
                    log.error(Code.HOOK_THREW, e) { "A beforeSend hook threw ${e.javaClass.name}; the event was dropped" }
                    return null
                }
            if (result == null) {
                if (current.source == EventSource.AUTO) return violation("returned null for an automatic event")
                log.debug { "A beforeSend hook dropped ${current.messageId}" }
                return null
            }
            val problem = problemOf(current, result)
            if (problem != null) return violation(problem)
            current = result
        }
        return current
    }

    private fun violation(problem: String): Event? {
        if (debug) throw IllegalStateException("beforeSend: a hook $problem")
        log.error(Code.HOOK_VIOLATION) { "A beforeSend hook $problem; the event was dropped" }
        return null
    }

    /** Why [output] may not replace [input], or `null` when it may (Public API 4, Event Model 6). */
    private fun problemOf(
        input: Event,
        output: Event,
    ): String? {
        val sameIdentity =
            output.javaClass == input.javaClass &&
                output.source == input.source &&
                output.messageId == input.messageId &&
                output.timestamp == input.timestamp &&
                output.userId == input.userId &&
                output.deviceId == input.deviceId &&
                output.context === input.context
        if (!sameIdentity) return "returned an event that is not a copy of its input"
        val name = nameOf(output)
        if (name != null && PropertyNormalizer.checkName(name) != null) return "returned an invalid event name"
        if (input.source == EventSource.AUTO) {
            if (name != nameOf(input)) return "renamed an automatic event"
            if (valuesOf(output).keys != valuesOf(input).keys) return "added or removed top-level keys of an automatic event"
        }
        return null
    }

    // The values a hook returned are normalized like a public call's (Event Model 3).
    private fun validateAgain(event: Event): Event {
        val normalized = PropertyNormalizer.normalize(valuesOf(event))
        PropertyProblem.report(normalized.problems, debug, log)
        if (normalized.problems.isEmpty()) return event
        return when (event) {
            is TrackEvent -> event.copy(properties = normalized.values)
            is ScreenEvent -> event.copy(properties = normalized.values)
            is IdentifyEvent -> event.copy(traits = normalized.values)
        }
    }

    private fun sessionEvent(
        event: SessionEvent,
        callTime: CallTimeValues,
        current: IdentitySnapshot,
    ): Prepared =
        when (event) {
            is SessionEvent.Started ->
                Prepared(
                    automatic(SESSION_STARTED, emptyMap(), event.session.startedAt, callTime),
                    current,
                    SessionContext(event.session.id, event.session.startedAt),
                )
            is SessionEvent.Ended ->
                Prepared(
                    automatic(SESSION_ENDED, mapOf(DURATION_MS to event.durationMs), event.session.lastActivity, callTime),
                    event.identity ?: current,
                    SessionContext(event.session.id, event.session.startedAt),
                )
        }

    private fun automatic(
        name: String,
        properties: Map<String, Any?>,
        timestamp: Long,
        callTime: CallTimeValues,
    ) = CapturedEvent(CapturedEvent.Type.TRACK, name, properties, EventSource.AUTO, newMessageId(), timestamp, IdentityRef(), callTime)

    private class Prepared(
        val captured: CapturedEvent,
        val identity: IdentitySnapshot,
        val session: SessionContext?,
    )

    companion object {
        /** Serialization 3: one serialized line, UTF-8. */
        const val MAX_EVENT_BYTES: Int = 32 * 1024

        const val LIBRARY_NAME: String = "rubicon-android"
        const val SESSION_STARTED: String = "Session Started"
        const val SESSION_ENDED: String = "Session Ended"
        const val DURATION_MS: String = "duration_ms"

        private fun nameOf(event: Event): String? =
            when (event) {
                is TrackEvent -> event.name
                is ScreenEvent -> event.name
                is IdentifyEvent -> null
            }

        private fun valuesOf(event: Event): Map<String, Any?> =
            when (event) {
                is TrackEvent -> event.properties
                is ScreenEvent -> event.properties
                is IdentifyEvent -> event.traits
            }
    }
}
