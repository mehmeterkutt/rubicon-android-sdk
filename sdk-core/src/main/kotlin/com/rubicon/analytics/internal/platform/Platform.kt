package com.rubicon.analytics.internal.platform

import com.rubicon.analytics.RubiconInternal
import com.rubicon.analytics.RubiconLogger
import com.rubicon.analytics.internal.context.ContextProvider
import com.rubicon.analytics.internal.http.HttpTransportFactory
import com.rubicon.analytics.internal.storage.EventStore
import com.rubicon.analytics.internal.storage.FileSystem
import com.rubicon.analytics.internal.storage.KeyValueStore
import java.io.File
import kotlin.random.Random

/**
 * Everything the SDK takes from its environment, in one place (Configuration 5). `sdk-android`
 * builds the production instance; tests build one from `TestPlatform` and reach the SDK through
 * the `@RubiconInternal` factory overload. Nothing here is replaceable by developers.
 *
 * @property logger the platform's default logger: Logcat on Android, `NoOpLogger` on the JVM. Used
 *   when the developer sets none, and when the developer's logger throws (Logging 1, 4b).
 * @property httpTransportFactory builds the transport once the configuration is validated (HTTP 1).
 * @property storageRoot `noBackupFilesDir/rubicon/` on Android (Disk 2a).
 * @property fileSystem the file operations of the file-based stores, a seam for fault injection.
 */
@RubiconInternal
public class Platform(
    public val clock: Clock,
    public val elapsedClock: ElapsedClock,
    public val random: Random,
    public val dispatchers: SdkDispatchers,
    public val networkMonitor: NetworkMonitor,
    public val httpTransportFactory: HttpTransportFactory,
    public val contextProvider: ContextProvider,
    public val processInfo: ProcessInfo,
    public val lifecycleSource: LifecycleSource,
    public val logger: RubiconLogger,
    public val eventStore: EventStore,
    public val keyValueStore: KeyValueStore,
    public val storageRoot: File,
    public val fileSystem: FileSystem,
)
