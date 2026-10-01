package com.rubicon.analytics.internal.event

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.Event
import com.rubicon.analytics.EventContext
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.IdentifyEvent
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.TrackEvent
import com.rubicon.analytics.internal.json.Json
import java.util.Locale

/**
 * The wire form of events (Event Model 1, 2, 5; Serialization 4): `snake_case` keys written by hand,
 * so the contract is visible here, and epoch milliseconds for times. One line per event is exactly
 * what is stored and sent (Disk 3). The golden files under `src/test/resources/golden` pin it.
 *
 * - `user_id` is always present, `null` while anonymous, since it is part of the event's identity.
 * - A context value that is `null` is omitted, and so is a context group left empty.
 */
@RubiconInternal
public object EventWire {
    /** The compact JSON line of [event]. */
    public fun line(event: Event): String = Json.write(toMap(event))

    public fun toMap(event: Event): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        when (event) {
            is TrackEvent -> {
                map["type"] = "track"
                map["event"] = event.name
            }
            is ScreenEvent -> {
                map["type"] = "screen"
                map["name"] = event.name
            }
            is IdentifyEvent -> map["type"] = "identify"
        }
        map["source"] = event.source.wireName
        map["message_id"] = event.messageId
        map["timestamp"] = event.timestamp
        map["user_id"] = event.userId
        map["device_id"] = event.deviceId
        when (event) {
            is TrackEvent -> map["properties"] = event.properties
            is ScreenEvent -> map["properties"] = event.properties
            is IdentifyEvent -> map["traits"] = event.traits
        }
        map["context"] = contextMap(event.context)
        return map
    }

    private fun contextMap(context: EventContext): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map.group(
            "app",
            "name" to context.app.name,
            "version" to context.app.version,
            "build" to context.app.build,
            "namespace" to context.app.namespace,
        )
        map.group(
            "device",
            "manufacturer" to context.device.manufacturer,
            "model" to context.device.model,
            "type" to context.device.type,
            "is_emulator" to context.device.isEmulator,
        )
        map.group("os", "name" to context.os.name, "version" to context.os.version, "api_level" to context.os.apiLevel)
        map.group("screen", "width" to context.screen.width, "height" to context.screen.height, "density" to context.screen.density)
        map.group("network", "type" to context.network.type, "carrier" to context.network.carrier)
        context.locale?.let { map["locale"] = it }
        context.timezone?.let { map["timezone"] = it }
        context.session?.let { map.group("session", "id" to it.id, "started_at" to it.startedAt) }
        map.group("library", "name" to context.library.name, "version" to context.library.version)
        return map
    }

    private fun MutableMap<String, Any?>.group(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val present = fields.filter { it.second != null }
        if (present.isNotEmpty()) this[name] = linkedMapOf(*present.toTypedArray())
    }
}

/** `app` or `auto`. */
@RubiconInternal
public val EventSource.wireName: String
    get() = name.lowercase(Locale.ROOT)

/** The key of the reason in `SDK Diagnostics`, such as `queue_full`. */
@RubiconInternal
public val DropReason.wireName: String
    get() = name.lowercase(Locale.ROOT)
