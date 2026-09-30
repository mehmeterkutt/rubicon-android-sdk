package com.rubicon.build

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import kotlinx.validation.ApiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** Marks declarations public only for cross-module use; see `RubiconInternal` in sdk-core. */
internal const val RUBICON_INTERNAL = "com.rubicon.analytics.RubiconInternal"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/**
 * Settings shared by `sdk-core` and `sdk-android`. Called after the Kotlin plugin is applied.
 */
internal fun Project.configureSdkModule() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("org.jetbrains.kotlinx.kover")
    pluginManager.apply("org.jetbrains.kotlinx.binary-compatibility-validator")
    pluginManager.apply(SourceRulesPlugin::class.java)

    extensions.configure<KotlinProjectExtension> {
        explicitApi()
    }

    // D5: consumers on Kotlin 1.9 and newer can read the artifacts. The language and API version
    // apply to published code only; test code may use libraries built with a newer Kotlin.
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            // SDK code uses its own internal API freely; only applications have to opt in.
            optIn.add(RUBICON_INTERNAL)
            if (!name.contains("Test")) {
                apiVersion.set(KotlinVersion.KOTLIN_2_0)
                languageVersion.set(KotlinVersion.KOTLIN_2_0)
            }
        }
    }

    // D5: the stdlib at the old version, so the SDK never upgrades the app's stdlib.
    dependencies.add("implementation", libs.findLibrary("kotlin-stdlib").get())

    // The .api files list what applications can use: @RubiconInternal declarations and the
    // internal packages (nested types included) are left out.
    extensions.configure<ApiValidationExtension> {
        nonPublicMarkers.add(RUBICON_INTERNAL)
        ignoredPackages.add("com.rubicon.analytics.internal")
    }

    extensions.configure<KtlintExtension> {
        filter {
            exclude { it.file.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
        }
    }

    tasks.named("check") {
        dependsOn("koverVerify")
    }
}
