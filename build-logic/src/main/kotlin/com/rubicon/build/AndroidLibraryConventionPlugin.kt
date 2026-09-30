package com.rubicon.build

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Conventions for an Android SDK module (`sdk-android`). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            configureSdkModule()

            extensions.configure<LibraryExtension> {
                compileSdk = libs.version("android-compileSdk").toInt()
                defaultConfig {
                    minSdk = libs.version("android-minSdk").toInt()
                    consumerProguardFiles("consumer-rules.pro")
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_1_8
                    targetCompatibility = JavaVersion.VERSION_1_8
                }
                lint {
                    abortOnError = true
                    error += "NewApi"
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                }
            }

            // Unit tests run once, on the debug variant; release differs only in R8, which unit
            // tests do not exercise (R8 is covered in step 8.2).
            extensions.configure<LibraryAndroidComponentsExtension> {
                beforeVariants(selector().withBuildType("release")) { variant ->
                    variant.enableUnitTest = false
                }
            }
        }
}
