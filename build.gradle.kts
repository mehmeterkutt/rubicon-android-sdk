plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compatibility.validator) apply false
    alias(libs.plugins.animalsniffer) apply false
    alias(libs.plugins.dokka) apply false
}

// `./gradlew check` runs `check` in every module; this adds the build-logic tests (source rules).
tasks.register("check") {
    group = "verification"
    description = "Runs the build-logic checks."
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
