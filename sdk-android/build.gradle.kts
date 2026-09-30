plugins {
    id("rubicon.android-library")
}

android {
    // Package of the generated R class only; the public API lives in com.rubicon.analytics.
    namespace = "com.rubicon.analytics.android"
}

// The interceptor tests run against OkHttp 4.12 by default and against 5.x with -PokhttpTestMajor=5
// (CI matrix, D11).
val okhttpTestVersion: String =
    when (val major = providers.gradleProperty("okhttpTestMajor").getOrElse("4")) {
        "4" -> libs.versions.okhttp.get()
        "5" -> libs.versions.okhttp5.get()
        else -> error("okhttpTestMajor must be 4 or 5, was $major")
    }

dependencies {
    api(project(":sdk-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.process)
    // Provided by the host app when it uses them; referenced only behind a class presence check
    // (Configuration 5, D13).
    compileOnly(libs.okhttp)
    compileOnly(libs.androidx.activity)

    testImplementation(testFixtures(project(":sdk-core")))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:okhttp:$okhttpTestVersion")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpTestVersion")
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
