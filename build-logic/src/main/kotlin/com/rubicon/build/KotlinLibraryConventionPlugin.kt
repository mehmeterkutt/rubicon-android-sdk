package com.rubicon.build

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSnifferExtension

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
            configureAndroidApiCheck()
        }

    /**
     * This module runs on Android down to minSdk, but Android lint never sees it. Published code may
     * use only the Java API that Android has at minSdk:
     *
     * - compiling against the Java 8 API (`-Xjdk-release`, `--release`) rejects anything added in
     *   Java 9 or later, which `jvmTarget` alone does not;
     * - Animal Sniffer checks the compiled classes against the Android API signature at minSdk, which
     *   also rejects Java 8 API that Android added later, such as `ConcurrentHashMap.newKeySet()`
     *   (API 24). It runs with `check`.
     *
     * Test code runs on the JVM only and is not restricted.
     */
    private fun Project.configureAndroidApiCheck() {
        tasks.withType<KotlinJvmCompile>().configureEach {
            if (!name.contains("Test")) compilerOptions.freeCompilerArgs.add("-Xjdk-release=1.8")
        }
        tasks.withType<JavaCompile>().configureEach {
            if (!name.contains("Test")) options.release.set(8)
        }

        pluginManager.apply("ru.vyarus.animalsniffer")
        val minSdk = libs.version("android-minSdk")
        val signature = "com.toasttab.android:gummy-bears-api-$minSdk:${libs.version("android-api-signature")}@signature"
        dependencies.add("signature", signature)
        extensions.configure<AnimalSnifferExtension> {
            sourceSets = listOf(extensions.getByType<SourceSetContainer>().getByName("main"))
        }
    }
}
