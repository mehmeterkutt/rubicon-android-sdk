package com.rubicon.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rubicon.analytics.internal.NoOpAnalytics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

// Public API 7 and 8: every method of Analytics called from Java, and a fake written in Java.
class AnalyticsJavaTest {
    @Test
    void everyMethodIsCallableFromJava() {
        Analytics analytics = NoOpAnalytics.INSTANCE;

        analytics.track("Signed Up", Collections.emptyMap());
        analytics.screen("checkout", Map.of("step", 2));
        analytics.identify("user-42", Map.of("plan", "pro"));
        analytics.register("tenant", "acme");
        analytics.unregister("tenant");
        analytics.flush();
        analytics.optOut();
        analytics.optIn();
        analytics.clearLocalData();
        analytics.reset();

        assertTrue(analytics.isOptedOut());
        assertEquals("", analytics.getDeviceId());
    }

    @Test
    void javaCodeCanImplementAFake() {
        RecordingAnalytics fake = new RecordingAnalytics();
        Analytics analytics = fake;

        analytics.track("Signed Up", Collections.emptyMap());
        analytics.identify("user-42", Collections.emptyMap());
        analytics.reset();

        assertEquals(List.of("track Signed Up", "identify", "reset"), fake.calls);
        assertEquals("fake-device", analytics.getDeviceId());
    }

    private static final class RecordingAnalytics implements Analytics {
        final List<String> calls = new ArrayList<>();

        @Override
        public void track(@NotNull String name, @NotNull Map<String, ?> properties) {
            calls.add("track " + name);
        }

        @Override
        public void screen(@NotNull String name, @NotNull Map<String, ?> properties) {
            calls.add("screen " + name);
        }

        @Override
        public void identify(@NotNull String userId, @NotNull Map<String, ?> traits) {
            calls.add("identify");
        }

        @Override
        public void reset() {
            calls.add("reset");
        }

        @Override
        public void register(@NotNull String key, Object value) {
            calls.add("register " + key);
        }

        @Override
        public void unregister(@NotNull String key) {
            calls.add("unregister " + key);
        }

        @Override
        public void flush() {
            calls.add("flush");
        }

        @Override
        public void optOut() {
            calls.add("optOut");
        }

        @Override
        public void optIn() {
            calls.add("optIn");
        }

        @Override
        public boolean isOptedOut() {
            return false;
        }

        @Override
        public void clearLocalData() {
            calls.add("clearLocalData");
        }

        @NotNull
        @Override
        public String getDeviceId() {
            return "fake-device";
        }
    }
}
