pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rubicon-android-sdk"

include(":sdk-core")
include(":sdk-android")
// Never published: the app the emulator suite drives, and the suite itself (D10, steps 8.1 to 8.3).
include(":test-app")
include(":emulator-tests")
