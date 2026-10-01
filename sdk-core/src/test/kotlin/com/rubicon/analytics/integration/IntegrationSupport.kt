package com.rubicon.analytics.integration

import com.rubicon.analytics.AnalyticsConfig
import com.rubicon.analytics.internal.http.HttpTransportFactory
import com.rubicon.analytics.internal.platform.Clock
import com.rubicon.analytics.internal.platform.ElapsedClock
import com.rubicon.analytics.internal.platform.Platform
import com.rubicon.analytics.internal.platform.SdkDispatchers
import com.rubicon.analytics.internal.storage.FileEventStore
import com.rubicon.analytics.internal.storage.FileKeyValueStore
import com.rubicon.analytics.internal.storage.FileSystem
import com.rubicon.analytics.testing.FakeContextProvider
import com.rubicon.analytics.testing.FakeLifecycleSource
import com.rubicon.analytics.testing.FakeNetworkMonitor
import com.rubicon.analytics.testing.FakeProcessInfo
import com.rubicon.analytics.testing.RecordingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/**
 * The real core on the JVM (plan section 5, component integration): real files under a temporary
 * directory, the real `HttpURLConnection` transport against MockWebServer, real dispatchers and
 * clocks. Only the Android parts are fakes.
 */
internal object IntegrationSupport {
    const val API_KEY = "rk_integration"

    fun platform(
        root: File,
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        lifecycle: FakeLifecycleSource = FakeLifecycleSource(),
        logger: RecordingLogger = RecordingLogger(),
    ): Platform =
        Platform(
            clock = Clock.SYSTEM,
            elapsedClock = ElapsedClock.SYSTEM,
            random = Random(7),
            dispatchers = SdkDispatchers.default(),
            networkMonitor = network,
            httpTransportFactory = HttpTransportFactory.DEFAULT,
            contextProvider = FakeContextProvider(),
            processInfo = FakeProcessInfo(),
            lifecycleSource = lifecycle,
            logger = logger,
            eventStore = FileEventStore(root),
            keyValueStore = FileKeyValueStore(root),
            storageRoot = root,
            fileSystem = FileSystem.SYSTEM,
        )

    fun config(
        server: MockWebServer,
        configure: AnalyticsConfig.() -> Unit = {},
    ): AnalyticsConfig =
        AnalyticsConfig(API_KEY).apply {
            host = server.url("/").toString().trimEnd('/')
            logging.sendDiagnostics = false
            configure()
        }

    /** The decompressed body of an upload. */
    fun body(request: RecordedRequest): JsonObject {
        // A copy, so the same upload can be read more than once.
        val bytes = request.body.clone().readByteArray()
        val text =
            if (request.getHeader("Content-Encoding") ==
                "gzip"
            ) {
                GZIPInputStream(bytes.inputStream()).readBytes().decodeToString()
            } else {
                bytes.decodeToString()
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    fun events(request: RecordedRequest): List<JsonObject> = body(request)["batch"]!!.jsonArray.map { it.jsonObject }
}

/**
 * A backend for MockWebServer: `/v1/config` answers 404 (no remote values), `/v1/batch` answers the
 * scripted responses in order, then 200, and keeps every upload for the test.
 */
internal class FakeBackend : Dispatcher() {
    private val scripted = LinkedBlockingQueue<MockResponse>()
    val uploads = LinkedBlockingQueue<RecordedRequest>()

    fun enqueue(vararg responses: MockResponse) {
        scripted.addAll(responses)
    }

    /** The next upload, waiting up to five seconds for it. */
    fun nextUpload(): RecordedRequest = checkNotNull(uploads.poll(5, TimeUnit.SECONDS)) { "No upload arrived" }

    override fun dispatch(request: RecordedRequest): MockResponse =
        when (request.path) {
            "/v1/config" -> MockResponse().setResponseCode(404)
            "/v1/batch" -> {
                uploads += request
                scripted.poll() ?: MockResponse()
            }
            else -> MockResponse().setResponseCode(404)
        }
}
