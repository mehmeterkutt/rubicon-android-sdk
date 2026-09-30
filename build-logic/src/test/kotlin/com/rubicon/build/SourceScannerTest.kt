package com.rubicon.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SourceScannerTest {
    private fun violations(
        text: String,
        path: String = "src/main/kotlin/Foo.kt",
    ): List<String> = SourceScanner.findViolations(path, text, SourceRule.DEFAULTS).map { "${it.line}:${it.rule.id}" }

    @Nested
    inner class `java time rule` {
        @Test
        fun `flags an import`() {
            assertEquals(listOf("2:no-java-time"), violations("package a\nimport java.time.Instant\n"))
        }

        @Test
        fun `flags a fully qualified reference`() {
            assertEquals(listOf("1:no-java-time"), violations("val x = java.time.Instant.now()"))
        }

        @Test
        fun `ignores comments, KDoc and strings`() {
            val text =
                """
                // java.time.Instant
                /** Never use java.time here. /* nested java.time */ still comment java.time */
                val name = "java.time.LocalDate"
                val raw = ${"\"\"\""}java.time.${"\"\"\""}
                """.trimIndent()
            assertEquals(emptyList<String>(), violations(text))
        }

        @Test
        fun `does not match a longer identifier`() {
            assertEquals(emptyList<String>(), violations("val java = Foo(); java.timestamp()"))
        }
    }

    @Nested
    inner class `println rule` {
        @Test
        fun `flags println, print and System out and err`() {
            val text = "println(1)\nprint(2)\nSystem.out.write(3)\nSystem.err.flush()"
            assertEquals(
                listOf("1:no-println", "2:no-println", "3:no-println", "4:no-println"),
                violations(text),
            )
        }

        @Test
        fun `ignores names that only contain it`() {
            assertEquals(emptyList<String>(), violations("fun printlnLike() = blueprint(x)"))
        }
    }

    @Nested
    inner class stripping {
        @Test
        fun `keeps line numbers`() {
            val text = "/* a\nb */\nval s = \"x\"\nprintln()"
            assertEquals(listOf("4:no-println"), violations(text))
        }

        @Test
        fun `handles escaped quotes and char literals`() {
            val text = "val a = \"\\\"println(\"\nval b = '\\''\nval c = '\"'\nprintln()"
            assertEquals(listOf("4:no-println"), violations(text))
        }

        @Test
        fun `treats a backticked name with an apostrophe as code`() {
            assertEquals(listOf("1:no-println"), violations("fun `doesn't fail`() = println()"))
        }
    }

    @Test
    fun `a rule does not apply to its allowed files`() {
        val rule = SourceRule("r", "println", "reason", allowedIn = listOf("log/LogcatLogger.kt"))
        val path = "src/main/kotlin/com/x/log/LogcatLogger.kt"
        assertEquals(emptyList<SourceRuleViolation>(), SourceScanner.findViolations(path, "println()", listOf(rule)))
    }
}
