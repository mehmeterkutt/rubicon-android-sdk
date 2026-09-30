package com.rubicon.analytics.internal.android

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class RobolectricSetupTest {
    @Test
    fun `an application context is available`() {
        assertNotNull(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `a test can pick the API level`() {
        assertEquals(Build.VERSION_CODES.P, Build.VERSION.SDK_INT)
    }
}
