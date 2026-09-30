package com.rubicon.analytics.testing

import com.rubicon.analytics.LogLevel
import com.rubicon.analytics.RubiconLogger

/** A logger that keeps every line it receives, for assertions. */
public class RecordingLogger : RubiconLogger {
    public class Line(
        public val level: LogLevel,
        public val message: String,
        public val throwable: Throwable?,
    ) {
        override fun toString(): String = "$level $message"
    }

    private val lock = Any()
    private val recorded = mutableListOf<Line>()

    /** The lines received so far, oldest first. */
    public val lines: List<Line>
        get() = synchronized(lock) { recorded.toList() }

    /** The messages received so far, oldest first. */
    public val messages: List<String>
        get() = lines.map { it.message }

    override fun log(
        level: LogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        synchronized(lock) { recorded += Line(level, message, throwable) }
    }
}
