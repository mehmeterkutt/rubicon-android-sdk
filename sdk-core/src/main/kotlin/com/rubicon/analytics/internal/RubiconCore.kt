package com.rubicon.analytics.internal

import com.rubicon.analytics.Analytics
import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.internal.config.ConfigValidator
import com.rubicon.analytics.internal.http.TransportSettings
import com.rubicon.analytics.internal.identity.Identity
import com.rubicon.analytics.internal.log.Code
import com.rubicon.analytics.internal.log.IssueCounter
import com.rubicon.analytics.internal.log.Log
import com.rubicon.analytics.internal.pipeline.Writer
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.privacy.ConsentController
import com.rubicon.analytics.internal.privacy.ConsentState
import com.rubicon.analytics.internal.remote.RemoteConfigClient
import com.rubicon.analytics.internal.remote.RuntimeFlags
import com.rubicon.analytics.internal.store.DeviceStore
import com.rubicon.analytics.internal.store.PersonStore
import com.rubicon.analytics.internal.upload.RetryPolicy
import com.rubicon.analytics.internal.upload.Uploader
import com.rubicon.analytics.internal.util.UuidV7Generator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicReference

/**
 * The core factory, `(config, platform)`; `sdk-android`'s `Analytics(context, config)` calls it with
 * the Android platform, and tests with a test platform (Configuration 3, 5).
 *
 * - The configuration is validated: in debug a mistake throws; in release a blank key or an
 *   invalid host gives [NoOpAnalytics] with an ERROR, and other mistakes are fixed with a WARN.
 *   The object is then frozen, and the effective host is logged once at INFO.
 * - One instance per process: a second call throws in debug and returns the first instance in
 *   release, whose configuration stays in force (Public API 1, Configuration 3).
 * - Nothing is read on the calling thread; the writer loads the stores first (Privacy 2).
 */
@RubiconInternal
public object RubiconCore {
    private val instance = AtomicReference<Analytics?>(null)

    public fun create(
        config: AnalyticsConfig,
        platform: Platform,
    ): Analytics {
        val debug = config.debug
        // Counted into DeviceStore once it exists; the log is needed to build it.
        var deviceStore: DeviceStore? = null
        val log =
            Log(
                config.logging.level,
                config.logging.logger,
                platform.logger,
                IssueCounter { code ->
                    deviceStore?.issueCounter?.increment(code)
                },
            )
        val existing = instance.get()
        if (existing != null) return second(existing, debug, log)
        val result = ConfigValidator.validate(config)
        result.report(debug, log)
        ConfigValidator.freeze(config, debug, log)
        val code = result.snapshot ?: return NoOpAnalytics
        log.info { "Sending to ${code.host}" }

        val ids = UuidV7Generator(platform.clock, platform.random)
        val device = DeviceStore(platform.keyValueStore, log, platform.clock, ids::next)
        deviceStore = device
        val person = PersonStore(platform.keyValueStore, log)
        val flags = RuntimeFlags()
        val scope =
            CoroutineScope(
                SupervisorJob() +
                    CoroutineExceptionHandler { _, t ->
                        // Release never rethrows; debug makes the mistake visible (Queue 1).
                        if (debug) throw t
                        log.error(Code.WORKER_FAILED, t) { "An SDK coroutine failed on ${t.javaClass.name}" }
                    },
            )
        lateinit var writer: Writer
        val consent =
            ConsentController(code.defaultConsent, code.persistConsent, device, log) { from, to -> writer.consentChanged(from, to) }
        val identity =
            Identity(
                person,
                device,
                log,
                debug,
                eventsAllowed = { consent.state != ConsentState.DENIED },
                requestPersist = { writer.requestPersist() },
            ) { previous -> writer.personCleared(previous) }
        val transport = platform.httpTransportFactory.create(TransportSettings(code.apiKey, code.headers, log), platform)
        val remote = RemoteConfigClient(code, device, transport, flags, log)
        writer = Writer(code, platform, log, device, person, identity, consent, remote, flags, ids::next, scope)
        val uploader =
            Uploader(
                platform.eventStore,
                transport,
                code.batchUrl,
                RetryPolicy(device, platform.clock, platform.elapsedClock, platform.random, log),
                remote,
                platform.networkMonitor,
                flags,
                device,
                platform.clock,
                log,
                code.eventObserver,
                writer.uploadSignal,
                writer.started,
                writer::requestPersist,
            ) { consent.allowsUploads }
        val analytics = DefaultAnalytics(code, platform, log, identity, consent, flags, writer, ids::next, scope)
        if (!instance.compareAndSet(null, analytics)) return second(instance.get()!!, debug, log)
        writer.start()
        uploader.start(scope, platform.dispatchers.uploader)
        return analytics
    }

    private fun second(
        existing: Analytics,
        debug: Boolean,
        log: Log,
    ): Analytics {
        val message = "Analytics was created a second time in this process; the first instance is returned"
        if (debug) throw IllegalStateException(message)
        log.error(Code.SECOND_INSTANCE) { message }
        return existing
    }

    /**
     * Asks [analytics] to flush and waits until nothing is pending, at most [timeoutMs]; `true` when
     * everything was sent, `false` for the no-op instance. For tests and shutdown only (Public API 9).
     */
    public fun flushBlocking(
        analytics: Analytics,
        timeoutMs: Long,
    ): Boolean = (analytics as? DefaultAnalytics)?.flushBlocking(timeoutMs) ?: false

    /** Stops and forgets the instance; for tests that create one per test, in both modules. */
    public fun resetForTests() {
        (instance.getAndSet(null) as? DefaultAnalytics)?.shutdown()
    }
}
