package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.config.ConfigValidator
import com.rubicon.analytics.internal.context.CallTimeValues
import com.rubicon.analytics.internal.identity.Identity
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.privacy.ConsentController
import com.rubicon.analytics.internal.privacy.ConsentState
import com.rubicon.analytics.internal.remote.RemoteConfigClient
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.util.UuidV7Generator
import com.rubicon.analytics.testing.InMemoryEventStore
import com.rubicon.analytics.testing.InMemoryKeyValueStore
import com.rubicon.analytics.testing.RecordingLogger
import com.rubicon.analytics.testing.TestPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The writer wired as the SDK will wire it (step 6.7), on a test dispatcher, with in-memory stores:
 * identity and consent tell the writer through its messages.
 */
internal class WriterHarness(
    testScope: TestScope,
    configure: AnalyticsConfig.() -> Unit = {},
    val eventStore: InMemoryEventStore = InMemoryEventStore(),
    val keyValueStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
) {
    val updates = mutableListOf<EventUpdate>()
    val code: ConfigSnapshot =
        ConfigValidator
            .validate(
                AnalyticsConfig("rk_test").apply {
                    flush.at = 5
                    flush.intervalMs = 60_000
                    logging.eventObserver = EventObserver { updates += it }
                    configure()
                },
            ).snapshot!!
    val test =
        TestPlatform(
            dispatcher = StandardTestDispatcher(testScope.testScheduler),
            eventStore = eventStore,
            keyValueStore = keyValueStore,
        )
    val logger = RecordingLogger()
    val log = Log(LogLevel.DEBUG, null, logger)
    val ids = UuidV7Generator(test.clock, test.random)
    val deviceStore = DeviceStore(keyValueStore, log, test.clock, ids::next)
    val personStore = PersonStore(keyValueStore, log)
    val flags = RuntimeFlags()
    val scope = CoroutineScope(SupervisorJob() + test.dispatcher)
    lateinit var writer: Writer
    val consent =
        ConsentController(code.defaultConsent, code.persistConsent, deviceStore, log) { from, to -> writer.consentChanged(from, to) }
    val identity =
        Identity(
            personStore,
            deviceStore,
            log,
            debug = false,
            eventsAllowed = { consent.state != ConsentState.DENIED },
            requestPersist = { writer.requestPersist() },
        ) { writer.personCleared(it) }
    val remote = RemoteConfigClient(code, deviceStore, test.transport, flags, log)

    init {
        writer = Writer(code, test.platform, log, deviceStore, personStore, identity, consent, remote, flags, ids::next, scope)
    }

    private var seq = 0

    fun track(
        name: String = "Tapped",
        values: Map<String, Any?> = emptyMap(),
        callTime: CallTimeValues = CallTimeValues.NONE,
    ): Boolean =
        writer.enqueue(
            CapturedEvent(
                CapturedEvent.Type.TRACK,
                name,
                values,
                EventSource.APP,
                "m-${++seq}",
                test.clock.currentTimeMillis(),
                identity.snapshot(),
                callTime,
            ),
        )

    fun lines(): List<String> = eventStore.closedSegments().flatMap { eventStore.segmentLines(it) } + eventStore.openSegment

    fun names(): List<String> = lines().map { field(it, "event") ?: "?" }

    fun field(
        line: String,
        key: String,
    ): String? {
        val value = Json.parseToJsonElement(line).jsonObject[key]
        // JSON null reads as no value, not as the text "null".
        return if (value == null || value is JsonNull) null else value.jsonPrimitive.content
    }

    fun signals(): Int {
        var n = 0
        while (writer.uploadSignal.tryReceive().isSuccess) n++
        return n
    }

    fun stop() = scope.cancel()
}
