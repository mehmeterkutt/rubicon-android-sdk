import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

// The emulator suite (steps 8.1 to 8.3): JUnit tests on this machine that drive :test-app on a running
// emulator or device through adb, with the fake server here, reached through `adb reverse`. They need
// a device, so they are not part of `check`:
//   ./gradlew :emulator-tests:emulatorTest                 (one device connected)
//   ANDROID_SERIAL=emulator-5560 ./gradlew :emulator-tests:emulatorTest
val emulatorTest: SourceSet by sourceSets.creating

configurations[emulatorTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[emulatorTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "emulatorTestImplementation"(platform(libs.junit5.bom))
    "emulatorTestImplementation"(libs.junit5.jupiter)
    "emulatorTestRuntimeOnly"(libs.junit5.platform.launcher)
    "emulatorTestImplementation"(libs.okhttp.mockwebserver)
    "emulatorTestImplementation"(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(17)
}

// adb from ANDROID_HOME or the sdk.dir of local.properties.
val adb: String =
    run {
        val local = Properties()
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use(local::load)
        val sdk = System.getenv("ANDROID_HOME") ?: local.getProperty("sdk.dir") ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        "$sdk/platform-tools/adb"
    }

val testApp = project(":test-app")

tasks.register<Test>("emulatorTest") {
    description = "Runs the emulator suite against the connected device (ANDROID_SERIAL to choose one)."
    group = "verification"
    testClassesDirs = emulatorTest.output.classesDirs
    classpath = emulatorTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(
        ":test-app:assembleFullDebug",
        ":test-app:assembleFullV2Debug",
        ":test-app:assembleNoStartupDebug",
    )
    systemProperty("adb", adb)
    systemProperty(
        "apkDir",
        testApp.layout.buildDirectory
            .dir("outputs/apk")
            .get()
            .asFile.absolutePath,
    )
    System.getenv("ANDROID_SERIAL")?.let { systemProperty("serial", it) }
    // Always run: the result depends on the device, not on the inputs Gradle sees.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
