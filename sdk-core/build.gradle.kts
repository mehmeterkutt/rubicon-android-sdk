import com.rubicon.build.GenerateBuildInfoTask

plugins {
    id("rubicon.kotlin-library")
    `java-test-fixtures`
}

val generateBuildInfo by tasks.registering(GenerateBuildInfoTask::class) {
    version.set(project.version.toString())
    packageName.set("com.rubicon.analytics.internal")
    outputDirectory.set(layout.buildDirectory.dir("generated/source/buildInfo/main"))
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(generateBuildInfo)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Android's own org.json API; the platform provides it at runtime (Serialization 1).
    compileOnly(libs.android.json)

    testFixturesImplementation(libs.kotlin.stdlib)
    testFixturesApi(libs.kotlinx.coroutines.test)

    testImplementation(libs.android.json)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Held certificates for the TLS failure test (HTTP 4).
    testImplementation(libs.okhttp.tls)
    // Independent JSON parser for the wire contract tests (Serialization 5).
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    systemProperty("rubicon.expectedVersion", project.version.toString())
}

kover {
    reports {
        filters {
            excludes {
                // Generated from the version property.
                classes("com.rubicon.analytics.internal.BuildInfo")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
