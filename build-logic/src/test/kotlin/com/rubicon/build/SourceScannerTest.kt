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

    @Nested
    inner class templates {
        @Test
        fun `keeps template expressions, which are code`() {
            assertEquals(listOf("1:no-java-time"), violations("val s = \"now ${'$'}{java.time.Instant.now()}\""))
        }

        @Test
        fun `keeps nested braces and strings inside a template`() {
            val text = "val s = \"${'$'}{map.getOrElse(\"k\") { java.time.Instant.MIN }}\""
            assertEquals(listOf("1:no-java-time"), violations(text))
        }

        @Test
        fun `keeps templates in raw strings`() {
            assertEquals(listOf("1:no-java-time"), violations("val s = \"\"\"a ${'$'}{java.time.Instant.MIN} b\"\"\""))
        }

        @Test
        fun `blanks the text around a template`() {
            val code = SourceScanner.stripCommentsAndStrings("val s = \"a ${'$'}name b\"")
            assertEquals("val s =     name   ", code)
        }
    }

    @Nested
    inner class `android Log rule` {
        @Test
        fun `flags android util Log, imported or qualified`() {
            val text = "import android.util.Log\nfun f() = android.util.Log.d(\"t\", \"m\")"
            assertEquals(listOf("1:no-android-log", "2:no-android-log"), violations(text))
        }

        @Test
        fun `allows the Logcat logger`() {
            val path = "src/main/kotlin/" + SourceRule.LOGCAT_LOGGER
            assertEquals(emptyList<String>(), violations("import android.util.Log", path))
        }
    }

    @Nested
    inner class `values in log rule` {
        @Test
        fun `flags a forbidden field in a template of a log message`() {
            assertEquals(listOf("1:no-values-in-log"), violations("log.debug { \"identified ${'$'}userId\" }"))
        }

        @Test
        fun `flags it on its own line in a multi-line call`() {
            val text =
                listOf(
                    "log.warn(Code.PROPERTY_DROPPED, e) {",
                    "    \"dropped ${'$'}key\" +",
                    "        \" of ${'$'}{event.properties.size}\"",
                    "}",
                ).joinToString("\n")
            assertEquals(listOf("3:no-values-in-log"), violations(text))
        }

        @Test
        fun `flags every forbidden field`() {
            listOf("properties", "traits", "userId", "deviceId", "apiKey", "headers", "body").forEach { field ->
                assertEquals(listOf("1:no-values-in-log"), violations("log.error(Code.X) { \"${'$'}{x.$field}\" }"), field)
            }
        }

        @Test
        fun `allows keys, types and counts, and the words in plain text`() {
            val text = "log.info { \"dropped ${'$'}key: ${'$'}{type.simpleName}, the userId is never logged\" }"
            assertEquals(emptyList<String>(), violations(text))
        }

        @Test
        fun `ignores the fields outside log calls`() {
            val text = "val count = event.properties.size\nlog.debug { \"count ${'$'}count\" }\nsend(userId)"
            assertEquals(emptyList<String>(), violations(text))
        }

        @Test
        fun `ignores other receivers`() {
            assertEquals(emptyList<String>(), violations("logger.debug { userId }"))
        }
    }

    @Test
    fun `a rule does not apply to its allowed files`() {
        val rule = SourceRule("r", "println", "reason", allowedIn = listOf("log/LogcatLogger.kt"))
        val path = "src/main/kotlin/com/x/log/LogcatLogger.kt"
        assertEquals(emptyList<SourceRuleViolation>(), SourceScanner.findViolations(path, "println()", listOf(rule)))
    }

    @Test
    fun `OkHttp is allowed under the network package only`() {
        val rules = SourceRule.DEFAULTS
        val inside = "src/main/kotlin/com/rubicon/analytics/network/RubiconNetworkInterceptor.kt"
        val outside = "src/main/kotlin/com/rubicon/analytics/internal/android/AndroidPlatform.kt"

        assertEquals(emptyList<SourceRuleViolation>(), SourceScanner.findViolations(inside, "import okhttp3.Interceptor", rules))
        assertEquals(
            listOf("okhttp-only-in-network"),
            SourceScanner.findViolations(outside, "import okhttp3.Interceptor", rules).map { it.rule.id },
        )
    }
}
