package com.rubicon.build

/** Plain text scan of Kotlin and Java sources for [SourceRule]s. */
object SourceScanner {
    // A call of one of the SDK's log functions on its `log` instance (Logging 3).
    private val LOG_CALL = Regex("""\blog\s*\.\s*(debug|info|warn|error)\b""")

    fun findViolations(
        path: String,
        text: String,
        rules: List<SourceRule>,
    ): List<SourceRuleViolation> {
        val applicable = rules.filter { rule -> rule.allowedIn.none { path.endsWith(it) } }
        if (applicable.isEmpty()) return emptyList()
        val code = stripCommentsAndStrings(text)
        val lineStarts = lineStarts(code)
        val violations = mutableListOf<SourceRuleViolation>()
        for (rule in applicable) {
            val pattern = Regex(rule.pattern)
            val ranges =
                when (rule.scope) {
                    SourceRule.Scope.FILE -> listOf(code.indices)
                    SourceRule.Scope.LOG_CALL -> logCalls(code)
                }
            val lines =
                ranges
                    .flatMap { range -> pattern.findAll(code.substring(range)).map { range.first + it.range.first } }
                    .map { offset -> lineOf(lineStarts, offset) }
                    .distinct()
            lines.forEach { violations += SourceRuleViolation(path, it, rule) }
        }
        return violations.sortedWith(compareBy({ it.line }, { it.rule.id }))
    }

    /**
     * The extent of each log call in [code] (already stripped): the name, its arguments in
     * parentheses if any, and its trailing lambda if any.
     */
    fun logCalls(code: String): List<IntRange> =
        LOG_CALL.findAll(code).map { match ->
            var end = match.range.last + 1
            end = skipGroup(code, skipSpaces(code, end), '(', ')') ?: end
            end = skipGroup(code, skipSpaces(code, end), '{', '}') ?: end
            match.range.first until end
        }.toList()

    /**
     * Replaces comments, string literals and character literals with spaces, keeping line breaks so
     * line numbers stay correct. Template expressions inside strings (`${...}` and `$name`) are kept,
     * since they are code: `"id ${user.id}"` becomes `"    user.id "` with the quotes blanked.
     */
    fun stripCommentsAndStrings(text: String): String = Stripper(text).run()

    private class Stripper(
        private val text: String,
    ) {
        private val out = StringBuilder(text.length)
        private var i = 0

        fun run(): String {
            code(inTemplate = false)
            return out.toString()
        }

        private fun keep(count: Int = 1) {
            repeat(count) {
                if (i < text.length) out.append(text[i++])
            }
        }

        private fun blank(count: Int = 1) {
            repeat(count) {
                if (i < text.length) out.append(if (text[i++] == '\n') '\n' else ' ')
            }
        }

        // Code, either top level or inside `${...}`, which ends at its matching brace.
        private fun code(inTemplate: Boolean) {
            var depth = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') blank()
                    text.startsWith("/*", i) -> blockComment()
                    text.startsWith("\"\"\"", i) -> {
                        blank(3)
                        string(raw = true)
                    }
                    c == '"' -> {
                        blank()
                        string(raw = false)
                    }
                    c == '\'' -> charLiteral()
                    // A backticked name is code, and may contain an apostrophe.
                    c == '`' -> {
                        keep()
                        while (i < text.length && text[i] != '`' && text[i] != '\n') keep()
                        if (i < text.length && text[i] == '`') keep()
                    }
                    inTemplate && c == '{' -> {
                        depth++
                        keep()
                    }
                    inTemplate && c == '}' -> {
                        if (depth == 0) {
                            blank()
                            return
                        }
                        depth--
                        keep()
                    }
                    else -> keep()
                }
            }
        }

        private fun string(raw: Boolean) {
            while (i < text.length) {
                val c = text[i]
                when {
                    raw && text.startsWith("\"\"\"", i) -> {
                        // A raw string may end with extra quotes: """a"""" is `a"`.
                        while (text.startsWith("\"\"\"\"", i)) blank()
                        blank(3)
                        return
                    }
                    !raw && c == '"' -> {
                        blank()
                        return
                    }
                    !raw && c == '\n' -> return
                    !raw && c == '\\' -> blank(2)
                    text.startsWith("\${", i) -> {
                        blank(2)
                        code(inTemplate = true)
                    }
                    c == '$' && i + 1 < text.length && text[i + 1].isJavaIdentifierStart() -> {
                        blank()
                        while (i < text.length && text[i].isJavaIdentifierPart()) keep()
                    }
                    else -> blank()
                }
            }
        }

        private fun charLiteral() {
            blank()
            while (i < text.length && text[i] != '\'' && text[i] != '\n') {
                if (text[i] == '\\') blank(2) else blank()
            }
            if (i < text.length && text[i] == '\'') blank()
        }

        // Kotlin block comments nest.
        private fun blockComment() {
            var depth = 0
            while (i < text.length) {
                when {
                    text.startsWith("/*", i) -> {
                        depth++
                        blank(2)
                    }
                    text.startsWith("*/", i) -> {
                        depth--
                        blank(2)
                        if (depth == 0) return
                    }
                    else -> blank()
                }
            }
        }
    }

    private fun skipSpaces(
        code: String,
        from: Int,
    ): Int {
        var i = from
        while (i < code.length && code[i].isWhitespace()) i++
        return i
    }

    // If a group opened by [open] starts at [from], returns the index after its matching close.
    private fun skipGroup(
        code: String,
        from: Int,
        open: Char,
        close: Char,
    ): Int? {
        if (from >= code.length || code[from] != open) return null
        var depth = 0
        for (i in from until code.length) {
            when (code[i]) {
                open -> depth++
                close -> if (--depth == 0) return i + 1
            }
        }
        return code.length
    }

    private fun lineStarts(code: String): IntArray {
        val starts = mutableListOf(0)
        code.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun lineOf(
        starts: IntArray,
        offset: Int,
    ): Int {
        val index = starts.binarySearch(offset)
        return if (index >= 0) index + 1 else -index - 1
    }
}
