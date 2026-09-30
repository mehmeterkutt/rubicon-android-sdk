# Rubicon Android SDK

Analytics SDK for Android, written in Kotlin. Work in progress: the integration guide is written in
step 8.5 of [`docs/implementation-plan.md`](docs/implementation-plan.md).

## Modules

| Module | Role |
|---|---|
| `sdk-core` | Pure Kotlin/JVM: configuration, event pipeline, storage, upload. |
| `sdk-android` | Android factory and platform. The one dependency apps add; it brings `sdk-core`. |
| `build-logic` | Gradle conventions and the `checkSourceRules` task. |

## Building

Needs JDK 17 (Gradle picks it through `gradle/gradle-daemon-jvm.properties`) and the Android SDK
(`ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
./gradlew check        # ktlint, tests, apiCheck, lint, coverage, source rules
./gradlew ktlintFormat # format before committing
./gradlew apiDump      # after a public API change; commit the .api files
```

## Branches

`master` holds releases, `develop` the next release. Work happens on `feature/<topic>` branches from
`develop`, merged by squash through a pull request.
