plugins {
    `kotlin-dsl`
}

dependencies {
    // The root build puts these plugins on the classpath (`apply false`); here they are only
    // needed to compile the conventions.
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ktlint.gradle.plugin)
    compileOnly(libs.kover.gradle.plugin)
    compileOnly(libs.binary.compatibility.validator.plugin)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "rubicon.kotlin-library"
            implementationClass = "com.rubicon.build.KotlinLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "rubicon.android-library"
            implementationClass = "com.rubicon.build.AndroidLibraryConventionPlugin"
        }
        register("sourceRules") {
            id = "rubicon.source-rules"
            implementationClass = "com.rubicon.build.SourceRulesPlugin"
        }
    }
}
