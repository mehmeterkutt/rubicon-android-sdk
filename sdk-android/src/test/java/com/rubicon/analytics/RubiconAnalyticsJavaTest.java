package com.rubicon.analytics;

import static org.junit.Assert.assertNotNull;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import com.rubicon.analytics.internal.RubiconCore;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

// Public API 7 and D3: Java creates the SDK through RubiconAnalytics.create.
@RunWith(RobolectricTestRunner.class)
public class RubiconAnalyticsJavaTest {
    @After
    public void stop() {
        RubiconCore.INSTANCE.resetForTests();
    }

    @Test
    public void javaCreatesTheSdkThroughRubiconAnalyticsCreate() {
        Application app = ApplicationProvider.getApplicationContext();
        AnalyticsConfig config = new AnalyticsConfig("rk_java");
        config.setHost("http://127.0.0.1:9");
        config.getLogging().setSendDiagnostics(false);

        Analytics analytics = RubiconAnalytics.create(app, config);
        analytics.track("Opened", Collections.emptyMap());

        assertNotNull(analytics.getDeviceId());
    }
}
