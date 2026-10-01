package com.rubicon.analytics.internal.upload

import com.rubicon.analytics.DropReason
import com.rubicon.analytics.EventObserver
import com.rubicon.analytics.EventUpdate
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpTransport
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.NetworkMonitor
import com.rubicon.analytics.internal.remote.RemoteConfigClient
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.storage.BatchResult
import com.rubicon.analytics.internal.storage.EventStore
import com.rubicon.analytics.internal.store.DeviceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single consumer that sends (Queue 1, 4; Retry 1 to 5): one request in flight, batches oldest
 * first, each closed segment one batch, deleted only after the server acknowledged it.
 *
 * - It wakes on the writer's conflated [signals] and on the network coming back, and after a wait
 *   it tries again by itself (Retry 2). A wake during a wait checks the rules again.
 * - Gates (Queue 2): no attempt while offline, while a wait is running, while collection is
 *   stopped (kill switch or outside `sample_rate`), or while [uploadsAllowed] says no (opted out or
 *   consent pending, step 6.5). Released gates resume at the next wake.
 * - Each attempt writes the body with a fresh `sent_at` (Event Model 5), sends it once, and lets
 *   [policy] decide; it stops at the first failure and waits.
 * - The remote configuration is fetched once per launch, the first time the network is there, also
 *   while uploads are gated (Configuration 1, Privacy 4).
 * - Retry state lives in `DeviceStore`; [requestPersist] asks the writer to write it (Queue 1).
 */
internal class Uploader(
    private val eventStore: EventStore,
    private val transport: HttpTransport,
    private val batchUrl: String,
    private val policy: RetryPolicy,
    private val remote: RemoteConfigClient,
    private val networkMonitor: NetworkMonitor,
    private val flags: RuntimeFlags,
    private val deviceStore: DeviceStore,
    private val clock: Clock,
    private val log: Log,
    private val observer: EventObserver?,
    private val signals: Channel<Unit>,
    private val writerStarted: Deferred<Unit>,
    private val requestPersist: () -> Unit,
    private val uploadsAllowed: () -> Boolean = { true },
) {
    private val networkReturned = AtomicBoolean(false)
    private var remoteFetched = false

    private val networkListener =
        NetworkMonitor.Listener { connected ->
            if (connected) {
                networkReturned.set(true)
                signals.trySend(Unit)
            }
        }

    fun start(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ): Job =
        scope.launch(dispatcher) {
            writerStarted.await()
            policy.restore()
            networkMonitor.addListener(networkListener)
            // No upload at startup (Queue 2): leftovers wait for a flush trigger, such as the interval
            // the writer starts for them. Only the remote configuration is fetched now.
            if (networkMonitor.isConnected()) fetchRemoteOnce()
            for (signal in signals) drain()
        }

    /** Sends batches until none is left, a gate closes, or a failure starts a wait. */
    private suspend fun drain() {
        val unreadable = HashSet<Long>()
        while (true) {
            if (networkReturned.getAndSet(false)) policy.onNetworkRegained()
            if (!networkMonitor.isConnected()) return
            fetchRemoteOnce()
            if (!flags.collecting || !uploadsAllowed()) return
            val wait = policy.waitMs()
            if (wait > 0) {
                // Any wake ends the sleep early so the rules are checked again: the network coming
                // back ends a network wait at once, other waits simply go on (Retry 2).
                withTimeoutOrNull(wait) { signals.receive() }
                continue
            }
            eventStore.expire()
            val next = policy.order(eventStore.closedSegments().filter { it !in unreadable }.map(::nameOf)).firstOrNull() ?: return
            if (!attempt(next.toLong(), next)) unreadable += next.toLong()
            requestPersist()
        }
    }

    /** One batch; returns `false` when the segment could not be read and was kept for later. */
    private fun attempt(
        index: Long,
        name: String,
    ): Boolean {
        val body = ByteArrayOutputStream()
        val batch =
            when (val result = eventStore.writeBatch(index, clock.currentTimeMillis(), body)) {
                is BatchResult.Unreadable -> {
                    if (result.deleted) policy.forget(name)
                    return result.deleted
                }
                is BatchResult.Written -> result.messageIds
            }
        if (batch.isEmpty()) {
            // Every line was corrupt or expired: nothing to send (Disk 5, Queue 5).
            delete(index, name)
            return true
        }
        val response = transport.execute(HttpRequest(HttpRequest.Method.POST, batchUrl, body = body.toByteArray()))
        when (policy.onResult(name, response)) {
            RetryPolicy.Decision.DELETE_SENT -> {
                delete(index, name)
                log.debug { "Uploaded ${batch.size} events from segment $name" }
                observe(EventUpdate.Sent(batch))
            }
            RetryPolicy.Decision.DELETE_REJECTED -> drop(index, name, batch, DropReason.REJECTED)
            RetryPolicy.Decision.DELETE_EXHAUSTED -> drop(index, name, batch, DropReason.RETRY_EXHAUSTED)
            RetryPolicy.Decision.KEEP -> Unit
        }
        return true
    }

    private fun drop(
        index: Long,
        name: String,
        batch: List<String>,
        reason: DropReason,
    ) {
        delete(index, name)
        deviceStore.countDropped(reason, batch.size)
        observe(EventUpdate.Dropped(batch, reason))
    }

    private fun delete(
        index: Long,
        name: String,
    ) {
        eventStore.deleteSegment(index)
        policy.forget(name)
    }

    private fun fetchRemoteOnce() {
        if (remoteFetched) return
        remoteFetched = true
        remote.fetch()
    }

    private fun observe(update: EventUpdate) {
        val observer = observer ?: return
        log.callbacks.notifyObserver { observer.onUpdate(update) }
    }

    private companion object {
        /** The segment name the retry state keys on, such as `0000000041` (Retry 5). */
        fun nameOf(index: Long): String = index.toString().padStart(10, '0')
    }
}
