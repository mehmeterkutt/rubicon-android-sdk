package com.rubicon.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Public API 7: a full configuration built from Java through getters and setters.
class ConfigJavaTest {
    @Test
    void buildsAFullConfiguration() {
        RubiconLogger logger = (level, message, throwable) -> { };
        AnalyticsConfig config = new AnalyticsConfig("rk_test");

        config.setHost("https://intranet.example.com/analytics");
        config.setDebug(true);
        config.getFlush().setAt(20);
        config.getFlush().setIntervalMs(30_000L);
        config.getFlush().setImmediateEvents(Set.of("Purchase Completed"));
        config.getSession().setBackgroundTimeoutMs(600_000L);
        config.getAutocapture().setEnabled(EnumSet.of(Autocapture.APP_LIFECYCLE, Autocapture.SESSIONS));
        config.getContext().setDisabledFields(EnumSet.of(ContextField.NETWORK_CARRIER, ContextField.LOCALE));
        config.getPrivacy().setDefaultConsent(Consent.PENDING);
        config.getPrivacy().setPersistConsent(false);
        config.getLogging().setLevel(LogLevel.DEBUG);
        config.getLogging().setLogger(logger);
        config.getLogging().setSendDiagnostics(false);
        config.getNetwork().setHeaders(Map.of("X-Gateway-Token", "t"));

        assertEquals("rk_test", config.getApiKey());
        assertEquals("https://intranet.example.com/analytics", config.getHost());
        assertTrue(config.getDebug());
        assertEquals(20, config.getFlush().getAt());
        assertEquals(30_000L, config.getFlush().getIntervalMs());
        assertEquals(Set.of("Purchase Completed"), config.getFlush().getImmediateEvents());
        assertEquals(600_000L, config.getSession().getBackgroundTimeoutMs());
        assertEquals(EnumSet.of(Autocapture.APP_LIFECYCLE, Autocapture.SESSIONS), config.getAutocapture().getEnabled());
        assertEquals(EnumSet.of(ContextField.NETWORK_CARRIER, ContextField.LOCALE), config.getContext().getDisabledFields());
        assertEquals(Consent.PENDING, config.getPrivacy().getDefaultConsent());
        assertFalse(config.getPrivacy().getPersistConsent());
        assertEquals(LogLevel.DEBUG, config.getLogging().getLevel());
        assertSame(logger, config.getLogging().getLogger());
        assertFalse(config.getLogging().getSendDiagnostics());
        assertEquals(Map.of("X-Gateway-Token", "t"), config.getNetwork().getHeaders());
    }

    @Test
    void readsTheDefaultsAndTheDefaultHost() {
        AnalyticsConfig config = new AnalyticsConfig("rk_test");

        assertEquals(AnalyticsConfig.DEFAULT_HOST, config.getHost());
        assertEquals(30, config.getFlush().getAt());
        assertFalse(config.getAutocapture().getEnabled().contains(Autocapture.DEEP_LINKS));
        assertEquals(Collections.emptySet(), config.getContext().getDisabledFields());
    }
}
