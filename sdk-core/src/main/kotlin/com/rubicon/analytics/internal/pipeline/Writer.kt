package com.rubicon.analytics.internal.pipeline

import com.rubicon.analytics.Autocapture
import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventSource
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.ScreenEvent
import com.rubicon.analytics.TrackEvent
import com.rubicon.analytics.internal.config.ConfigSnapshot
import com.rubicon.analytics.internal.identity.IdentifyTicket
import com.rubicon.analytics.internal.identity.Identity
import com.rubicon.analytics.internal.identity.IdentityRef
import com.rubicon.analytics.internal.identity.IdentitySnapshot
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.LifecycleSource
import com.rubicon.analytics.internal.platform.NetworkMonitor
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.privacy.ConsentController
import com.rubicon.analytics.internal.privacy.ConsentState
import com.rubicon.analytics.internal.remote.RemoteConfigClient
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.session.SessionEvent
import com.rubicon.analytics.internal.session.SessionManager
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What the calling threads and the platform ask of the writer, in order (Queue 1). */
internal sealed interface WriteMessage {
    class Enqueue(
        val event: CapturedEvent,
    ) : WriteMessage

    /** Every flush, whatever triggered it, so it includes every event accepted before it. */
    object Flush : WriteMessage

    /** Write the stores now; any message does it too, this one only wakes the writer. */
    object Persist : WriteMessage

    object Foreground : WriteMessage

    object Background : WriteMessage

    object NetworkRegained : WriteMessage

    /** A reset or a user switch cleared the person; the session ends as theirs (Session 6). */
    class PersonCleared(
        val previous: IdentitySnapshot,
    ) : WriteMessage

    /** `optOut()` or `optIn()` changed the state (Privacy 1, 4). */
    class ConsentChanged(
        val from: ConsentState,
        val to: ConsentState,
    ) : WriteMessage

    /** `clearLocalData()`: every event tracked before it is deleted (Privacy 3). */
    object ClearLocalData : WriteMessage
}

/**
 * The single consumer that writes (Queue 1): events through the [EventPipeline] into the event store,
 * and the person and device stores. It is the only code that writes to disk.
 *
 * - Its first task is loading the stores, then the identity calls made before that, the effective
 *   configuration, the event store and the saved session (Privacy 2, plan section 15, step 6.1).
 * - Calling threads use [enqueue], which never blocks: a full channel drops the event as
 *   `backpressure`.
 * - Flush triggers (Queue 2, 3, 7), all as one [WriteMessage.Flush]: the count of events appended
 *   since the last flush, the interval from the first pending event, backgrounding, the network
 *   coming back while events are pending, `flush()` and immediate events. No startup flush and no
 *   periodic timer. A flush closes the open segment and signals the uploader through the conflated
 *   [uploadSignal].
 * - The stores are written when no message is waiting, at least every [PERSIST_EVERY] messages, and
 *   only when something changed, so changes coalesce (Disk 7).
 * - An unexpected exception while handling a message is logged and the writer goes on; in debug it
 *   is thrown.
 */
