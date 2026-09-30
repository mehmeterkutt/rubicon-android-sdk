package com.rubicon.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// Public API 7: the logging types are usable from Java without a Java-specific surface.
class LoggingJavaTest {
    @Test
    void aLoggerIsAJavaLambda() {
        List<String> lines = new ArrayList<>();
        RubiconLogger logger = (level, message, throwable) -> lines.add(level + " " + message);
        RuntimeException cause = new RuntimeException("cause");

        logger.log(LogLevel.WARN, "careful", null);
        logger.log(LogLevel.ERROR, "failed", cause);

        assertEquals(List.of("WARN careful", "ERROR failed"), lines);
    }

    @Test
    void aLoggerIsAJavaClass() {
        class Recording implements RubiconLogger {
            Throwable last;

            @Override
            public void log(LogLevel level, String message, Throwable throwable) {
                last = throwable;
            }
        }
        Recording logger = new Recording();

        logger.log(LogLevel.INFO, "hello", null);

        assertNull(logger.last);
    }

    @Test
    void levelsAreAnOrderedJavaEnum() {
        assertSame(LogLevel.DEBUG, LogLevel.valueOf("DEBUG"));
        assertEquals(5, LogLevel.values().length);
        assertEquals(-1, Integer.signum(LogLevel.DEBUG.compareTo(LogLevel.WARN)));
        assertEquals(LogLevel.OFF, LogLevel.values()[LogLevel.values().length - 1]);
    }
}
