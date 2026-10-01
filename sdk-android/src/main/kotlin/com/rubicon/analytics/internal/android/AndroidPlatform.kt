package com.rubicon.analytics.internal.android

import android.content.Context
import android.os.SystemClock
import com.rubicon.analytics.internal.http.HttpTransportFactory
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.platform.SdkDispatchers
import com.rubicon.analytics.internal.storage.FileEventStore
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.storage.FileSystem
import java.io.File
import kotlin.random.Random

/**
 * The Android [Platform] (Configuration 5). Building it reads nothing from disk, so the factory can
 * run on the main thread (Public API 9): the storage root is computed from `applicationInfo.dataDir`
 * rather than `noBackupFilesDir`, which creates the directory on first call; the writer creates it.
 */
internal object AndroidPlatform {
    /** `noBackupFilesDir/rubicon/` (Disk 2a): excluded from backup, removed with the app's data. */
    fun storageRoot(context: Context): File = File(File(context.applicationInfo.dataDir, NO_BACKUP_DIR), ROOT_DIR)

    fun create(
        context: Context,
        processInfo: AndroidProcessInfo,
    ): Platform {
        val root = storageRoot(context)
        val elapsedClock = ElapsedClock { SystemClock.elapsedRealtime() }
        val networkMonitor = AndroidNetworkMonitor(context)
        return Platform(
            clock = Clock.SYSTEM,
            elapsedClock = elapsedClock,
            random = Random.Default,
            dispatchers = SdkDispatchers.default(),
            networkMonitor = networkMonitor,
            httpTransportFactory = HttpTransportFactory.DEFAULT,
            contextProvider = AndroidContextProvider(context, networkMonitor, elapsedClock),
            processInfo = processInfo,
            lifecycleSource = AndroidLifecycleSource(context),
            logger = LogcatLogger,
            eventStore = FileEventStore(root),
            keyValueStore = FileKeyValueStore(root),
            storageRoot = root,
            fileSystem = FileSystem.SYSTEM,
        )
    }

    /** The name `Context.getNoBackupFilesDir()` uses inside the data directory. */
    private const val NO_BACKUP_DIR = "no_backup"
    private const val ROOT_DIR = "rubicon"
}