internal class Writer(
    private val code: ConfigSnapshot,
    private val platform: Platform,
    private val log: Log,
    private val deviceStore: DeviceStore,
    private val personStore: PersonStore,
    private val identity: Identity,
    private val consent: ConsentController,
    private val remote: RemoteConfigClient,
    private val flags: RuntimeFlags,
    private val newId: () -> String,
    private val scope: CoroutineScope,
) {
    private val channel = Channel<WriteMessage>(CHANNEL_CAPACITY)

    /** Conflated: any number of flushes during an upload become one follow-up upload (Queue 1). */
    val uploadSignal: Channel<Unit> = Channel(Channel.CONFLATED)

    /** Completes once the startup work is done and the stores are loaded; the uploader waits for it. */
    val started: CompletableDeferred<Unit> = CompletableDeferred()

    // Set up at start, on the writer.
    private lateinit var snapshot: ConfigSnapshot
    private lateinit var sessions: SessionManager
    private lateinit var pipeline: EventPipeline
    private var sendsDiagnostics = true

    // Messages accepted into the channel and messages handled, for isIdle.
    private val sentMessages = AtomicLong()
    private val handledMessages = AtomicLong()

    /** No message waiting or being handled; for `flushBlocking` (tests and shutdown). */
    val isIdle: Boolean
        get() = started.isCompleted && handledMessages.get() == sentMessages.get()

    // Writer only.
    private var messagesSincePersist = 0
    private var appendedSinceFlush = 0
    private var interval: Job? = null

    /** Whether `SDK Diagnostics` is sent, as the effective configuration says; known after start. */
    val diagnosticsEnabled: Boolean get() = sendsDiagnostics

    private val lifecycleListener =
        object : LifecycleSource.Listener {
            override fun onForeground() {
                send(WriteMessage.Foreground)
            }

            override fun onBackground() {
                send(WriteMessage.Background)
            }
        }

    private val networkListener = NetworkMonitor.Listener { connected -> if (connected) send(WriteMessage.NetworkRegained) }

    /** Starts the writer: its startup work first, then the messages in order. */
    fun start(): Job =
        scope.launch(platform.dispatchers.writer) {
            try {
                guarded { startUp() }
            } finally {
                started.complete(Unit)
            }
            for (message in channel) {
                try {
                    guarded { handle(message) }
                    // Changes coalesce (Disk 7): the stores are written when no message is waiting,
                    // and at least every PERSIST_EVERY messages under a steady load.
                    messagesSincePersist++
                    if (handledMessages.get() + 1 >= sentMessages.get() || messagesSincePersist >= PERSIST_EVERY) {
                        messagesSincePersist = 0
                        guarded { persistStores() }
                    }
                } finally {
                    handledMessages.incrementAndGet()
                }
            }
        }

    /** From any thread; never blocks. Returns `false` when the event was dropped as `backpressure`. */
    fun enqueue(event: CapturedEvent): Boolean {
        if (offer(WriteMessage.Enqueue(event))) return true
        deviceStore.countDropped(DropReason.BACKPRESSURE)
        log.warn(Code.BACKPRESSURE) { "Events arrive faster than they can be stored; one was dropped" }
        observe(EventUpdate.Dropped(listOf(event.messageId), DropReason.BACKPRESSURE))
        return false
    }

    fun flush() {
        send(WriteMessage.Flush)
    }

    fun requestPersist() {
        send(WriteMessage.Persist)
    }

    fun personCleared(previous: IdentitySnapshot) {
        send(WriteMessage.PersonCleared(previous))
    }

    fun consentChanged(
        from: ConsentState,
        to: ConsentState,
    ) {
        send(WriteMessage.ConsentChanged(from, to))
    }

    fun clearLocalData() {
        send(WriteMessage.ClearLocalData)
    }

    private fun send(message: WriteMessage) {
        if (!offer(message)) log.debug { "The write channel is full; ${message.javaClass.simpleName} was skipped" }
    }

    private fun offer(message: WriteMessage): Boolean {
        // Counted first, so the message is never handled before it is counted.
        sentMessages.incrementAndGet()
        if (channel.trySend(message).isSuccess) return true
        sentMessages.decrementAndGet()
        return false
    }

    private fun startUp() {
        deviceStore.load()
        personStore.load()
        consent.load()
        identity.load()
        val effective = remote.startup()
        snapshot = effective.snapshot
        sendsDiagnostics = effective.sendsDiagnostics
        platform.eventStore.open(snapshot.apiKey, platform.clock, log) { reason, count -> deviceStore.countDropped(reason, count) }
        sessions =
            SessionManager(
                personStore,
                platform.clock,
                platform.elapsedClock,
                snapshot.sessionBackgroundTimeoutMs,
                Autocapture.SESSIONS in snapshot.autocapture,
                newId,
                log,
                ::requestPersist,
            )
        pipeline =
            EventPipeline(snapshot.beforeSend, snapshot.disabledFields, snapshot.debug, platform.contextProvider, sessions, newId, log)
        appendSessionEvents(sessions.start())
        appendedSinceFlush = platform.eventStore.pendingCount
        if (appendedSinceFlush > 0) startInterval()
        platform.lifecycleSource.addListener(lifecycleListener)
        platform.networkMonitor.addListener(networkListener)
        if (platform.lifecycleSource.isForeground) appendSessionEvents(sessions.onForeground())
        persistStores()
        log.debug { "Writer started with ${platform.eventStore.pendingCount} pending events" }
    }

    private fun handle(message: WriteMessage) {
        when (message) {
            // Nothing new while opted out; events that waited for the stored choice are discarded
            // unwritten and uncounted (Privacy 1, 2).
            is WriteMessage.Enqueue -> if (collecting) resolve(message.event)?.let { append(pipeline.process(it), it.skipsHooks) }
            WriteMessage.Flush -> flushNow("requested")
            WriteMessage.Persist -> Unit
            WriteMessage.Foreground -> appendSessionEvents(sessions.onForeground())
            WriteMessage.Background -> {
                sessions.onBackground()
                platform.eventStore.sync()
                flushNow("background")
            }
            WriteMessage.NetworkRegained -> if (platform.eventStore.pendingCount > 0) flushNow("network regained")
            is WriteMessage.PersonCleared -> appendSessionEvents(sessions.onPersonCleared(message.previous))
            is WriteMessage.ConsentChanged -> onConsentChanged(message.from, message.to)
            WriteMessage.ClearLocalData -> clearEvents()
        }
    }

    // An identify is sent only if its ticket says so, with the traits and identity of that moment.
    private fun resolve(event: CapturedEvent): CapturedEvent? {
        val ticket = event.ticket ?: return event
        val decision = ticket.decision as? IdentifyTicket.Decision.SendEvent ?: return null
        return CapturedEvent(
            event.type,
            event.name,
            decision.traits,
            event.source,
            event.messageId,
            event.timestamp,
            IdentityRef().apply { value = decision.identity },
            event.callTime,
        )
    }

    private val collecting: Boolean
        get() = flags.collecting && consent.state != ConsentState.DENIED

    private fun onConsentChanged(
        from: ConsentState,
        to: ConsentState,
    ) {
        when {
            to == ConsentState.DENIED -> {
                // The open session ends without an event (Privacy 1). Events written while consent
                // was pending were collected without it and go, uncounted (Privacy 4).
                sessions.endSilently()
                if (from == ConsentState.PENDING) clearEvents()
            }
            from == ConsentState.PENDING -> flushNow("consent granted")
        }
    }

    // Deleted on purpose: not a loss, so not counted (Privacy 3, 4).
    private fun clearEvents() {
        sessions.endSilently()
        interval?.cancel()
        interval = null
        appendedSinceFlush = 0
        platform.eventStore.clear()
    }

    private fun appendSessionEvents(events: List<SessionEvent>) {
        if (events.isEmpty() || !collecting) return
        val current = checkNotNull(identity.snapshot().value)
        append(
            pipeline.processSessionEvents(events, current, platform.contextProvider.callTimeValues(snapshot.disabledFields)),
            diagnostics = false,
        )
    }

    /** Appends the accepted results; returns the message ids that reached the store. */
    private fun append(
        results: List<PipelineResult>,
        diagnostics: Boolean,
    ): Set<String> {
        val stored = HashSet<String>()
        var immediate: String? = null
        for (result in results) {
            when (result) {
                is PipelineResult.Accepted -> {
                    // A failed append is counted and logged by the store (Disk 4).
                    if (!platform.eventStore.append(result.line)) continue
                    stored += result.messageId
                    observe(EventUpdate.Queued(result.event))
                    appendedSinceFlush++
                    if (interval == null) startInterval()
                    val name = nameOf(result)
                    if (name != null && name in snapshot.immediateEvents) immediate = name
                }
                is PipelineResult.Dropped -> {
                    // The drop of SDK Diagnostics itself is not counted, which avoids a loop (Logging 5).
                    if (!diagnostics) deviceStore.countDropped(result.reason)
                    observe(EventUpdate.Dropped(listOf(result.messageId), result.reason))
                }
            }
        }
        when {
            immediate != null -> {
                log.debug { "Immediate event \"$immediate\" persisted; flushing" }
                flushNow("immediate event")
            }
            appendedSinceFlush >= snapshot.flushAt -> flushNow("count")
        }
        return stored
    }

    private fun flushNow(reason: String) {
        appendDiagnosticsIfDue()
        interval?.cancel()
        interval = null
        appendedSinceFlush = 0
        platform.eventStore.closeOpenSegment()
        uploadSignal.trySend(Unit)
        log.debug { "Flush ($reason), ${platform.eventStore.pendingCount} events pending" }
    }

    // Set while SDK Diagnostics is being appended, so a flush it causes does not report again.
    private var reportingDiagnostics = false

    /**
     * `SDK Diagnostics` right before a flush, when due (Logging 5): never while opted out, pending or
     * switched off, never through the hooks, and its own drop is not counted. The counters it
     * reports are taken off only once it is stored.
     */
    private fun appendDiagnosticsIfDue() {
        if (reportingDiagnostics || !sendsDiagnostics || !collecting || consent.state != ConsentState.GRANTED) return
        val now = platform.clock.currentTimeMillis()
        val state = deviceStore.state.diagnostics
        if (!Diagnostics.isDue(state, now)) return
        reportingDiagnostics = true
        try {
            val event =
                CapturedEvent(
                    CapturedEvent.Type.TRACK,
                    Diagnostics.EVENT_NAME,
                    Diagnostics.properties(state, now),
                    EventSource.AUTO,
                    newId(),
                    now,
                    identity.snapshot(),
                    platform.contextProvider.callTimeValues(snapshot.disabledFields),
                    skipsHooks = true,
                )
            if (event.messageId in append(pipeline.process(event), diagnostics = true)) {
                deviceStore.diagnosticsReported(state, now)
                log.debug { "SDK Diagnostics written" }
            }
        } finally {
            reportingDiagnostics = false
        }
    }

    private fun startInterval() {
        interval =
            scope.launch(platform.dispatchers.writer) {
                delay(snapshot.flushIntervalMs)
                send(WriteMessage.Flush)
            }
    }

    private fun persistStores() {
        personStore.persist()
        deviceStore.persist()
    }

    private fun observe(update: EventUpdate) {
        val observer = code.eventObserver ?: return
        log.callbacks.notifyObserver { observer.onUpdate(update) }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (code.debug) throw e
            log.error(Code.WORKER_FAILED, e) { "The writer failed on ${e.javaClass.name}; it goes on with the next message" }
        }
    }

    private companion object {
        /** Queue 1: the write channel holds at most this many messages. */
        const val CHANNEL_CAPACITY = 1000

        /** Under a steady load the stores are still written at least this often. */
        const val PERSIST_EVERY = 100

        fun nameOf(result: PipelineResult.Accepted): String? =
            when (val event = result.event) {
                is TrackEvent -> event.name
                is ScreenEvent -> event.name
                else -> null
            }
    }
}
