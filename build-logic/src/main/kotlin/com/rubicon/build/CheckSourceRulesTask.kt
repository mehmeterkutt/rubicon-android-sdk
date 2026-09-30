package com.rubicon.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/** Fails the build when a main source file breaks one of [rules]. */
abstract class CheckSourceRulesTask : DefaultTask() {
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val rules: ListProperty<SourceRule>

    @get:Internal
    abstract val baseDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val base = baseDirectory.get().asFile
        val violations =
            sources.files
                .sortedBy { it.path }
                .flatMap { file ->
                    val path = file.relativeTo(base).invariantSeparatorsPath
                    SourceScanner.findViolations(path, file.readText(), rules.get())
                }
        val text = violations.joinToString("\n")
        report.get().asFile.writeText(text)
        if (violations.isNotEmpty()) {
            throw GradleException("Source rules broken:\n$text")
        }
    }
}
