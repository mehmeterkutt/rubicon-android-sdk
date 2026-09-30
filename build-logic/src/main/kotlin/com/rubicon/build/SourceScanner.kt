package com.rubicon.build

/** Plain text scan of Kotlin and Java sources for [SourceRule]s. */
object SourceScanner {
    fun findViolations(
        path: String,
        text: String,
        rules: List<SourceRule>,
    ): List<SourceRuleViolation> {
        val applicable = rules.filter { rule -> rule.allowedIn.none { path.endsWith(it) } }
        if (applicable.isEmpty()) return emptyList()
        val code = stripCommentsAndStrings(text)
        val violations = mutableListOf<SourceRuleViolation>()
        code.lines().forEachIndexed { index, line ->
            for (rule in applicable) {
                if (Regex(rule.pattern).containsMatchIn(line)) {
                    violations += SourceRuleViolation(path, index + 1, rule)
                }
            }
        }
        return violations
    }

    /**
     * Replaces comments, string literals and character literals with spaces, keeping line breaks so
     * line numbers stay correct. Template expressions inside strings are removed with the string,
     * which is acceptable for the rules this scan enforces.
     */
    fun stripCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0

        fun blank(until: Int) {
            while (i < until && i < text.length) {
                out.append(if (text[i] == '\n') '\n' else ' ')
                i++
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blank(end)
                }
                text.startsWith("/*", i) -> blank(endOfBlockComment(text, i))
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    blank(end)
                }
                c == '"' || c == '\'' -> blank(endOfQuoted(text, i, c))
                // A backticked name is code, and may contain an apostrophe.
                c == '`' -> {
                    val end = text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
                    out.append(text, i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // Kotlin block comments nest.
    private fun endOfBlockComment(
        text: String,
        start: Int,
    ): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }
                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return text.length
    }

    private fun endOfQuoted(
        text: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }
}
