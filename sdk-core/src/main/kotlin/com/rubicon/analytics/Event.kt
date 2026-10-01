package com.rubicon.analytics

/**
 * An event as `beforeSend` hooks and the `eventObserver` see it (Event Model 2, 6). Events are
 * immutable and only the SDK creates them. A hook may change only the name and the properties or
 * traits, through `copy`; identity, time, origin and [context] stay as measured. For an automatic
 * event ([source] `AUTO`) only property values may change: see [BeforeSend].
 *
 * Subtypes may be added in minor versions: a `when` over events should keep an `else` branch.
 *
 * ```
 * BeforeSend { event ->
 *     when (event) {
 *         is TrackEvent -> event.copy(properties = event.properties - "email")
 *         else -> event
 *     }
 * }
 * ```
 */
public sealed class Event {
    /** Whether the app or the SDK created the event. */
    public abstract val source: EventSource

    /** A UUIDv7, unique per event and never changed, also across retries. */
    public abstract val messageId: String

    /** When the event was created, in epoch milliseconds. */
    public abstract val timestamp: Long

    /** The identified user, or `null` while anonymous. */
    public abstract val userId: String?

    /** The installation's id. */
    public abstract val deviceId: String

    public abstract val context: EventContext
}

/** An action, sent by `track` or captured automatically, such as `Application Opened`. */
public class TrackEvent internal constructor(
    override val source: EventSource,
    override val messageId: String,
    override val timestamp: Long,
    override val userId: String?,
    override val deviceId: String,
    public val name: String,
    public val properties: Map<String, Any?>,
    override val context: EventContext,
) : Event() {
    /**
     * A copy with a new [name] or new [properties]; everything else stays. The result is validated
     * again before it is stored.
     */
    @JvmOverloads
    public fun copy(
        name: String = this.name,
        properties: Map<String, Any?> = this.properties,
    ): TrackEvent = TrackEvent(source, messageId, timestamp, userId, deviceId, name, properties, context)

    override fun toString(): String = "TrackEvent(name=$name, messageId=$messageId)"
}

/** A screen view, sent by `screen` or captured automatically for an activity. */
public class ScreenEvent internal constructor(
    override val source: EventSource,
    override val messageId: String,
    override val timestamp: Long,
    override val userId: String?,
    override val deviceId: String,
    public val name: String,
    public val properties: Map<String, Any?>,
    override val context: EventContext,
) : Event() {
    /**
     * A copy with a new [name] or new [properties]; everything else stays. The result is validated
     * again before it is stored.
     */
    @JvmOverloads
    public fun copy(
        name: String = this.name,
        properties: Map<String, Any?> = this.properties,
    ): ScreenEvent = ScreenEvent(source, messageId, timestamp, userId, deviceId, name, properties, context)

    override fun toString(): String = "ScreenEvent(name=$name, messageId=$messageId)"
}

/** Who the user is, sent by `identify`. [traits] are sent with this event only and never stored. */
public class IdentifyEvent internal constructor(
    override val source: EventSource,
    override val messageId: String,
    override val timestamp: Long,
    override val userId: String?,
    override val deviceId: String,
    public val traits: Map<String, Any?>,
    override val context: EventContext,
) : Event() {
    /** A copy with new [traits]; everything else stays. The result is validated again before it is stored. */
    @JvmOverloads
    public fun copy(traits: Map<String, Any?> = this.traits): IdentifyEvent =
        IdentifyEvent(source, messageId, timestamp, userId, deviceId, traits, context)

    override fun toString(): String = "IdentifyEvent(messageId=$messageId)"
}

/**
 * A hook that sees each event before it is stored, set with `AnalyticsConfig.beforeSend`
 * (Public API 4). It sees the app's events and the automatic ones, but never `SDK Diagnostics`.
 *
 * - For the app's own events ([EventSource.APP]) return the event, a `copy` of it with any name and
 *   properties or traits, or `null` to drop it.
 * - For automatic events ([EventSource.AUTO]) only the values of properties may change, for example
 *   to mask a token: the name stays, the same top-level keys come out as went in, and `null` is not
 *   allowed. A value may become `null` or another type, and nested values are free. To stop an
 *   automatic event, switch its type off in `autocapture.enabled`; to name automatic screens, use
 *   `@ScreenName`.
 *
 * A hook that throws, or returns what these rules do not allow, drops the event; with `debug` on,
 * the SDK throws.
 *
 * ```
 * val inviteCode = Regex("/invite/[^/]+")
 * BeforeSend { e ->
 *     if (e is TrackEvent && e.source == EventSource.AUTO) {
 *         // Same keys, new values: masks the code in a deep link or a captured call.
 *         e.copy(properties = e.properties.mapValues { (_, v) -> if (v is String) v.replace(inviteCode, "/invite/:code") else v })
 *     } else {
 *         e
 *     }
 * }
 * ```
 */
public fun interface BeforeSend {
    public fun process(event: Event): Event?
}

/**
 * Hears what became of each event, set with `logging.eventObserver` (Logging 4c): for QA screens in
 * debug builds and for tests. Called on the SDK's worker thread; keep it fast and do not call the SDK
 * from it. If it throws, it is switched off.
 */
public fun interface EventObserver {
    public fun onUpdate(update: EventUpdate)
}

/** What became of events (Logging 4c). Updates may be added in minor versions. */
public sealed class EventUpdate {
    /** The event passed the hooks and validation and was stored. */
    public class Queued internal constructor(
        public val event: Event,
    ) : EventUpdate() {
        override fun toString(): String = "Queued(${event.messageId})"
    }

    /** The server accepted a batch with these events. */
    public class Sent internal constructor(
        public val messageIds: List<String>,
    ) : EventUpdate() {
        override fun toString(): String = "Sent(${messageIds.size} events)"
    }

    /** These events were dropped for [reason]; events dropped before they were stored are included. */
    public class Dropped internal constructor(
        public val messageIds: List<String>,
        public val reason: DropReason,
    ) : EventUpdate() {
        override fun toString(): String = "Dropped(${messageIds.size} events, $reason)"
    }
}
