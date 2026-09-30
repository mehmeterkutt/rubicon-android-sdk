package com.rubicon.analytics.testing

import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.platform.SdkDispatchers
import com.rubicon.analytics.internal.storage.FileSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.File
import kotlin.random.Random

/**
 * A [Platform] made of fakes, with each fake kept as a property so the test can drive it. Override a
 * part with a named argument:
 *
 * ```
 * val test = TestPlatform(dispatcher = StandardTestDispatcher(testScheduler), clock = FakeClock(0))
 * test.transport.enqueue(HttpResult.HttpError(503, null, null))
 * // test.platform goes to the component under test, or to the internal factory (step 6.7)
 * ```
 *
 * Every SDK coroutine runs on [dispatcher], so time is virtual under `runTest`. The stores are in
 * memory; a test of the file implementations passes its own temporary [storageRoot] and, for fault
 * injection, a [FaultyFileSystem].
 */
public class TestPlatform(
    public val dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
    public val clock: FakeClock = FakeClock(),
    public val elapsedClock: FakeElapsedClock = FakeElapsedClock(),
    public val random: Random = SeededRandom(),
    public val networkMonitor: FakeNetworkMonitor = FakeNetworkMonitor(),
    public val transport: FakeTransport = FakeTransport(),
    public val contextProvider: FakeContextProvider = FakeContextProvider(),
    public val processInfo: FakeProcessInfo = FakeProcessInfo(),
    public val lifecycleSource: FakeLifecycleSource = FakeLifecycleSource(),
    public val eventStore: InMemoryEventStore = InMemoryEventStore(),
    public val keyValueStore: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    public val storageRoot: File = File(System.getProperty("java.io.tmpdir"), "rubicon-test-unused"),
    public val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    public val platform: Platform =
        Platform(
            clock = clock,
            elapsedClock = elapsedClock,
            random = random,
            dispatchers = SdkDispatchers(writer = dispatcher, uploader = dispatcher, watchdog = dispatcher),
            networkMonitor = networkMonitor,
            httpTransport = transport,
            contextProvider = contextProvider,
            processInfo = processInfo,
            lifecycleSource = lifecycleSource,
            eventStore = eventStore,
            keyValueStore = keyValueStore,
            storageRoot = storageRoot,
            fileSystem = fileSystem,
        )
}
