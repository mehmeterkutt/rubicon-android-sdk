package com.rubicon.analytics.internal.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// Keeps docs/issue-codes.md and Code in step: every code is documented, once, with its level.
class IssueCodesTest {
    private val rows: List<List<String>> =
        File("../docs/issue-codes.md")
            .readLines()
            .filter { it.startsWith("| `") }
            .map { line -> line.trim('|').split('|').map { it.trim() } }

    private val documented = rows.map { it[0].trim('`') }

    @Test
    fun `every code is documented once`() {
        assertEquals(Code.entries.map { it.name }.sorted(), documented.sorted())
        assertEquals(documented.size, documented.toSet().size)
    }

    @Test
    fun `every documented level is WARN or ERROR`() {
        rows.forEach { row -> assertTrue(row[1] == "WARN" || row[1] == "ERROR", "${row[0]} has level ${row[1]}") }
    }

    @Test
    fun `the KDoc of each code states the documented level`() {
        val source = File("src/main/kotlin/com/rubicon/analytics/internal/log/Code.kt").readText()
        rows.forEach { row ->
            val code = row[0].trim('`')
            val kdoc = Regex("""/\*\* (WARN|ERROR)\. [^*]*\*/\s*$code,""").find(source)
            assertEquals(row[1], kdoc?.groupValues?.get(1), code)
        }
    }
}
