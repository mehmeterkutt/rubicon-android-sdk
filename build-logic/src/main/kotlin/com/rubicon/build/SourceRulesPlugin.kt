package com.rubicon.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Registers `checkSourceRules` over `src/main` and wires it into `check`. */
class SourceRulesPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply(LifecycleBasePlugin::class.java)
            val task =
                tasks.register("checkSourceRules", CheckSourceRulesTask::class.java) {
                    group = LifecycleBasePlugin.VERIFICATION_GROUP
                    description = "Checks main sources against the SDK's source rules."
                    sources.from(
                        fileTree("src/main") {
                            include("**/*.kt", "**/*.java")
                        },
                    )
                    rules.set(SourceRule.DEFAULTS)
                    baseDirectory.set(layout.projectDirectory)
                    report.set(layout.buildDirectory.file("reports/source-rules/violations.txt"))
                }
            tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
                dependsOn(task)
            }
        }
}
