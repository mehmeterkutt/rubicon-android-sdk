package com.rubicon.analytics.internal.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rubicon.analytics.internal.http.HttpRequest
import com.rubicon.analytics.internal.http.HttpResult
import com.rubicon.analytics.testing.FakeClock
import com.rubicon.analytics.testing.TestPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// sdk-android tests reach the core fakes through testFixtures(project(":sdk-core")).
@RunWith(RobolectricTestRunner::class)
class TestPlatformAccessTest {
    @Test
    fun `a Robolectric test builds a platform from the core fakes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.noBackupFilesDir, "rubicon")
        val test = TestPlatform(clock = FakeClock(start = 0), storageRoot = root)
        test.transport.enqueue(HttpResult.HttpError(503, null, null))

        val result = test.platform.httpTransport.execute(HttpRequest(HttpRequest.Method.GET, "https://h/v1/config"))

        assertEquals(0L, test.platform.clock.currentTimeMillis())
        assertSame(root, test.platform.storageRoot)
        assertEquals(HttpResult.HttpError(503, null, null), result)
    }
}
