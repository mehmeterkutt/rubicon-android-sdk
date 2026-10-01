package com.rubicon.build

import java.io.Serializable

/**
 * One rule of `checkSourceRules`: [pattern] must not match the code of a main source file.
 * Comments and string literals are removed before matching, so documentation may name what the rule
 * forbids; template expressions inside strings are kept, since they are code. Files whose
 * project-relative path ends with one of [allowedIn] are exempt; an entry ending with `/` exempts
 * every file under that directory.
 */
data class SourceRule(
    val id: String,
    val pattern: String,
    val reason: String,
    val allowedIn: List<String> = emptyList(),
    val scope: Scope = Scope.FILE,
) : Serializable {
    enum class Scope {
        /** Anywhere in the file. */
        FILE,

        /** Only inside calls of the SDK's log functions: `log.warn(...) { ... }`. */
        LOG_CALL,
    }

    companion object {
        private const val serialVersionUID = 2L

        /** The file of the one class allowed to call `android.util.Log` (step 7.1). */
        const val LOGCAT_LOGGER = "com/rubicon/analytics/internal/android/LogcatLogger.kt"

        /** The one package allowed to reference OkHttp, which apps may not have (step 7.6). */
        const val NETWORK_PACKAGE = "com/rubicon/analytics/network/"

        /** The rules every SDK module is checked against. Later steps add their rules here. */
        val DEFAULTS: List<SourceRule> =
            listOf(
                SourceRule(
                    id = "no-java-time",
                    pattern = """\bjava\s*\.\s*time\b""",
                    reason = "java.time is missing below API 26 without desugaring (Serialization 2).",
                ),
                SourceRule(
                    id = "no-println",
                    pattern = """\bprintln\s*\(|\bprint\s*\(|\bSystem\s*\.\s*(out|err)\b""",
                    reason = "Log only through the internal Log functions (Logging 1 and 3).",
                ),
                SourceRule(
                    id = "no-android-log",
                    pattern = """\bandroid\s*\.\s*util\s*\.\s*Log\b""",
                    reason = "Only the Logcat logger writes to Logcat; log through the internal Log functions (Logging 1).",
                    allowedIn = listOf(LOGCAT_LOGGER),
                ),
                SourceRule(
                    id = "no-values-in-log",
                    pattern = """\b(properties|traits|userId|deviceId|apiKey|headers|body)\b""",
                    reason =
                        "Log lines never carry property or trait values, ids, the API key, headers or bodies; " +
                            "log keys, types and counts instead (Logging 4a).",
                    scope = Scope.LOG_CALL,
                ),
                SourceRule(
                    id = "okhttp-only-in-network",
                    pattern = """\bokhttp3\b""",
                    reason =
                        "OkHttp is compileOnly: only com.rubicon.analytics.network may reference it, so apps " +
                            "without OkHttp never load it (Configuration 5).",
                    allowedIn = listOf(NETWORK_PACKAGE),
                ),
            )
    }
}

/** A rule broken at [line] (1-based) of [path]. */
data class SourceRuleViolation(
    val path: String,
    val line: Int,
    val rule: SourceRule,
) {
    override fun toString(): String = "$path:$line: [${rule.id}] ${rule.reason}"
}
