package com.rubicon.analytics.internal.android

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.Analytics
import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.internal.NoOpAnalytics
import com.rubicon.analytics.internal.RubiconCore
import com.rubicon.analytics.testing.RecordingLogger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// Both sides of the API 28 process name lookup (Disk 2d).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class FactoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val uploads = LinkedBlockingQueue<RecordedRequest>()

    @Before
    fun start() {
        RubiconCore.resetForTests()
        ShadowLog.clear()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/v1/batch") return MockResponse().setResponseCode(404)
                    uploads += request
                    return MockResponse()
                }
            }
        server.start()
    }

    @After
    fun stop() {
        RubiconCore.resetForTests()
        server.shutdown()
    }

    private fun config(configure: AnalyticsConfig.() -> Unit = {}) =
        AnalyticsConfig("rk_android").apply {
            host = server.url("/").toString().trimEnd('/')
            logging.sendDiagnostics = false
            configure()
        }

    private fun runningProcesses(vararg names: String) {
        val processes =
            names.map { name ->
                ActivityManager.RunningAppProcessInfo().apply {
                    processName = name
                    pid = Process.myPid()
                }
            }
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setProcesses(processes)
    }

    @Test
    fun `the main process gets the real instance, with files only under no_backup`() {
        val analytics = Analytics(app, config())

        analytics.track("Opened")

        assertTrue(RubiconCore.flushBlocking(analytics, 10_000))
        assertTrue(uploads.poll(5, TimeUnit.SECONDS) != null)
        val root = AndroidPlatform.storageRoot(app)
        assertEquals(app.noBackupFilesDir.absolutePath, root.parentFile!!.absolutePath)
        assertTrue(File(root, "device.json").isFile)
        assertTrue(File(root, "person.json").isFile)
        val prefs = File(app.applicationInfo.dataDir, "shared_prefs")
        assertTrue(!prefs.exists() || prefs.list()!!.isEmpty())
        assertFalse(File(app.filesDir, "rubicon").exists())
    }

    @Test
    fun `events carry the device's context`() {
        val analytics = Analytics(app, config())

        analytics.track("Opened")

        assertTrue(RubiconCore.flushBlocking(analytics, 10_000))
        val body = text(uploads.poll(5, TimeUnit.SECONDS)!!)
        assertTrue(body.contains("\"namespace\":\"${app.packageName}\""))
        assertTrue(body.contains("\"api_level\":${Build.VERSION.SDK_INT}"))
        assertTrue(body.contains("\"locale\":"))
        assertTrue(body.contains("\"width\":"))
    }

    private fun text(request: RecordedRequest): String {
        val bytes = request.body.readByteArray()
        val gzip = request.getHeader("Content-Encoding") == "gzip"
        return (if (gzip) GZIPInputStream(bytes.inputStream()).readBytes() else bytes).toString(Charsets.UTF_8)
    }

    @Test
    @Config(sdk = [23])
    fun `another process gets the no-op instance and one INFO line`() {
        runningProcesses("${app.packageName}:remote")
        val logger = RecordingLogger()

        val analytics =
            Analytics(
                app,
                config {
                    logging.logger = logger
                    logging.level = LogLevel.INFO
                },
            )

        assertSame(NoOpAnalytics, analytics)
        assertEquals(1, logger.lines.count { it.level == LogLevel.INFO && it.message.contains("main process") })
        assertFalse(AndroidPlatform.storageRoot(app).exists())
    }

    @Test
    @Config(sdk = [23])
    fun `an unknown process name is assumed main, with a warning`() {
        runningProcesses()
        val logger = RecordingLogger()

        val analytics = Analytics(app, config { logging.logger = logger })

        assertNotSame(NoOpAnalytics, analytics)
        assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.startsWith("PROCESS_NAME_UNKNOWN") })
    }

    @Test
    fun `the main process name follows applicationInfo and falls back to the package`() {
        assertEquals(true, AndroidProcessInfo(app) { app.packageName }.isMainProcess())
        assertEquals(false, AndroidProcessInfo(app) { "${app.packageName}:remote" }.isMainProcess())
        assertNull(AndroidProcessInfo(app) { null }.isMainProcess())

        app.applicationInfo.processName = "com.example.renamed"
        assertEquals(true, AndroidProcessInfo(app) { "com.example.renamed" }.isMainProcess())
        assertEquals(false, AndroidProcessInfo(app) { app.packageName }.isMainProcess())

        app.applicationInfo.processName = null
        assertEquals(true, AndroidProcessInfo(app) { app.packageName }.isMainProcess())
    }

    @Test
    fun `the default lookup finds this process on both sides of API 28`() {
        if (Build.VERSION.SDK_INT < 28) runningProcesses(app.packageName)

        assertEquals(true, AndroidProcessInfo(app).isMainProcess())
    }

    @Test
    fun `building the platform touches no file`() {
        val noBackup = AndroidPlatform.storageRoot(app).parentFile!!
        noBackup.deleteRecursively()

        AndroidPlatform.create(app, AndroidProcessInfo(app))

        assertFalse(noBackup.exists())
    }

    @Test
    fun `without a logger the SDK logs to Logcat with the tag Rubicon`() {
        Analytics(app, config { logging.level = LogLevel.INFO })

        assertTrue(ShadowLog.getLogsForTag(LogcatLogger.TAG).any { it.msg.startsWith("Sending to") })
    }

    @Test
    fun `a developer's logger is never replaced`() {
        val logger = RecordingLogger()

        Analytics(
            app,
            config {
                logging.level = LogLevel.INFO
                logging.logger = logger
            },
        )

        assertTrue(logger.messages.any { it.startsWith("Sending to") })
        assertTrue(ShadowLog.getLogsForTag(LogcatLogger.TAG).isEmpty())
    }

    @Test
    fun `any context works and the application context is the one kept`() {
        val activityLike = app.createConfigurationContext(app.resources.configuration)

        assertNotSame(NoOpAnalytics, Analytics(activityLike, config()))
    }

    @Test
    fun `process facts for the User-Agent come from Build`() {
        val info = AndroidProcessInfo(app)

        assertEquals(Build.VERSION.SDK_INT, info.apiLevel)
        assertTrue(info.osVersion.isNotEmpty())
    }
}
