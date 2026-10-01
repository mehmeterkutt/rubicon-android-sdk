package com.rubicon.analytics.internal.store

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.storage.KeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A logger, a log and helpers shared by the store tests. */
internal class StoreTestSupport {
    val logger = RecordingLogger()
    val log = Log(LogLevel.DEBUG, null, logger)

    private var ids = 0

    /** Device ids `device-1`, `device-2` and so on. */
    val newDeviceId: () -> String = { "device-${++ids}" }

    /** The stored file parsed by an independent parser. */
    fun stored(
        store: KeyValueStore,
        name: String,
    ): JsonObject = Json.parseToJsonElement((store.read(name) as KeyValueStore.Read.Present).content).jsonObject

    fun warned(code: Code): Boolean = logger.lines.any { it.level == LogLevel.WARN && it.message.contains(code.name) }

    fun errored(code: Code): Boolean = logger.lines.any { it.level == LogLevel.ERROR && it.message.contains(code.name) }
}
