plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// The app the emulator suite drives (D10, steps 8.1 to 8.3); never published. Flavors:
// - full: the SDK with OkHttp and the interceptor;
// - fullV2: the same app with a higher version code, for the update test;
// - noStartup: androidx Startup's provider removed from the manifest (Lifecycle 2);
// - noOkhttp: no OkHttp on the classpath at all (step 8.3).
android {
    namespace = "com.rubicon.analytics.testapp"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.rubicon.analytics.testapp"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "variant"
    productFlavors {
        create("full")
        create("fullV2") {
            versionCode = 2
            versionName = "2.0"
        }
        create("noStartup")
        create("noOkhttp")
    }

    sourceSets {
        for (flavor in listOf("full", "fullV2", "noStartup")) {
            getByName(flavor).kotlin.srcDir("src/okhttp/kotlin")
        }
    }

    buildTypes {
        release {
            // Step 8.2: the SDK's consumer rules under R8, signed with the debug key to install.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // A test fixture, not shipped: lint checks the SDK modules.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("com.rubicon.analytics.RubiconInternal")
    }
}

dependencies {
    implementation(project(":sdk-android"))
    // ComponentActivity, so the singleTop deep link goes through OnNewIntentProvider (D13).
    implementation(libs.androidx.activity)
    for (flavor in listOf("full", "fullV2", "noStartup")) {
        "${flavor}Implementation"(libs.okhttp)
    }
}
