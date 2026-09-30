package com.rubicon.build

import java.io.Serializable

/**
 * One rule of `checkSourceRules`: [pattern] must not match the code of a main source file.
 * Comments and string literals are removed before matching, so documentation may name what the rule
 * forbids. Files whose project-relative path ends with one of [allowedIn] are exempt.
 */
data class SourceRule(
    val id: String,
    val pattern: String,
    val reason: String,
    val allowedIn: List<String> = emptyList(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

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
