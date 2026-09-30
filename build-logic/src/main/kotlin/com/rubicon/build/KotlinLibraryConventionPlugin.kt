package com.rubicon.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/** Conventions for a pure Kotlin/JVM SDK module (`sdk-core`). */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            configureSdkModule()

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
            tasks.withType<JavaCompile>().configureEach {
                // javac 17 warns that source and target 8 are obsolete; 8 is intended (D5).
                options.compilerArgs.add("-Xlint:-options")
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
        }
}
