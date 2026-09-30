package com.rubicon.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceRulesPluginTest {
    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun `create project`() {
        projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        projectDir.resolve("build.gradle.kts").writeText("plugins { id(\"rubicon.source-rules\") }\n")
    }

    private fun source(
        path: String,
        text: String,
    ) {
        projectDir.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun runner() =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("check", "--stacktrace")

    @Test
    fun `check fails on a planted java time import and names the file and line`() {
        source("src/main/kotlin/com/example/Clock.kt", "package com.example\n\nimport java.time.Instant\n")

        val result = runner().buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":checkSourceRules")?.outcome)
        assertTrue(result.output.contains("src/main/kotlin/com/example/Clock.kt:3: [no-java-time]"), result.output)
    }

    @Test
    fun `check passes on clean sources`() {
        source("src/main/kotlin/com/example/Clock.kt", "package com.example\n\n// Not java.time.\nclass Clock\n")

        val result = runner().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkSourceRules")?.outcome)
    }

    @Test
    fun `test sources may use what main sources may not`() {
        source("src/test/kotlin/com/example/ClockTest.kt", "import java.time.Instant\nfun f() = println()\n")
        source("src/main/kotlin/com/example/Clock.kt", "class Clock\n")

        val result = runner().build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkSourceRules")?.outcome)
    }
}
