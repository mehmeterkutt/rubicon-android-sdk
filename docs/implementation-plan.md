# Rubicon Android SDK: Implementation Plan

This plan takes the Rubicon analytics SDK from an empty repository to a release candidate, one pull
request at a time. It is written for an implementing session that starts with a clean context: it
names what to read, what to build, what to test and when a step is done. It does not restate the
design; the design lives in Confluence and is binding.

## 1. Sources of truth

| Source | What it holds | How to read it |
|---|---|---|
| Confluence, space SD, page "Analytics SDK Design Decisions" (id `3112961`) and its 14 child pages | Every design decision with its reasoning. Binding for implementation. | Atlassian MCP, `cloudId` `rubiconn.atlassian.net`, `getConfluencePage` with `contentFormat: "markdown"` |
| `CLAUDE.md`, `sdk-core/CLAUDE.md`, `sdk-android/CLAUDE.md` | The day-to-day rules distilled from those pages, with pointers to the decisions. Written in step 1.2 from the former `docs/claude-md-seed.md` (in git history). | Loaded at the start of every session |
| This file | Order of work, tests, done criteria, progress | Read the Status section at the start of every session |
| `~/Projects/analytics-sdk/{posthog-android, analytics-kotlin, amplitude-kotlin, mixpanel-android, snowplow-android-tracker}` | Source of the five reference SDKs (analytics-kotlin is Segment) | Read-only, for implementation patterns. Where a reference SDK and a decision disagree, the decision wins. |
| `~/Projects/analytics-sdk/demo-app` (repo `rubicon-team/rubicon-android-demo`) | Host app and fake backend (`fake-server/server.py`) | Used in phase 9 |

Decision pages (link: `https://rubiconn.atlassian.net/wiki/spaces/SD/pages/<id>`):

| Block | Page id | Block | Page id |
|---|---|---|---|
| Public API | 3211265 | Queue with Flush Policy | 4161566 |
| Event Model | 3407873 | Disk Persistence | 4587522 |
| Configuration | 2981890 | Serialization | 4358166 |
| Identity Management | 3833857 | HTTP Client with Compression | 3964956 |
| Context Enrichment | 3899401 | Retry and Network Awareness | 4096005 |
| Session Management | 4161537 | Privacy Controls | 4325427 |
| Automatic Lifecycle Events | 4325379 | Logging | 4554779 |

In this plan "Queue 5" means decision 5 on the Queue with Flush Policy page, "Disk 2d" decision 2d
on the Disk Persistence page, and so on.

## 2. Working agreement for the implementing session

1. At the start of a session read `CLAUDE.md`, then section 14 (Status) of this file, and take the
   first unchecked step. Do not work on two steps at once.
2. One step is one branch (`feature/<topic>`, from `develop`, no step number) and one pull request. A step
   that turns out too big is split, and the split is written into this file first.
3. Before writing code for a step, read the decisions listed under "Read" in full, not only their
   summary rows. Read the reference SDK code named there when the step says so.
4. Write the tests listed under "Tests" before or together with the code. A listed test may be
   renamed or merged; it may not be skipped. If a test cannot be written, stop and say why.
5. If a decision is missing, ambiguous, contradicts another decision, or cannot be implemented as
   written (an API level, a library limitation), stop and ask the user, with the options and a
   recommendation. Never decide silently. Record the answer in section 15 and, when it changes a
   decision, tell the user which Confluence page and which `CLAUDE.md` rule need the change; update
   them after approval.
6. Done means every item of section 6 (Definition of done) holds. Then tick the step in section 14,
   fill its rows in the traceability table (section 13) and stop for review with the changes
   uncommitted. Commit, push and open the pull request only after the user approves. Never merge,
   and never push to `develop` or `master`.
7. Documents and code comments are in English, without emoji.

## 3. Open decisions to settle before step 1.1

These are not covered by the Confluence decisions and block the scaffold. Ask the user about each
in one message, with the recommendation, and record the answers in section 15.

| # | Question | Recommendation and reason |
|---|---|---|
| D1 | Repository name and remote | `rubicon-team/rubicon-android-sdk`, local `~/Projects/rubicon-android-sdk`, same branch model as the demo app (`master`, `develop`, `feature/*`, squash merge) |
| D2 | Maven group, artifact ids, Kotlin packages | Group `com.rubicon.analytics`; artifacts `sdk-core`, `sdk-android`; public package `com.rubicon.analytics` in both modules (the factory reads `Analytics(context, config)` after one import), internals under `com.rubicon.analytics.internal.*`. The group must be a namespace we can verify on Maven Central. |
| D3 | Java name of the factory | Kotlin `Analytics(context, config)` is a top-level function. With `@file:JvmName("RubiconAnalytics")` and `@JvmName("create")` Java reads `RubiconAnalytics.create(context, config)`. Otherwise Java sees `AnalyticsKt.Analytics(...)`. |
| D4 | `minSdk` | 21, as Amplitude, Mixpanel, Segment and Snowplow (PostHog: 23). Requires fallbacks the decisions already describe: `getProcessName` (28), `registerDefaultNetworkCallback` (24), `getDefaultDataSubscriptionId`/`createForSubscriptionId` (24). Confirm that the chosen `lifecycle-process` version (D6) supports it; recent AndroidX releases raised their own `minSdk`. |
| D5 | Kotlin compatibility of the artifacts | Compile with Kotlin 2.2.x but set `apiVersion` and `languageVersion` to 2.0 and `jvmTarget` 1.8, as PostHog and Mixpanel do (Mixpanel's comment: 2.0 "keeps .kotlin_module on the pre-2.1 format"). Apps on Kotlin 1.9.x and newer can then consume the SDK. Set `kotlin.stdlib.default.dependency=false` and declare `kotlin-stdlib` at the matching old version so the SDK never upgrades the app's stdlib. |
| D6 | Dependency versions shipped to customers | `kotlinx-coroutines-core` 1.9.0 (Mixpanel: "1.10.x transitively forces kotlin-stdlib 2.1.0 on consumers"; 1.9.0 is also where `limitedParallelism` became stable). `lifecycle-process`: the oldest stable version that has `ProcessLifecycleInitializer` and compiles against D5; verify `minSdk`. OkHttp `compileOnly` 4.12.0 (already decided). |
| D7 | Default SaaS host | The production base URL, for example `https://api.rubicon.example`. Needed for `DEFAULT_HOST`. |
| D8 | License and publishing | Apache-2.0 (open source SDK). Publishing to Maven Central with `com.vanniktech.maven.publish`, signed, in step 8.6. Only the scaffold's license file is needed now. |
| D9 | Version numbering | SemVer, first release `0.1.0`; while below `1.0.0` the public API may still change, `apiCheck` records every change regardless. `library.version` comes from one Gradle property, generated into a Kotlin constant. |
| D10 | In-repository test app | A `:test-app` module, never published, for instrumented tests, the R8 check and an app built without OkHttp (steps 8.1 to 8.3). The demo app stays the manual harness. |
| D11 | CI | GitHub Actions. Every pull request: `ktlintCheck`, `build`, `test`, `apiCheck`, `lint`, `koverVerify`, the OkHttp 4/5 matrix and the source rules check. Emulator suite: on pull requests that touch `sdk-android` or `test-app`, and nightly on the lowest and highest API level. Coverage gate: 90 percent line coverage in `sdk-core`, 80 percent in `sdk-android` unit tests. Check the private repository's Actions minutes. |
| D12 | How `RubiconNetworkInterceptor` reaches the instance | Privacy 5c shows `RubiconNetworkInterceptor(hosts = ..., maskPathIds = true)` without the instance, and there is no global accessor (Public API 1). Recommendation: the instance is the first constructor parameter, `RubiconNetworkInterceptor(analytics, hosts, maskPathIds = true)`; a fake or no-op instance makes it pass through. |
| D13 | `onNewIntent` deep links without an `androidx.activity` dependency | Lifecycle 3 uses `addOnNewIntentListener` (androidx.activity 1.7+), but the SDK's only dependencies are coroutines and lifecycle-process. Recommendation: `androidx.activity` as `compileOnly`, isolated like OkHttp (class presence check, used only when the activity is a `ComponentActivity`), otherwise the documented `setIntent()` advice. |

## 4. Target layout

```
rubicon-android-sdk/
  CLAUDE.md                     root rules (step 1.2)
  README.md                     integration guide (step 8.5)
  CHANGELOG.md
  LICENSE
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties
  build-logic/                  included build: shared Kotlin/Android conventions, source rules task
  sdk-core/                     kotlin("jvm"), java-test-fixtures
    CLAUDE.md
    api/sdk-core.api
    src/main/kotlin/com/rubicon/analytics/                      public API only
    src/main/kotlin/com/rubicon/analytics/internal/<area>/      implementation
    src/test/kotlin/...                                          JUnit 5
    src/test/java/...                                            Java interop tests
    src/test/resources/golden/                                   golden wire files
    src/testFixtures/kotlin/...                                  TestPlatform and fakes, shared with sdk-android
  sdk-android/                  com.android.library
    CLAUDE.md
    api/sdk-android.api
    consumer-rules.pro
    src/main/kotlin/com/rubicon/analytics/                      factory, @ScreenName
    src/main/kotlin/com/rubicon/analytics/network/              RubiconNetworkInterceptor, the only okhttp3 code
    src/main/kotlin/com/rubicon/analytics/internal/android/     platform implementations
    src/test/kotlin/...                                          Robolectric (JUnit 4)
    src/androidTest/kotlin/...                                   instrumented
  test-app/                     not published (D10)
  docs/
    implementation-plan.md      this file
    issue-codes.md              catalogue of WARN/ERROR codes (backend item)
    remote-config.md            provisional remote configuration contract
    wire-format.md              points at the golden files and describes the envelope
  .github/workflows/, .github/pull_request_template.md
```

Internal areas in `sdk-core`: `platform`, `log`, `util` (UUIDv7, ISO time, HTTP date), `json`,
`validation`, `config`, `event`, `storage`, `queue` (writer, uploader, triggers), `http`, `retry`,
`identity`, `session`, `context`, `privacy`, `remote`, `diagnostics`, `pipeline`.

## 5. Test strategy

Every layer below is required; the step or phase that introduces it is in brackets.

| Layer | Where | Tools | Covers |
|---|---|---|---|
| Unit | `sdk-core/src/test` | JUnit 5, MockK only where a fake is impossible, `kotlinx-coroutines-test` with `StandardTestDispatcher` and virtual time | Every component in isolation, through `TestPlatform` fakes (clock, elapsed clock, random, dispatchers, network monitor, transport, context provider, process info, file system seam) [2 onward] |
| Component integration | `sdk-core/src/test/.../integration` | Real files under JUnit `@TempDir`, real `HttpURLConnection` transport against MockWebServer, fake clock | The whole pipeline from `track` to the server, restarts (a new instance over the same directory), crash leftovers [6] |
| Wire contract | `sdk-core/src/test/resources/golden` | android-json to produce, kotlinx-serialization-json as an independent parser, structural comparison | One golden file per event type and for the batch envelope; every line the SDK writes is valid JSON to an independent parser (Serialization 5) [3, completed in 8.4] |
| Java interop | `sdk-core/src/test/java`, `sdk-android/src/test/java` | JUnit | Every public method, configuration setter, hook and callback called from Java; compiling is half the test (Public API 7) [3, 7] |
| Robolectric | `sdk-android/src/test` | Robolectric, JUnit 4 (Robolectric has no JUnit 5 runner) | Factory, process check, lifecycle observers, activity callbacks, deep links, context provider, network callback, Logcat logger [7] |
| Interceptor | `sdk-android/src/test/.../network` | Real `OkHttpClient` against MockWebServer; the test task runs twice, against OkHttp 4.12 and the latest 5.x | Event shape, masking, host filter, pass-through rules, never throws, never modifies [7] |
| Instrumented | `sdk-android/src/androidTest` with `test-app` | AndroidX Test, UiAutomator, emulator | Process kill and recovery, real offline transitions, real `ProcessLifecycleOwner`, a second process, release build with R8, an app without OkHttp [8] |
| Concurrency | `sdk-core/src/test/.../stress` | Real dispatchers, many threads, bounded runs | Thread safety of every public method, per-thread ordering, no lost or duplicated events under load, backpressure counting [6] |
| Robustness | unit and integration | Seeded random inputs (no new library), fault-injecting file system and transport | Validator and serializer never throw on arbitrary input; storage failures, full disk, truncated files and unreadable stores degrade as decided [2, 4, 6] |
| Android API | `sdk-core` main classes | Animal Sniffer with the API signature at `minSdk`, Java 8 API at compile time | No Java API that Android lacks at `minSdk` reaches `sdk-core`, which lint does not check [2.2a] |
| Static rules | Gradle `checkSourceRules` task in `build-logic` | Plain source scan | No `java.time`, no `SimpleDateFormat` in the ISO formatter, no `okhttp3` outside `network`, no `android.util.Log` or `println` outside the Logcat logger, no `GlobalScope`, no `synchronized(this)`, no `Dispatchers.Main` in core; log calls never interpolate `properties`, `traits`, `userId`, `deviceId`, `apiKey`, `headers` or `body` (Logging 4a) [1, extended as areas arrive] |
| API | `apiCheck`, explicit API mode | binary-compatibility-validator, `kotlin { explicitApi() }` | Every public change is visible in the `.api` diff; nothing is public by accident [1] |
| Lint | `sdk-android` | Android lint, `NewApi` as error, `abortOnError` | API level mistakes on `minSdk` [1] |
| Coverage | both modules | Kover | Gate on `koverVerify` at the thresholds of D11; exclusions only for generated code, listed in the build file [1] |
| Manual | demo app with `fake-server` | Checklist in step 9.3 | What a person must see once before a release [9] |

Rules that apply to every test:

- Names are backtick sentences; `sdk-core` groups them with `@Nested inner class`.
- No `Thread.sleep` and no real waiting in unit tests; time is virtual. Integration and stress tests
  may wait, with a timeout, on a condition.
- Every test that touches files uses its own temporary directory. No test depends on another's order.
- Every drop reason and every issue code has at least one test that produces it and asserts the
  counter (a test enumerates `DropReason` and `Code` and fails when one has no producing test).
- Every `debug = true` exception has a test, and the same input with `debug = false` has a test that
  asserts the log line, the code and that nothing is thrown.
- Every public entry point has a test that injects a failure below it and asserts the host is not
  crashed (Non-negotiable 1).

## 6. Definition of done (every step)

- Every test listed for the step exists and passes; `./gradlew check` (which runs `ktlintCheck`,
  `test`, `apiCheck`, `lint`, `koverVerify` and `checkSourceRules`) is green locally and in CI.
- A public API change has its `apiDump` diff committed, KDoc on every new public symbol, a Java
  interop test, and is named at the top of the pull request.
- No dependency beyond those in step 1.1; a test-only dependency is named in the pull request.
- Every new WARN or ERROR has a `Code` listed in `docs/issue-codes.md`; every new way to lose an
  event uses a `DropReason` and is counted.
- No value, id, key, header, body or query string reaches a log line (Logging 4a).
- `CLAUDE.md` of the affected module updated if a rule was added, changed or clarified; this file
  updated: status ticked, traceability rows filled, decisions log extended.
- No `TODO` without the step that resolves it.
- The pull request follows the template: problem, change, how it was tested, and the decisions it
  implements (for example "Queue 4, Disk 1").

## 7. Phase 1: Repository and tooling

### 1.1 Scaffold

- Read: `claude-md-seed.md` sections Modules, Commands, Dependencies, Testing, Code style; section 3
  answers.
- Build:
  - Gradle wrapper (same Gradle as the demo app, 8.14.3), `libs.versions.toml`, `build-logic`
    conventions for Kotlin JVM and Android library modules (D4, D5, D6).
  - `sdk-core` (`kotlin("jvm")`, `java-test-fixtures`) and `sdk-android` (Android library, depends on
    `sdk-core` with `api`, `consumer-rules.pro` with `-dontwarn okhttp3.**`).
  - `explicitApi()` in both modules; `@RubiconInternal` (`@RequiresOptIn`) in `sdk-core`.
  - Plugins: ktlint (`ktlintFormat`, `ktlintCheck`), binary-compatibility-validator (`apiDump`,
    `apiCheck`), Kover, Android lint with `NewApi` as error.
  - Dependencies exactly as decided: runtime `kotlinx-coroutines-core` (both), `lifecycle-process`
    (`sdk-android`, `implementation`), `compileOnly` `com.vaadin.external.google:android-json` in
    `sdk-core` (same API surface as Android's `org.json`; never `org.json:json`), `compileOnly`
    OkHttp 4.12 in `sdk-android`. Test dependencies as in section 5.
  - `checkSourceRules` task wired into `check`, starting with the `java.time` and `println` rules.
  - Generated `BuildInfo.VERSION` constant from the version property (D9).
  - CI workflow (D11), pull request template, `README.md` stub, `CHANGELOG.md`, `.gitignore`.
    `LICENSE` waits for D8.
- Tests: one trivial test per module so the test tasks, JUnit 5 and Robolectric runners, Kover and CI
  are proven; a test that `checkSourceRules` fails on a planted `java.time` import (task test using
  a temporary source set or Gradle TestKit).
- Done when: `./gradlew build ktlintCheck apiCheck lint koverVerify checkSourceRules` is green locally
  and in CI; the dependency report (`./gradlew :sdk-android:dependencies --configuration
  releaseRuntimeClasspath`) shows only stdlib, coroutines and lifecycle-process with their
  transitive dependencies, at the versions of D5 and D6.

### 1.2 CLAUDE.md files

- Read: `docs/claude-md-seed.md` in full.
- Build: split it into three files, each rule stated once, the reasoning left to Confluence with a
  pointer (page and decision number):
  - root `CLAUDE.md` (target 150 lines at most): modules, commands, non-negotiables, public API rules,
    dependencies, threading, testing, code style, git and pull requests, how to use this plan;
  - `sdk-core/CLAUDE.md`: event model, configuration, identity, session state, queue, storage,
    serialization, HTTP, retry, privacy, logging and diagnostics;
  - `sdk-android/CLAUDE.md`: factory and process check, lifecycle, screens, deep links, context
    collection, network monitor, HTTP call capture, permissions, Robolectric conventions.
  Remove everything about `:app` and `RubiconAnalytics`; the demo app is a separate repository.
  Replace the seed's commands with the ones of step 1.1. Delete `docs/claude-md-seed.md`.
- Tests: none; the reviewer checks that no rule of the seed was lost (list any rule deliberately
  dropped in the pull request).
- Done when: the three files together cover every rule of the seed, and each is below 200 lines.

## 8. Phase 2: Core foundations (no pipeline yet)

### 2.1 Platform and test fixtures

- Read: Configuration 5 (internal tier), Public API 6, Queue 1, Disk 6.
- Build: internal `Platform` with `Clock` (wall, epoch ms), `ElapsedClock` (monotonic), `Random`,
  `Dispatchers` (writer, uploader, watchdog), `NetworkMonitor`, `HttpTransport`, `ContextProvider`,
  `ProcessInfo`, `LifecycleSource`, `EventStore` and `KeyValueStore` (`@RubiconInternal`, Disk 6),
  the storage root and a thin `FileSystem` seam used by the file implementations (for fault injection
  only; production is plain `java.io`). `testFixtures`: `TestPlatform` builder, `FakeClock`,
  `FakeElapsedClock`, `SeededRandom`, `InMemoryEventStore`, `InMemoryKeyValueStore`,
  `FakeNetworkMonitor`, `FakeTransport` (scripted results, recorded requests), `FaultyFileSystem`,
  `FakeLifecycleSource`, `FakeContextProvider`.
- Tests: fakes behave as documented (a scripted transport returns results in order; the faulty file
  system fails the configured operation once or always).
- Done when: `sdk-android` test code can use `TestPlatform` through `testFixtures(project(":sdk-core"))`.

### 2.2 Logging infrastructure

- Read: Logging 1 to 4, `sdk-core/CLAUDE.md` "Logging and diagnostics", root `CLAUDE.md` "Code style".
- Build: public `LogLevel`, `RubiconLogger`; internal `Log` with `inline` `debug`, `info`,
  `warn(code)`, `error(code, throwable)`; `Code` enum with every WARN and ERROR code the decisions
  name (start `docs/issue-codes.md` with the same list, one line of meaning each); once-per-process
  repeat limit per code with later occurrences at DEBUG; issue counter hook (counters are stored in
  step 4.1); `CallbackGuard` for the developer's logger and observer (throwing logger falls back to
  the platform default logger for the process, throwing observer switched off, re-entrant calls on
  the same thread ignored). Core default logger is a no-op.
- Tests: level gating does not run the lambda (counting lambda); `OFF` logs nothing; repeat limit per
  code, second occurrence at DEBUG, counter still incremented; a throwing logger falls back once and
  logs the fallback line once; re-entrancy from inside the logger is ignored and does not overflow
  the stack; a throwing observer is disabled after one ERROR; log calls from several threads
  concurrently.
- Done when: every later step can log only through these functions.

### 2.2a Android API check for `sdk-core`

Added during step 2.2 (section 15): `sdk-core` runs on Android down to `minSdk`, but Android lint
never sees it, and `jvmTarget` does not restrict the Java API.

- Build: `sdk-core` main code compiles against the Java 8 API (`-Xjdk-release=1.8`, `--release 8`);
  Animal Sniffer checks its classes against the Android API signature at `minSdk`
  (`com.toasttab.android:gummy-bears-api-21`, without desugaring) in `check`. Build-only; nothing is
  added to the artifacts. Test code is not restricted.
- Tests: a planted `ConcurrentHashMap.newKeySet()` (API 24) fails `animalsnifferMain` with file and
  line; a planted `java.util.List.of` (Java 9) fails compilation.

### 2.3 Identifiers and time

- Read: Event Model 4 and 5, Serialization 4, Retry 3 (`Retry-After` as HTTP date).
- Build: monotonic UUIDv7 generator (one per instance, seeded from `Platform.random` and
  `Platform.clock`, strictly increasing within a millisecond and across a clock that moves back); ISO
  8601 UTC formatter `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` from epoch ms by calendar arithmetic; HTTP date
  parser for `Retry-After` (IMF-fixdate; the two obsolete RFC 9110 formats are ignored, which makes
  the header count as absent: confirm with the user when implementing); SHA-256 hex helper for the
  key hash (`MessageDigest`).
- Tests: UUIDv7 version and variant bits, timestamp field equals the clock, 10 000 ids in one
  millisecond are unique and ordered (RFC 9562 counter method), ordering holds when the clock moves
  back, parsed by `java.util.UUID`; the ISO formatter (years 0000 to 9999 only) against a table (epoch
  0, leap day 2024-02-29, 2100-03-01, year boundaries, milliseconds 0 and 999, dates before 1970, the
  first and last supported instant) and against `java.time` in the test only for 100 000 seeded random instants (tests may use `java.time`);
  HTTP date examples from RFC 9110 and malformed inputs.

### 2.4 JSON layer

- Read: Serialization 1 and 4, Disk 3.
- Build: the only code that touches `org.json`: build a compact line from normalized values (write
  `JSONObject.NULL` for null), read stores with `has()` checks, never trust `opt*` defaults. Document
  the known behaviours in KDoc: a Kotlin null removes a key, `50.0` becomes `50`, `/` is escaped,
  large numbers may use exponent form (`1.0E21`), NaN and Infinity must never reach `org.json`.
  Events are never parsed back into objects; reading exists only for the three store files and for
  the corruption and age check of stored lines (Disk 3).
- Tests (on android-json): `{"coupon":null}` is written as such; each known behaviour pinned by a
  test so a change of runtime would be noticed; round trip of every normalized type; output parsed by kotlinx-serialization-json; non-ASCII
  and escaped characters; a 32 KB line; reading a store file with missing, extra and wrongly typed keys.

### 2.5 Property validation and normalization

- Read: Public API 3, Serialization 2 and 3, Event Model 3, Context (super properties rule).
- Build: one-pass validator and normalizer producing an immutable deep copy: the conversion table,
  `java.time` rejected with the hint, NaN and Infinity dropped, invalid keys dropped, 1..255 names and
  keys never trimmed, strings truncated at 1024 on a code point boundary without a marker, 256
  properties with super properties dropped first, depth 3 (which also stops self-referencing maps),
  unpaired surrogates to U+FFFD, a bad element of a collection drops only that element, `java.time`
  detected by class name without referencing the classes, the exact hint message of Serialization 2,
  truncation logged once per key and never thrown. Result carries the list of problems (key and type only) so the caller logs or throws according to `debug`.
- Tests: one test per row of the conversion table and per limit, exactly at, below and above each
  bound; `debug = true` throws the documented exception, `debug = false` drops the key and logs the
  documented code; the caller's map mutated after the call does not change the event; nested
  collections are copied deeply; seeded random maps (nested, mixed types, hostile strings) never make
  the validator throw in release mode and always produce a JSON-serializable result.

## 9. Phases 3 to 6: Core

### 3.1 Configuration

- Read: Configuration 1 to 6, Queue 3 and 7, Session (timeout bounds), Lifecycle (`Autocapture`
  values), Context (`ContextField` values), Privacy 2 and 4, Logging (configuration table).
- Build: `AnalyticsConfig(apiKey)` with `host`, `debug` and the groups `flush`,
  `session`, `autocapture`, `context`, `privacy`, `logging`, `network` (no `remote` group, section 15); enums `Autocapture`,
  `ContextField`, `Consent`; freezing property delegate; validator (bounds, clamping, debug
  exceptions, host normalization with optional path, reserved headers); immutable internal
  `ConfigSnapshot`; `DEFAULT_HOST` (D7). KDoc with bounds on every numeric setting. `beforeSend` and
  `logging.eventObserver` come in 3.2 with their types (split in step 3.1, section 15).
- Tests: defaults of every setting; every bound at, below and above in both modes; blank key and
  invalid host in both modes (release returns the no-op signal, not an exception); host with and
  without trailing slash and path; `http://` warns in release; reserved header override throws in
  debug and is ignored with WARN in release; after freezing, every setter throws in debug and is
  ignored with one WARN in release; the snapshot does not change when the config object is changed
  afterwards; Java test builds a full configuration through getters and setters.

### 3.2 Event model and public types

- Read: Event Model (all), Public API 4 and 5, Logging 4c, Lifecycle (`source`).
- Build: `sealed class Event` with `TrackEvent`, `ScreenEvent`, `IdentifyEvent`; `EventContext`;
  `AnalyticsConfig.beforeSend` and `logging.eventObserver` (moved here from 3.1);
  `EventSource`; internal constructors; limited `copy` with `@JvmOverloads`; `toString` with type,
  name and message id only; `BeforeSend`; `EventObserver`, `EventUpdate` (`Queued`, `Sent`,
  `Dropped`); `DropReason` with the ten reasons. Wire mapping of each type (snake_case keys, epoch ms
  times) through the JSON layer.
- Tests: `copy` changes only name and properties or traits and re-validation happens later (step
  6.3); hooks cannot reach the read-only fields; `toString` never contains properties or ids other
  than `message_id`; first golden files: one track, one screen, one identify line, compared
  structurally and parsed by the independent parser.

### 3.3 The `Analytics` interface

- Read: Public API 1, 2, 7, 8 and 9.
- Build: `Analytics` with the full vocabulary and `deviceId`, KDoc with the threading contract;
  internal `NoOpAnalytics`. First `apiDump`.
- Tests: `NoOpAnalytics` accepts every call from any thread and never throws; Java test calls every
  method.
- Done when: the `.api` files contain exactly the public types decided so far and nothing else.

### 4.1 Key-value stores

- Read: Identity 1 and 5, Disk 2 and 7, Retry 5, Logging 5 (counters), Privacy 2 and 3.
- Build: atomic JSON file store (`.tmp`, fsync, rename, `"v"`, coalesced writes on the writer, a
  leftover store `.tmp` deleted at startup, memory is the source of truth),
  missing versus unreadable (corrupt, Direct Boot, newer `v`): unreadable means in-memory values for
  the process and the file is never overwritten; `PersonStore` and `DeviceStore` with their decided
  keys, in the shapes of Disk 7: `person.json` `{"v":1,"user_id",
  "super_properties":{},"session":{"id","started_at","last_activity"}}`, `device.json` `{"v":1,
  "device_id","consent","last_version","last_build","retry":{...},"diagnostics":{...}}`, with the
  retry object of Retry 5 and the consent states "no decision", `granted`, `denied` (Privacy 2);
  `remote-config.json` belongs to `DeviceStore`. Missing `device.json`: generate and write; missing
  `person.json`: anonymous and write.
- Tests: exact key set of each store (the test named in Identity); missing file creates; corrupt file,
  newer `v` and unreadable file keep in-memory values and leave the file byte-identical; a crash
  between write and rename (simulated with the faulty file system) leaves the old file valid; many
  updates in a row produce one write; file permissions are app-private.

### 4.2 Event store

- Read: Disk 1 to 6, Queue 4 and 5, Serialization 3.
- Build: segmented append log under `events/v1/{keyHash}/`: open segment `{index}.tmp` with a
  fixed-width index computed from the directory, one `write()` per line, close at 100 events, 512 KB
  or flush (fsync, rename), closed segments immutable, one segment is one batch; deletion after
  acknowledgement; startup: delete other key directories (`api_key_changed`), recover a leftover
  `.tmp` (keep whole lines, drop a half line as `corrupt`), delete unknown files with WARN, expire
  events older than 3 days, enforce 1000 events by trimming the oldest to 900 (`queue_full`); corrupt
  lines (half line, invalid JSON, not an object, or missing `type`, `message_id` or numeric
  `timestamp`) skipped with ERROR naming segment, line number and `message_id` only; expired lines of
  a partly expired segment skipped when building the body; pending count from counting lines at
  startup, then kept in memory; a write error counts `storage_error`, logs ERROR (`STORAGE_WRITE_FAILED`, Disk 4) and the next event
  goes to a new segment (Direct Boot is not supported and ends here, Disk 2a); an unreadable segment
  retried and deleted after 3 consecutive failures (`UNREADABLE_SEGMENT`); streaming reads only.
- Tests: every rule above; a segment never exceeds 100 events or 512 KB; index ordering survives
  restart and gaps; the batch body produced from a segment is byte-identical to the lines written;
  expiry at exactly 3 days; trimming counts dropped events per reason; a write failure counts
  `storage_error` and the SDK keeps running; 10 000 events written and read back in order; a
  seeded-random truncation of a `.tmp` at every byte offset of its last line recovers correctly.

### 5.1 HTTP transport

- Read: HTTP 1 to 6, Configuration 5, Retry 1.
- Build: `HttpURLConnectionTransport` behind `HttpTransport`: POST batch and GET config; gzip in
  memory with `Content-Length` via `setFixedLengthStreamingMode`; `Authorization: Bearer`,
  `Content-Type`, `Content-Encoding`, User-Agent `rubicon-android/{version} (Android {os}; API
  {level})` (values from `ProcessInfo`), custom `network.headers` except reserved ones; timeouts 15 s
  connect, 20 s read, 45 s watchdog on its own dispatcher calling `disconnect()`; `useCaches = false`,
  no redirects; read at most 4 KB of an error body, always drain and close; `Retry-After` in seconds or
  HTTP date; `HttpResult` (`Success(code)`, `HttpError(code, body, retryAfterMs)`,
  `NetworkFailure(kind, cause)`); compression failure sends uncompressed with WARN; `Content-Type:
  application/json; charset=utf-8`; a 3xx logs the ERROR that names the redirect target; the
  blocked-host check after `CONNECT` or `DNS` failures only (`0.0.0.0`, `127.x`, `::`, `::1`; skipped
  for `localhost` and IP hosts; WARN once); a server request id header, if any, added to the DEBUG
  line; never throws, unexpected runtime exceptions become `UNEXPECTED` with an ERROR.
- Tests (MockWebServer): request line, headers and gzip body decoded and compared; reserved headers
  cannot be overridden; `Content-Length` present and no chunking; 2xx, 3xx not followed, each 4xx and
  5xx group, 429 and 503 with seconds, HTTP date, invalid and missing `Retry-After`; slow headers
  trigger read timeout (`TIMEOUT`), a server that never finishes the body trips the 45 s watchdog
  (virtual time is impossible here: use shortened internal timeouts in tests); connection refused
  (`CONNECT`), unknown host (`DNS`), untrusted certificate with MockWebServer HTTPS and a held
  certificate (`TLS`), a resolver returning loopback (`BLOCKED`), an unexpected exception
  (`UNEXPECTED`); a 1 MB error body reads 4 KB; the transport never throws for any of these.

### 5.2 Retry policy and backoff

- Read: Retry 1 to 6, Queue 2.
- Build: pure classification function over `HttpResult` into groups A to E; backoff table with
  jitter from `Platform.random`; `Retry-After` handling (cap 1 h, values of 0 or less count as 1 s,
  forward-only jitter 1.0 to 1.1, kept across restarts); pause of 1 h plus or minus 20 percent for
  group E, then one try, with ERROR once; no network means no attempt and no step advance; waits in
  a process measured with the elapsed clock; poison-batch rule (after 5 failures try the next segment,
  drop after 10 as `retry_exhausted` while others succeed); retry state persisted in `DeviceStore` and
  restored, clamped when the clock moved back.
- Tests: a table test with one row per status code and failure kind; backoff steps and jitter bounds
  with a seeded random; reset on success; `Retry-After` never shortened and never extended past the
  cap; network regained ends only network waits; poison-batch sequence end to end with a scripted
  transport; restored state after restart; clock moved back by a day.

### 5.3 Remote configuration

- Read: Configuration 1 and 3, Lifecycle (NETWORK_REQUESTS), Logging 5 (diagnostics rate).
- Build: `docs/remote-config.md` with the provisional JSON contract (marked pending backend);
  background `GET {host}/v1/config` with ETag and `If-None-Match`; validation with the same validator
  (restrict only); saved to `remote-config.json`; applied at the next launch; runtime flags for
  `enabled = false` and `NETWORK_REQUESTS` off applied at once; `sample_rate` and
  `diagnostics_sample_rate` derived deterministically from `device_id`; `If-None-Match` with the stored
  ETag and `useCaches = false`.
- Tests: 200, 304, 404 (no remote values), 5xx, malformed JSON, unknown keys, values that try to
  widen what code allows (enable an autocapture that code disabled, lower `flush_at` or
  `flush_interval_ms` below the code value: remote may only raise them within the bounds, Queue 3,
  raise a sample rate) are ignored with WARN; a request is allowed while consent is pending;
  restart applies saved values; the kill switch stops collection and upload in the running process;
  sampling is stable for a device id and splits a population close to the rate.

### 6.1 Identity and super properties

- Read: Identity (all), Public API 9, Context (super properties).
- Build: in-memory identity with lock or volatile snapshot, persisted through the writer; `identify`
  rules (anonymous, same id with and without traits, different id clears `PersonStore`), `reset`,
  `register`, `unregister`, `deviceId`; missing versus unreadable device id; while opted out
  `identify` updates local state without an event; `reset` clears all super properties; a super
  property of `null` is stored and sent as JSON null; INFO without ids on a user switch.
- Tests: every row of the identify rules; blank and 256-character ids in both modes; never trimmed;
  `identify` then `track` on the same thread carries the new id; traits never stored; `reset` never
  renews `device_id`; unreadable device file gives a temporary id and leaves the file untouched.

### 6.2 Session

- Read: Session (all).
- Build: session manager driven by `LifecycleSource` (foreground and background), `ElapsedClock` for
  background time, wall clock for persisted last activity; `Session Started` and backdated `Session
  Ended` with `duration_ms`; 12 h maximum; background rules; process start evaluation; interaction
  with `reset` and a changed user.
- Tests: every sentence of the Session page as a scenario on virtual time: `Session Started`
  immediately before the starting event with its timestamp equal to `started_at` and no properties;
  `Session Ended` on each of its five triggers, carrying the ended session; continue within the
  timeout, end after it, background events never extend, background process start has no session,
  maximum duration, clock moved back, restart within and after the timeout, last activity written at
  most every 30 s, reset and user switch order of events.

### 6.3a Event pipeline

Split from 6.3 (too big for one pull request): the transformation of one event, without coroutines.

- Read: Public API 3 and 4, Event Model 1, 3 and 6, Context 2 to 6, Serialization 3, Logging 4c.
- Build: the event as captured on the calling thread (name or traits, normalized properties,
  identity reference, time-dependent context, `message_id`, timestamp, source); internal steps in
  order: context (static values added on the worker, `disabledFields` never read), super properties
  (the event's own key wins, Context 5), session, `beforeSend` chain (fail-closed, `before_send`;
  automatic events keep name and top-level keys, section 15, hook rules; a result that is not a
  `copy` of the input is dropped, section 15, step 6.3), second validation, serialization with the
  32 KB check (`oversize`); `SDK Diagnostics` skips the hooks; the result is a line to append or a drop
  with its reason, and `EventUpdate.Queued` or `Dropped` for the observer.
- Tests: a hook that drops, throws, renames or adds properties, one that returns an invalid
  property, and one that returns an event it built itself or of a foreign subclass (section 15, step
  3.2); on an automatic event a hook that changes values (to `null`, to another type, nested values)
  passes, and one that renames, adds or removes a top-level key or returns `null` drops it with
  `before_send` and throws in debug; `SDK Diagnostics` never reaches a hook; hooks run in list order;
  super properties merge with the event's key winning; disabled context fields are never read; an
  event over 32 KB is dropped as `oversize`; the line matches the golden files.

### 6.3b Writer

- Read: Queue 1, 2 and 7, Public API 9, Privacy 2, Disk 4, Logging 4c.
- Build: SDK scope (`SupervisorJob`, `CoroutineExceptionHandler` that logs and never rethrows in
  release); the writer's first task is loading `DeviceStore` and `PersonStore`, then `Identity.load`,
  `SessionManager.start` and `EventStore.open` (Privacy 2, section 15, step 6.1); write channel of
  1000 with `trySend` (`backpressure`), `WriteMessage` as in Queue 1; messages for events, `Flush`,
  store persistence, lifecycle and person-cleared signals for the session (section 15, step 6.2),
  consent changes, `clearLocalData`; the 6.3a pipeline per event, then append and
  `EventUpdate.Queued`; internal `FlushTrigger` with `CountTrigger` (pending on disk including
  leftovers), `IntervalTrigger` (from the first pending event, started at process start when leftovers
  exist, cancelled by any flush), `BackgroundTrigger`, `NetworkTrigger` (only while events are
  pending), `ImmediateEventTrigger` (exact, case-sensitive final name after hooks, flush after
  persisting, DEBUG line). No startup flush and no periodic timer.
- Tests: order of calls from one thread is kept; no event is created once `RuntimeFlags.collecting`
  is off (remote kill switch or outside `sample_rate`, 5.3); the observer sees `Queued` for exactly the
  persisted events; channel full counts `backpressure` without blocking the caller; every trigger
  fires once and only when its condition holds; `flush()` includes all earlier events.

### 6.4 Uploader

- Read: Queue 2 and 4, Retry (all), HTTP 2, Logging 4c.
- Build: conflated upload signal; gates (offline, backoff, opted out, consent pending, kill switch);
  sequential batches oldest first; envelope `{"sent_at":..,"batch":[...]}` streamed with `sent_at`
  rewritten per attempt; delete after 2xx; `Sent` and `Dropped` updates; stop at the first failure;
  one request in flight.
- Tests: each gate blocks and its release resumes, including `RuntimeFlags.collecting` turned off by
  a remote fetch in the running process (5.3); `sent_at` differs between attempts of the same
  batch; a batch is deleted only after 2xx; no two requests overlap under a burst of signals;
  `Sent` lists exactly the batch's message ids.

### 6.5 Privacy

- Read: Privacy (all), Identity 5.
- Build: `optOut`, `optIn`, `isOptedOut` (true while opted out or pending; the early read of
  `device.json` only before the writer has loaded it), the open session ended without an event on
  `optOut`, no opt-in event, `defaultConsent`, `persistConsent`, stored choice wins, `PENDING` write-but-never-send
  and its resolution (queue limits still apply, pending survives a restart without a stored
  decision), `clearLocalData` through the write channel with its keep and delete lists.
- Tests: every rule; startup events of an opted-out user never reach disk; from `PENDING`, `optOut`
  deletes waiting events without counting and `optIn` uploads them; `clearLocalData` keeps consent,
  version and build and remote config, renews `device_id`, counts nothing; opted out produces no
  automatic events, no session events and no `SDK Diagnostics`.

### 6.6 SDK Diagnostics

- Read: Logging 5, Queue 5.
- Build: the `SDK Diagnostics` event right before a flush when a counter is non-zero and one hour has
  passed (future last time counts as now); counters reset when it is written; its own drop not
  counted; sampling by `device_id`; `logging.sendDiagnostics`; never while opted out, pending or
  killed; never passed to `beforeSend` hooks.
- Tests: silent device sends nothing; one event per hour at most across restarts; exact property
  shape against a golden file; counters reset only after the write; opted out keeps counting and
  reports after `optIn`.

### 6.7 `DefaultAnalytics` and the core factory

- Read: Public API 1 and 9, Configuration 3 and 4.
- Build: `DefaultAnalytics` wiring every component; internal core factory `(config, platform)`; one
  instance per process guard (debug throws, release returns the existing instance); internal
  `flushBlocking(timeout)`.
- Tests: integration suite (section 5, component integration): track to server with real files and
  MockWebServer; offline then online; 503 then 200; restart with pending events; crash leftovers;
  two instances; stress suite: 16 threads times 5000 mixed calls, then `flushBlocking`, the server
  received every accepted event exactly once and per-thread order holds, backpressure counted exactly.
- Done when: the whole core runs on the JVM end to end and the `.api` diff of `sdk-core` is final
  for the first release.

## 10. Phase 7: Android

### 7.1 Factory and Android platform

- Read: Public API 2, Configuration 3, Disk 2 (2d warning), Logging 1.
- Build: `Analytics(context, config)` (D3), application context only; main process check
  (`applicationInfo.processName` versus `Application.getProcessName()` or `runningAppProcesses` below
  API 28, matched on `Process.myPid()`; `applicationInfo.processName` falls back to `packageName`;
  never `/proc` or `ActivityThread`; unknown name assumed main with WARN; other processes get a no-op
  and one INFO line);
  `AndroidPlatform` (storage under `noBackupFilesDir/rubicon/`, `SystemClock.elapsedRealtime`,
  process info, Logcat logger with tag `Rubicon`); nothing read on the main thread.
- Tests (Robolectric): main and non-main process, API levels on both sides of 28; files never in
  `SharedPreferences` or the backed-up directory; StrictMode disk-read detection around the factory
  call; a developer logger is never replaced; Java creation through the D3 name.

### 7.2 Network monitor

- Read: Retry 6, Context (permission rule).
- Build: one `registerDefaultNetworkCallback` per process, never unregistered (a request-based
  fallback below API 24 if D4 keeps `minSdk` 21), registered in `try/catch(Throwable)`; connected
  means `NET_CAPABILITY_INTERNET`, never `VALIDATED`; without `ACCESS_NETWORK_STATE` always connected,
  no callback, WARN once (`NETWORK_STATE_PERMISSION_MISSING`); on registration failure query before
  each flush, no automatic flush on network return, WARN once. The same monitor feeds context (network type, carrier) and retry.
- Tests: Robolectric shadows for connect, lose, capability change, registration throwing, permission
  removed.

### 7.3 Context provider

- Read: Context (all).
- Build: `AndroidContextProvider` with the decided fields, lazy on the worker, cached and refreshed by
  signals, carrier of the default data subscription with fallback, disabled fields never read,
  unknown values omitted.
- Tests: each field present and its source; each `ContextField` disabled is neither read nor sent;
  dual SIM reports the data SIM; configuration change refreshes screen and locale; timezone read per
  event; missing permission omits `network.type`.

### 7.4 Lifecycle, install and update

- Read: Lifecycle 1 to 4 and 6, Session, Queue 2 (ON_STOP).
- Build: `ProcessLifecycleOwner` observer added on the main thread, `AppInitializer` call;
  `Application Opened` (`from_background`), `Application Backgrounded`; install and update rules with
  `DeviceStore` and their properties (`version`, `build`, `installed_at`; `previous_version`,
  `previous_build` when known, `updated_at`, `is_downgrade` only when true), timestamped at the
  check; ON_STOP flush and fsync; each type gated by `Autocapture` and remote switch-off.
- Tests: first launch, update, downgrade, no record with differing times, reinstall; foreground and
  background sequence produces the decided events with `source = auto`; the Startup provider removed
  from the manifest still works.

### 7.5 Screens and deep links

- Read: Lifecycle 3 and 6, Privacy 5.
- Build: public `@ScreenName`; activity callbacks for automatic screen views (repeated resume of the
  same activity skipped, name rules, `name_source`); `Deep Link Opened` with scheme, host, path,
  the five `utm_*` parameters (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`)
  and referrer package or host only, never the fragment; `addOnNewIntentListener` for `singleTop`
  as settled in D13; weak-reference dedupe. `@ScreenName` kept by `consumer-rules.pro`.
- Tests: annotation and class-name rules; repeated resume; rotation; `singleTop` new intent; a
  non-`utm` query parameter never appears; `DEEP_LINKS` off by default.

### 7.6 HTTP call capture

- Read: Configuration 5, Lifecycle (`Network Request`), Privacy 5, `sdk-android/CLAUDE.md` "HTTP call
  capture".
- Build: `RubiconNetworkInterceptor` with the constructor settled in D12, in the `network` package;
  pass-through when opted out, `NETWORK_REQUESTS` off (checked per request) or the instance is not
  the SDK's own; never modifies, never throws, never captures the SDK host, headers, bodies, query or
  fragment; masks ids in paths. Test source set runs against OkHttp 4.12 and the latest 5.x.
- Tests: event shape per field; masking rules (digits, UUID, hex longer than 16); unconfigured host
  ignored; failure produces `error` as a short class name; response body still readable by the app;
  a throwing SDK path still returns the response; the interceptor identity check with a fake
  `Analytics`; both OkHttp versions green.
- Done when: no class outside `com.rubicon.analytics.network` references `okhttp3` (source rule).

## 11. Phase 8: Hardening and release readiness

### 8.1 Test app and instrumented suite

- Build: `:test-app` (D10) with a main process, a `:remote` process service, two activities, a deep
  link, an OkHttp client with the interceptor.
- Running the emulator suite in GitHub Actions is decided after development (D11); until then it
  runs locally.
- Tests (emulator, on the lowest supported API level and the latest; Serialization 5 asks for the
  `org.json` behaviour tests of step 2.4 to run here too): kill the process while writing 500
  events, restart, every accepted event arrives, and a repeat only ever carries the same
  `message_id` (at-least-once, deduplicated by the backend); real offline and online with `svc wifi` and `svc data`; real
  foreground and background with UiAutomator home press; the `:remote` process creates no files and
  sends nothing; install and update event across an `adb install -r` of a higher version code.

### 8.2 R8 and consumer rules

- Tests: `test-app` minified release variant runs the smoke subset of 8.1; no missing class warnings
  in the R8 output.

### 8.3 App without OkHttp

- Tests: a `test-app` flavor with no OkHttp on the classpath starts, tracks and uploads; no
  `NoClassDefFoundError`.

### 8.4 Wire contract complete

- Build: golden files for every automatic event (`Application Installed`, `Updated`, `Opened`,
  `Backgrounded`, `Session Started`, `Session Ended`, automatic screen, `Deep Link Opened`,
  `Network Request`, `SDK Diagnostics`) and the envelope; `docs/wire-format.md`.
- Tests: the whole golden set compared structurally; every file parsed by the independent parser.

### 8.5 Documentation

- Build: KDoc complete on every public symbol (a Dokka build with `failOnWarning`); `README.md` as the
  integration guide: dependency, creation in `Application`, `BuildConfig.DEBUG`, on-prem `host`,
  Java example, consent and privacy checklist (disable `NETWORK_CARRIER`, `LOCALE`, `TIMEZONE` for
  child-directed apps), interceptor setup, screens (an activity is tracked automatically or through
  `screen()`, never both; `@ScreenName` to name it), `eventObserver` for QA, R8 notes; `docs/issue-codes.md`
  complete.

### 8.6 Publishing (after D2 and D8 are answered)

- Build: Maven Central publishing for both artifacts with sources, KDoc jar, signing, POM metadata;
  a release workflow on tags; `CHANGELOG.md` entry.
- Tests: `publishToMavenLocal` and a throwaway project consuming both artifacts with Kotlin 1.9 and
  with Java only, both building and running a unit test.

## 12. Phase 9: Demo app and backend (in the demo repository)

### 9.1 Fake backend

- Remove the `invalid=` parameter; add `GET /v1/config` serving a JSON file per key with ETag and 304;
  add a control for redirects, TLS-less errors and slow responses where manual checks need them.

### 9.2 Demo app on the SDK

- Composite build (`includeBuild`) against the SDK checkout; replace `RubiconAnalytics` and
  `LoggingAnalytics` with `Analytics`; Diagnostics screen fed by `eventObserver`; configuration as in
  the demo app's `CLAUDE.md`.

### 9.3 Manual scenario checklist

Run once before every release and record the result in the release pull request:

1. Fresh install: `Application Installed`, `Application Opened`, `Session Started`, first screen.
2. Background and return within 5 minutes: same session; after the timeout: `Session Ended`
   backdated, new session.
3. Airplane mode, 50 events, back online: all arrive once.
4. `control?status=503&retry_after=30`: waits 30 s, then sends; `status=401`: pause, ERROR once.
5. Kill from recent apps with pending events, reopen: they arrive.
6. `optOut`, events, `optIn`: nothing from the opted-out period; `PENDING` then `optIn`: waiting
   events arrive.
7. `identify`, `reset`, another `identify`: events and sessions as decided.
8. Deep link with `utm_source` and a private parameter: only `utm_*` arrives.
9. Interceptor on a configured host with an id in the path: masked; remote switch-off stops capture
   at once.
10. Remote `enabled = false`: collection and upload stop in the running process.
11. Logcat at default level on a healthy run: silent.

## 13. Traceability

Every decision is implemented in a step and verified by at least one named test. When a step is done,
fill the Test column of its rows with the test class and test names. A row without a test blocks the
release.

| Decision | In short | Steps | Tests |
|---|---|---|---|
| Public API 1 | Developer holds one instance per process, no singleton | 6.7, 7.1 |  |
| Public API 2 | `Analytics` interface, internal implementation, factory | 3.3, 6.7, 7.1 | 3.3: `AnalyticsJavaTest.javaCodeCanImplementAFake`, `NoOpAnalyticsTest` |
| Public API 3 | `Map<String, Any?>` validated at the boundary | 2.5 | `PropertyNormalizerTest` (all) |
| Public API 4 | `beforeSend` list fixed at creation, fail-closed; automatic events keep name and keys | 3.2, 6.3a | 3.2: `EventJavaTest.aHookIsAJavaLambdaThatCopiesOrDrops`, `ConfigValidatorTest.config snapshot`; 6.3a: `EventPipelineTest` `hooks on the app's events`, `hooks on automatic events`, `results that are not a copy`, `SDK Diagnostics` |
| Public API 5 | Public methods return `Unit` | 3.3 | `NoOpAnalyticsTest.every method returns Unit except the two readers` |
| Public API 6 | `sdk-core` (JVM) and `sdk-android` | 1.1, 2.1 | Build: `sdk-core` is `kotlin("jvm")`, `sdk-android` depends on it with `api`; `BuildInfoTest`, `RobolectricSetupTest`; 2.1: `TestPlatformAccessTest` |
| Public API 7 | Java by construction, builders, `apiCheck` | 1.1, 3.1, 3.3, 7.1 | 1.1: `explicitApi()` and `apiCheck` in `check` (gates); 3.1: `ConfigJavaTest`; 3.3: `AnalyticsJavaTest.everyMethodIsCallableFromJava` |
| Public API 8 | Vocabulary and read-only `deviceId` | 3.3, 6.1, 6.5 | 3.3: `NoOpAnalyticsTest.Analytics has exactly the decided methods` |
| Public API 9 | Thread-safe, non-blocking, per-thread order | 3.3, 6.3b, 6.7 | 3.3: contract in the KDoc of `Analytics`; `NoOpAnalyticsTest.calls from many threads at once never throw`; 6.1: `IdentityTest` `ordering` (identify then snapshot, calls before load in call order); 6.3b: `WriterTest`.`events from one thread are stored in call order`, `calls made before the writer started keep their order and identity` |
| Event Model 1 | Top level, `properties`/`traits`, `context` ownership | 3.2, 6.3a | 3.2: `EventModelTest.golden files` (all); 6.3a: `EventPipelineTest`.`a track event matches the golden file`, `super properties merge, the event's own key wins` |
| Event Model 2 | Sealed `TrackEvent`, `ScreenEvent`, `IdentifyEvent`; `type` on the wire | 3.2, 8.4 | 3.2: `EventModelTest.golden files` (all) |
| Event Model 3 | Immutable normalized maps, validated twice | 2.5, 6.3a | 2.5: `PropertyNormalizerTest.deep copies` (all); 6.3a: `EventPipelineTest`.`an invalid property from a hook is dropped by the second validation` |
| Event Model 4 | `message_id` UUIDv7, never regenerated | 2.3, 6.3, 6.4 | 2.3: `UuidV7GeneratorTest` (all) |
| Event Model 5 | Epoch ms, ISO dates in properties, `sent_at` per attempt | 2.3, 6.4 | 2.3: `IsoDatesTest` (all) |
| Event Model 6 | Public classes, internal constructors, limited `copy` | 3.2, 6.3a | `EventModelTest` `limited copy`, `read-only for hooks`, `safe descriptions`; `EventJavaTest.copyHasJavaOverloads`; 6.3a: `EventPipelineTest` `results that are not a copy` |
| Configuration 1 | Code plus restrict-only remote, allowlist, next launch, two immediate flags | 5.3, 6.3, 6.4 | 5.3: `RemoteConfigTest` `validation`, `the client` (200, 304, 404, failures, kill switch, network requests, restart); `docs/remote-config.md`; 6.4: `UploaderTest` `remote configuration` (once per launch, also with uploads gated), `the kill switch blocks uploads` |
| Configuration 2 | One class, small groups, tuning internal | 3.1 | `ConfigValidatorTest.default values` (all), `ConfigJavaTest` |
| Configuration 3 | Only `apiKey` required, factory validation, one instance, main process | 3.1, 6.7, 7.1 | 3.1: `ConfigValidatorTest` `api key`, `host url`, `numeric bounds`, `custom headers` |
| Configuration 4 | Snapshot and freeze | 3.1 | `ConfigValidatorTest` `config snapshot`, `frozen config` |
| Configuration 5 | Public hatches, internal `Platform`, `HttpURLConnection`, OkHttp `compileOnly` | 2.1, 5.1, 7.6 | 2.1: `FakeEnvironmentTest.test platform` (all), `TestPlatformAccessTest`; 5.1: `HttpURLConnectionTransportTest`.`the production constructor takes its parts from the platform` |
| Configuration 6 | No platform types in configuration | 3.1 | `ConfigJavaTest` (the whole configuration compiles and runs on the JVM) |
| Identity 1 | `deviceId` and `userId` in files outside backup | 4.1, 6.1, 7.1 | 4.1: `DeviceStoreTest` `missing and unreadable`, `files left byte-identical`; `PersonStoreTest` likewise; 6.1: `IdentityTest` `device id` |
| Identity 2 | Implicit merge: both ids on every event | 3.2, 6.1 | 3.2: `EventModelTest.golden files.an anonymous user is sent as a null user id`; 6.1: `IdentityTest` (every snapshot carries both ids) |
| Identity 3 | One `identify`, set-only traits never stored | 6.1 | `IdentityTest` `identify rules`, `invalid user ids` |
| Identity 4 | Different id means person reset, then identify | 6.1, 6.2 | 6.1: `IdentityTest`.`a different id clears the person's state first` |
| Identity 5 | `reset` clears person data only, no event | 4.1, 6.1, 6.5 | 4.1: `PersonStoreTest.person json has exactly the decided keys`, `DeviceStoreTest.device json has exactly the decided keys`; 6.1: `IdentityTest`.`reset clears user and all super properties`; `remote config json has exactly the decided keys`; 6.5: `PrivacyTest`.`deletes events and the person, renews the device id, keeps consent, version and remote config` |
| Identity 6 | No alias | 3.3 | `NoOpAnalyticsTest.Analytics has exactly the decided methods` |
| Context 1 | Internal `ContextProvider`, `ContextStep`, nothing on the main thread | 2.1, 6.3, 7.3 |  |
| Context 2 | Cached and signal-refreshed, captured at call time, carrier of the data SIM | 6.3a, 7.2, 7.3 | 6.3a: `EventPipelineTest`.`static context is read once, on first need` |
| Context 3 | Twenty fields, unknown omitted (list to be revisited) | 7.3, 8.4 |  |
| Context 4 | Everything in `context`, read-only for hooks | 3.2 | `EventModelTest.read-only for hooks` (all), `EventJavaTest.contextIsReadThroughGetters` |
| Context 5 | `register`/`unregister`, validated at registration | 2.5, 6.1 | 2.5: `PropertyNormalizerTest.property count` (super properties dropped first); 6.1: `IdentityTest` `reset and super properties` |
| Context 6 | `context.disabledFields`, never read when disabled | 3.1, 7.3, 6.3a | 3.1: `ConfigValidatorTest.default values` (18 values, no `LIBRARY_*`); 6.3a: `EventPipelineTest`.`disabled fields are passed to the provider and left out of the event` |
| Session 1 | SDK computes sessions, on every event | 6.2 | `SessionManagerTest` (every event gets the session or none) |
| Session 2 | UUIDv7 id plus `started_at` | 6.2 | `SessionManagerTest`.`the first event in the foreground starts it` (id from the generator passed in) |
| Session 3 | Foreground keeps alive, background never extends | 6.2, 7.4 | 6.2: `SessionManagerTest` `starting a session`, `the background countdown` |
| Session 4 | 5 min timeout (10 s to 2 h), 12 h maximum, no timers | 3.1, 6.2 | 3.1: `ConfigValidatorTest.numeric bounds` (background timeout); 6.2: `SessionManagerTest` `maximum duration`, `a clock change while backgrounded does not count` |
| Session 5 | `Session Started`/`Ended`, backdated with `duration_ms` | 6.2, 8.4 | 6.2: `SessionManagerTest` (each of the five end triggers) |
| Session 6 | Persisted in `PersonStore`, resumed after restart | 4.1, 6.2 | 6.2: `SessionManagerTest` `across restarts`, `last activity`, `reset and a change of user` |
| Lifecycle 1 | Event inventory and properties | 7.4, 7.5, 7.6, 8.4 |  |
| Lifecycle 2 | `ProcessLifecycleOwner`, `AppInitializer` | 7.4 |  |
| Lifecycle 3 | Activity screens, `@ScreenName`, `name_source` | 7.5 |  |
| Lifecycle 4 | Install and update detection | 7.4 |  |
| Lifecycle 5 | Plain names, `source` `app`/`auto` | 3.2, 7.4 | 3.2: `EventModelTest.wire names` |
| Lifecycle 6 | `Autocapture` set and defaults, `NETWORK_REQUESTS` off at once | 3.1, 5.3, 7.4, 7.6 | 3.1: `ConfigValidatorTest.default values` |
| Queue 1 | Writer and uploader coroutines, channel 1000, `Flush` message | 6.3b, 6.4 | 6.3b: `WriterTest` (single writer, channel of 1000, backpressure, conflated upload signal); 6.4: `UploaderTest`.`no two requests overlap under a burst of signals` |
| Queue 2 | Flush triggers and suppression, no WorkManager | 6.3b, 6.4, 7.4 | 6.3b: `WriterTest` `flush triggers`; 6.4: `UploaderTest` `gates` |
| Queue 3 | 30 events or 60 s, bounds, remote only raises | 3.1, 5.3 | 3.1: `ConfigValidatorTest.numeric bounds` (flush); 5.3: `RemoteConfigTest`.`flushing can only become less frequent` |
| Queue 4 | 100 events / 512 KB batches, oldest first, sequential | 4.2, 6.4 | 4.2: `FileEventStoreTest` `segments` (closes at 100 events and before 512 KB); 6.4: `UploaderTest`.`batches go oldest first, are deleted after 2xx, and Sent lists their ids`, `it stops at the first failure` |
| Queue 5 | 1000 events, 3 days, drop counters | 4.2, 6.6 | 4.2: `FileEventStoreTest` `capacity`, `age limit` (exactly 3 days kept); 6.6: `DiagnosticsTest`.`comes right before a flush and resets the counters once written` |
| Queue 6 | No public flush policy, no pause or resume | 3.3 | `NoOpAnalyticsTest.Analytics has exactly the decided methods` |
| Queue 7 | `flush.immediateEvents` | 3.1, 6.3b | 3.1: `ConfigValidatorTest.config snapshot`; 6.3b: `WriterTest`.`an immediate event flushes after it is stored, matched exactly`, `the name that counts is the final one, after the hooks` |
| Disk 1 | Segmented append log, one segment is one batch | 4.2 | `FileEventStoreTest` `segments`, `10 000 events are read back in order` |
| Disk 2a | Everything under `noBackupFilesDir/rubicon/` | 4.1, 4.2, 7.1 | 4.1: `FileKeyValueStore` writes under `Platform.storageRoot`; `FileKeyValueStoreTest.files are app-private` |
| Disk 2b | Fixed-width index from the directory | 4.2 | `FileEventStoreTest.index order survives a restart and gaps` |
| Disk 2c | One directory per key, others deleted (`api_key_changed`) | 2.3, 4.2 | 2.3: `HttpDatesAndHashingTest.sha-256` (all); 4.2: `FileEventStoreTest` `events of another API key are deleted and counted`, `a later format directory is never touched` |
| Disk 2d | Main process only | 7.1, 8.1 |  |
| Disk 3 | JSON Lines equal to the wire event, `v1` directory | 4.2, 2.4 | 2.4: `JsonTest.reading fields` (all), `JsonTest.round trip` (a line never holds a raw line break); 4.2: `FileEventStoreTest` `the batch body` (byte for byte) |
| Disk 4 | One `write()` per event, fsync points, `.tmp` recovery | 4.2, 7.4, 8.1, 6.3b | 4.2: `FileEventStoreTest` `leftover open segment` (truncation at every byte), `failures`; 6.3b: `WriterTest`.`backgrounding flushes and syncs` |
| Disk 5 | Corrupt lines skipped, unreadable segments retried then deleted | 4.2 | `FileEventStoreTest` `corrupt lines`, `unknown files are deleted with a warning`, `an unreadable segment is retried and deleted after three consecutive failures` |
| Disk 6 | No encryption, storage not replaceable | 2.1 | 2.1: `EventStore`, `KeyValueStore` are `@RubiconInternal` (opt-in error for apps); `InMemoryStoresTest` |
| Disk 7 | Atomic person and device stores with `v` | 4.1 | `FileKeyValueStoreTest` `atomic replacement`; `PersonStoreTest` and `DeviceStoreTest` (`many updates in a row produce one write`, newer `v`) |
| Serialization 1 | `org.json` `compileOnly`, one conversion layer | 1.1, 2.4 | 1.1: `android-json` `compileOnly` in `sdk-core`, absent from the release runtime classpath; 2.4: `JsonTest` (all), `JsonOnAndroidTest` |
| Serialization 2 | Conversion table, no `java.time` | 2.5, 1.1 | 1.1: `SourceScannerTest.java time rule` (all), `SourceRulesPluginTest.check fails on a planted java time import and names the file and line`; 2.5: `PropertyNormalizerTest.conversion table` (all) |
| Serialization 3 | Size limits | 2.5, 6.3a | 2.5: `PropertyNormalizerTest` `property keys`, `string values`, `nesting depth`, `property count`, `event names`; 6.3a: `EventPipelineTest` `size limit` (exactly 32 KB kept, one byte more dropped) |
| Serialization 4 | snake_case, epoch ms, own ISO formatter | 2.3, 3.2 | 2.3: `IsoDatesTest` (all); 3.2: `EventModelTest.golden files` |
| Serialization 5 | Streamed body, golden files, independent parser | 2.4, 3.2, 6.4, 8.4 | 2.4: `JsonTest.round trip` (independent parser), `JsonOnAndroidTest` (Android's `org.json`); 3.2: golden `track.json`, `screen.json`, `identify.json` |
| HTTP 1 | `HttpURLConnection` behind `HttpTransport` | 5.1 | `HttpURLConnectionTransportTest` (all, against MockWebServer) |
| HTTP 2 | Always gzip, `Content-Length` | 5.1 | `HttpURLConnectionTransportTest` `posts the gzipped body with a fixed length`, `a failed compression sends the body uncompressed` |
| HTTP 3 | 15 s / 20 s / 45 s watchdog | 5.1 | `HttpURLConnectionTransportTest` `slow headers trip the read timeout`, `a server that never answers trips the watchdog` |
| HTTP 4 | Sealed `HttpResult`, never throws | 5.1 | `HttpURLConnectionTransportTest` `responses`, `Retry-After`, `failures` (each `FailureKind`) |
| HTTP 5 | Bearer key, User-Agent, no redirects | 5.1 | `HttpURLConnectionTransportTest` `custom headers never replace the SDK's own`, `a redirect is not followed` |
| HTTP 6 | No transport extras, blocked-host check | 5.1 | `HttpURLConnectionTransportTest` `BLOCKED, logged once`, `localhost and IP hosts are never checked`, `bodies are never logged` |
| Retry 1 | Five groups, only a bad batch deleted | 5.2 | `RetryPolicyTest` `classification` (one row per code and kind), `configuration problems` |
| Retry 2 | Backoff table with jitter, 1 h for configuration problems | 5.2, 6.4 | `RetryPolicyTest` `backoff`, `network regained`, `pause one hour plus or minus 20 percent`; 6.4: `UploaderTest`.`the network coming back ends a wait caused by the network`, `a wait blocks new attempts until it ends` |
| Retry 3 | `Retry-After` cap and floor, poison-batch rule | 2.3, 5.2, 6.4 | 2.3: `HttpDatesAndHashingTest.http dates` (all); 5.2: `RetryPolicyTest` `Retry-After`, `poison batch`; 6.4: `UploaderTest`.`a batch is kept on failure, retried after the wait with a new sent_at` |
| Retry 4 | 413 as configuration problem, server answers 200 | 5.2 | `RetryPolicyTest`.`status codes` (413 is CONFIG); success bodies are never read (5.1) |
| Retry 5 | Pipeline wait and per-segment attempts persisted, clock guard | 4.1, 5.2, 6.4 | 4.1: `DeviceStoreTest.the state and the cache read back as written` (retry object); 5.2: `RetryPolicyTest` `persistence` (restart, clock moved back by a day); 6.4: `UploaderTest`.`retry state changes are handed to the writer` |
| Retry 6 | One default-network callback, `INTERNET` without `VALIDATED` | 7.2 |  |
| Privacy 1 | `optOut` stops new events, queue still sends | 6.5, 7.6 | 6.5: `PrivacyTest` `opting out` |
| Privacy 2 | Stored choice beats default, applied in the writer | 4.1, 6.3b, 6.5 | 4.1: `DeviceStoreTest.consent is null, granted or denied`; 6.3b: `WriterTest`.`the stores are loaded and written by the writer`; 6.5: `PrivacyTest` `the starting state` (stored choice wins, `persistConsent`, early `isOptedOut`, calls before load) |
| Privacy 3 | `clearLocalData`, read-only `deviceId` | 6.5 | 6.5: `PrivacyTest` `clearing local data` |
| Privacy 4 | `Consent` `GRANTED`/`PENDING` | 3.1, 6.5 | 3.1: `ConfigValidatorTest.default values`; 6.5: `PrivacyTest` `pending consent`, `PENDING is opted out, meaning nothing is sent now` |
| Privacy 5a | No field presets, checklist in the guide | 8.5 |  |
| Privacy 5b | Deep links collect `utm_*` only | 7.5 |  |
| Privacy 5c | Network capture without headers or bodies, masked ids | 7.6 |  |
| Privacy 6 | No IP setting in the SDK | 3.1 | `ConfigValidatorTest.frozen config` (the 14 settings, none about IP) |
| Logging 1 | `RubiconLogger`, instance-owned, Logcat default | 2.2, 7.1 | 2.2: `LogTest.log lines` (all), `LoggingJavaTest` |
| Logging 2 | Levels, default `WARN`, repeat limit, no runtime setter | 2.2 | `LogTest.repeat limit` (all), `IssueCodesTest` |
| Logging 3 | Inline gating | 2.2 | `LogTest.level gating` (all) |
| Logging 4 | Content rule, isolation, `eventObserver` | 1.1, 2.2, 3.2, 6.3b, 6.4 | 1.1: `SourceScannerTest.println rule` (all); 2.2: `SourceScannerTest.values in log rule` (all), `LogTest.throwing logger`, `LogTest.event observer`, `LogTest.re-entrancy` (all); 3.2: `EventJavaTest.anObserverIsAJavaLambda`, `EventModelTest.safe descriptions`; 6.3b: `WriterTest` `the observer and drops`; 6.4: `UploaderTest` (`Sent` and `Dropped` updates) |
| Logging 5 | `SDK Diagnostics` | 4.1, 6.6, 5.3 | 4.1: `DeviceStoreTest` `diagnostics counters`; 5.3: `RemoteConfigTest` `sampling`, `diagnostics follow the code switch and their own sample rate`; 6.6: `DiagnosticsTest` (due once an hour across restarts, golden `sdk-diagnostics.json`, reset after the write, opted out keeps counting, gates) |

## 14. Status

- [x] 0 Open decisions D1 to D13 answered and recorded in section 15
- [x] 1.1 Scaffold
- [x] 1.2 CLAUDE.md files
- [x] 2.1 Platform and test fixtures
- [x] 2.2 Logging infrastructure
- [x] 2.2a Android API check for `sdk-core`
- [x] 2.3 Identifiers and time
- [x] 2.4 JSON layer
- [x] 2.5 Property validation and normalization
- [x] 3.1 Configuration
- [x] 3.2 Event model and public types
- [x] 3.3 The `Analytics` interface
- [x] 4.1 Key-value stores
- [x] 4.2 Event store
- [x] 5.1 HTTP transport
- [x] 5.2 Retry policy and backoff
- [x] 5.3 Remote configuration
- [x] 6.1 Identity and super properties
- [x] 6.2 Session
- [x] 6.3a Event pipeline
- [x] 6.3b Writer
- [x] 6.4 Uploader
- [x] 6.5 Privacy
- [x] 6.6 SDK Diagnostics
- [ ] 6.7 `DefaultAnalytics` and the core factory
- [ ] 7.1 Factory and Android platform
- [ ] 7.2 Network monitor
- [ ] 7.3 Context provider
- [ ] 7.4 Lifecycle, install and update
- [ ] 7.5 Screens and deep links
- [ ] 7.6 HTTP call capture
- [ ] 8.1 Test app and instrumented suite
- [ ] 8.2 R8 and consumer rules
- [ ] 8.3 App without OkHttp
- [ ] 8.4 Wire contract complete
- [ ] 8.5 Documentation
- [ ] 8.6 Publishing
- [ ] 9.1 Fake backend
- [ ] 9.2 Demo app on the SDK
- [ ] 9.3 Manual scenario checklist

## 15. Implementation decisions log

Decisions taken during implementation that the Confluence pages do not cover, and changes to
decisions. One row each: date, step, question, answer, pages or rules updated.

| Date | Step | Question | Answer | Updated |
|---|---|---|---|---|
| 2026-09-30 | 0 | D1 Repository | `rubicon-team/rubicon-android-sdk`, private. `master`, `develop`, `feature/*`; feature pull requests squash-merged into `develop`, merge commits allowed for `develop` to `master` releases. | - |
| 2026-09-30 | 0 | D2 Coordinates and packages | Packages as recommended: public `com.rubicon.analytics`, internals `com.rubicon.analytics.internal.*`; artifacts `sdk-core`, `sdk-android`. The Maven group is provisional (`group` in `gradle.properties`) and decided with D8 before step 8.6. | - |
| 2026-09-30 | 0 | D3 Java factory name | As recommended: `RubiconAnalytics.create(context, config)`. | - |
| 2026-09-30 | 0 | D4 `minSdk` | 21. | - |
| 2026-09-30 | 0 | D5 Kotlin compatibility | As recommended. Compiler 2.2.10; `apiVersion` and `languageVersion` 2.0 on published code only (test code may use libraries built with a newer Kotlin); `jvmTarget` 1.8; `kotlin-stdlib` 2.0.0 declared, default stdlib dependency off. | - |
| 2026-09-30 | 0 | D6 Shipped dependency versions | `kotlinx-coroutines-core` 1.9.0; `lifecycle-process` 2.4.0 (first version with `ProcessLifecycleInitializer`, `minSdk` 14, brings `startup-runtime` 1.0.0); OkHttp 4.12.0 `compileOnly`, supplied by the app at runtime. | - |
| 2026-09-30 | 0 | D7 Default host | Placeholder `https://api.rubicon.example` until the production URL is known. `host` stays a code setting; the SDK reads no configuration file. | - |
| 2026-09-30 | 0 | D8 License and publishing | Deferred. No `LICENSE` and no publishing until decided; step 8.6 waits. | - |
| 2026-09-30 | 0 | D9 Versioning | As recommended: SemVer from `0.1.0`, the `version` Gradle property generated into `BuildInfo.VERSION`. | - |
| 2026-09-30 | 0 | D10 Test app | As recommended: `:test-app`, not published (step 8.1). | - |
| 2026-09-30 | 0 | D11 CI | GitHub Actions on every pull request without an emulator: `check` and the `sdk-android` tests on OkHttp 5. Coverage gates 90 percent (`sdk-core`) and 80 percent (`sdk-android`). The emulator suite in Actions is decided after development. Organization plan: free, 2000 minutes a month. | - |
| 2026-09-30 | 0 | D12 Interceptor constructor | As recommended: `RubiconNetworkInterceptor(analytics, hosts, maskPathIds = true)`. | - |
| 2026-09-30 | 0 | D13 `onNewIntent` without `androidx.activity` | As recommended: `androidx.activity` 1.7.0 `compileOnly`, behind a class presence check; `-dontwarn androidx.activity.**` in `consumer-rules.pro`. | - |
| 2026-09-30 | 1.1 | Branch names | `feature/<topic>`, without the step number. | Working agreement 2 |
| 2026-09-30 | 1.1 | When to push | Each step is committed locally and reviewed by the user; push and pull request only after approval. | Working agreement 6 |
| 2026-09-30 | 2.1 | When to commit | The user reviews the uncommitted changes; commit, push and pull request follow the approval. | Working agreement 6, root `CLAUDE.md` Git |
| 2026-09-30 | 2.1 | `@RubiconInternal` in the `.api` files | Left out: `nonPublicMarkers` for the annotation and `ignoredPackages` for `com.rubicon.analytics.internal` (nested types carry no annotation). The `.api` files list only what applications can use; both modules ship together. SDK code opts in module-wide (`-opt-in`). | Root `CLAUDE.md` Public API |
| 2026-09-30 | 2.1 | Visibility of `HttpResult` and `FailureKind` | Public with `@RubiconInternal`, like every `Platform` type, because `Platform` is public for `sdk-android` (Configuration 5, Disk 6). | HTTP page, decision 4 (version 3) |
| 2026-09-30 | 2.2 | Issue code catalogue | The decisions name four codes; the user approved the others in `docs/issue-codes.md` and `Code`, one per WARN or ERROR situation the decisions describe. Pending backend review. | `docs/issue-codes.md` |
| 2026-09-30 | 2.2 | `HOST_BLOCKED` level: WARN (HTTP 6, Logging 2) or ERROR (Retry 1 group E) | WARN; the rest of group E stays ERROR. | Retry page, decision 1 (version 3) |
| 2026-09-30 | 2.2 | Failed write level: WARN (Disk 4) or ERROR (Logging 2, 3) | ERROR, `STORAGE_WRITE_FAILED`. | Disk page, decision 4 (version 3) |
| 2026-09-30 | 2.2 | Missing `ACCESS_NETWORK_STATE`: INFO (Retry 6) or WARN with a code (Logging 2, 5) | WARN, `NETWORK_STATE_PERMISSION_MISSING`; step 7.2 follows this. | Retry page, decision 6 (version 3) |
| 2026-09-30 | 2.2 | Corrupt lines: ERROR per line (Disk 5) against the repeat limit (Logging 2) | The first at ERROR, later ones at DEBUG with the same detail, all counted. | Disk page, decision 5 (version 3) |
| 2026-09-30 | 2.2 | `level.priority` in the Timber example of Logging 1 | Not added; `LogLevel` is exactly the enum of Logging 2. It can be added later without breaking anyone. | - |
| 2026-09-30 | 2.2 | "Once per key" (Serialization 3) against "once per process per code" (Logging 2) | `repeatKey` on `warn` and `error`: once per code by default, once per key when given. | `sdk-core/CLAUDE.md` Logging |
| 2026-09-30 | 2.2 | Level of the "custom logger threw" line | WARN, code `LOGGER_THREW` (a misconfiguration; Logging 4b gives no level). | `docs/issue-codes.md` |
| 2026-09-30 | 2.2 | What re-entrant calls are ignored | Log lines and observer updates caused on the callback's own thread (Logging 4b); the seed said "SDK calls", which the decision does not. | `sdk-core/CLAUDE.md` Logging |
| 2026-09-30 | 2.2 | Message format | A WARN or ERROR message starts with its code: `HOST_BLOCKED: ...`. | - |
| 2026-09-30 | 2.2 | The platform's default logger | `Platform.logger` (Logcat on Android, `NoOpLogger` on the JVM), also the fallback of a throwing logger. | - |
| 2026-09-30 | 2.2a | Java API that Android lacks at `minSdk` in `sdk-core` | New step 2.2a: Java 8 API at compile time and Animal Sniffer with `gummy-bears-api-21`, build-only. | Root `CLAUDE.md` Dependencies |
| 2026-09-30 | 2.3 | Obsolete HTTP date forms in `Retry-After` (RFC 850, asctime) | Not parsed; the header then counts as absent. The day name of an IMF-fixdate is not checked; a leap second counts as the next minute. Confirmed by the user. | - |
| 2026-09-30 | 2.3 | UUIDv7 counter | RFC 9562 method 1 with a 42-bit counter (`rand_a` and the top 30 bits of `rand_b`), seeded with its top bit clear each millisecond; on overflow the timestamp moves one millisecond ahead; a clock moved back keeps the last timestamp. | - |
| 2026-09-30 | 2.4 | `android-json` differs from Android's `org.json` | It does not keep key order (Android does). Nothing may depend on key order; `JsonOnAndroidTest` runs the layer on Android's own `org.json` through Robolectric, bringing part of Serialization 5's device check forward. Serialization 1 said `android-json` runs "the same implementation"; the note was added with the user's approval. | Serialization page, decision 1 (version 3) |
| 2026-09-30 | 2.4 | Trailing content after a JSON object | `org.json` ignores it (`{"a":1}}x` parses); `Json.readObject` rejects it, so such a stored line counts as corrupt. | - |
| 2026-09-30 | 2.4 | API levels Robolectric can run | 23 to 35 on Java 17: none below 23, and 36 needs Java 21 for the tests. API 21 and 22 are covered only by the emulator suite (8.1). | `sdk-android/CLAUDE.md` Tests |
| 2026-09-30 | 2.5 | Nesting depth counted how | A map or list that is a property value is level 1; containers are allowed up to level 3 (the `cart.items[0]` example of Serialization 3) and a container at level 4 is dropped, which also stops self-referencing maps and lists. | - |
| 2026-09-30 | 2.5 | Which properties go over 256 | The first 256 in iteration order are kept; when super properties merge in, those not overridden by the event fill the remaining room in their own order. | - |
| 2026-09-30 | 2.5 | Debug exception type | `IllegalArgumentException` for the first problem that throws in debug, as Configuration 3 uses; a truncation only logs, once per key. | - |
| 2026-10-01 | 3.1 | `ContextField` values (the page names 5 of 20 fields) | One value per field in the same pattern, 18 in all: `APP_NAME`, `APP_VERSION`, `APP_BUILD`, `APP_NAMESPACE`, `DEVICE_MANUFACTURER`, `DEVICE_MODEL`, `DEVICE_TYPE`, `DEVICE_IS_EMULATOR`, `OS_NAME`, `OS_VERSION`, `OS_API_LEVEL`, `SCREEN_WIDTH`, `SCREEN_HEIGHT`, `SCREEN_DENSITY`, `NETWORK_TYPE`, `NETWORK_CARRIER`, `LOCALE`, `TIMEZONE`. No `LIBRARY_*` values, since those fields cannot be disabled. Revisit in 7.3 with the field list. | - |
| 2026-10-01 | 3.1 | `remote.enabled` (the `remote` group of Configuration 2) | Removed by the user: the backend provides `/v1/config`, and a missing endpoint already means "no remote values" (Configuration 1, rule 4). There is no `remote` group. | Configuration page, decision 2 (version 9) |
| 2026-10-01 | 3.1 | `network.headers` and reserved headers | `Map<String, String>`, default empty. Reserved, in any letter case: `Authorization`, `Content-Type`, `Content-Encoding`, `Content-Length`, `User-Agent`, `Host`, `If-None-Match`, and any name starting with `X-Rubicon-` (the SDK's own `X-` headers). | `sdk-core/CLAUDE.md` Configuration |
| 2026-10-01 | 3.1 | What an invalid `host` is | Not an `http` or `https` URL with a host name, or one with a query, fragment, user info or surrounding whitespace (never trimmed). Trailing slashes are removed; `http://` warns (`CLEARTEXT_HOST`) in both modes. | `sdk-core/CLAUDE.md` Configuration |
| 2026-10-01 | 3.1 | `beforeSend` and `logging.eventObserver` need the types of 3.2 | Added in 3.2 with `BeforeSend`, `Event` and `EventObserver`; adding a property to a builder is compatible. | Steps 3.1 and 3.2 |
| 2026-10-01 | 3.1 | Surface kept to the decisions | No public constant of the `Autocapture` defaults; `toString` of the config and the snapshot never shows the API key or header values. | - |
| 2026-10-01 | 3.2 | Shape of `EventContext` | Typed groups as public classes with internal constructors: `app` (`name`, `version`, `build`, `namespace`), `device` (`manufacturer`, `model`, `type`, `isEmulator`), `os` (`name`, `version`, `apiLevel`), `screen` (`width`, `height`, `density`), `network` (`type`, `carrier`), `session` (`id`, `startedAt`, nullable group), `library` (`name`, `version`), and `locale`, `timezone`. Values are nullable and omitted on the wire when `null`; `device.type` and `network.type` are strings. | - |
| 2026-10-01 | 3.2 | `user_id` of an anonymous user | Always on the wire, `null` while anonymous; it is part of the event's identity, not a context value. | - |
| 2026-10-01 | 3.2 | An event name that is blank or over 255 characters | No new `DropReason` and no `Dropped` update: the event is never created. Debug throws; release logs `INVALID_EVENT_NAME`, counted in `SDK Diagnostics`. | - |
| 2026-10-01 | 3.2 | Wire names | `EventSource` `APP`/`AUTO` as `app`/`auto`; `DropReason` values as their lower-case names (`queue_full`). Golden files in `sdk-core/src/test/resources/golden`. | - |
| 2026-10-01 | 3.2 | Java can bypass `internal` | Kotlin compiles internal constructors as public, and the sealed `Event` has a public synthetic constructor, so Java code can build an event or subclass `Event`. Step 6.3 must not trust a hook's result: it is accepted only if it is a `TrackEvent`, `ScreenEvent` or `IdentifyEvent` whose source, message id, timestamp, user id, device id and context are those of the input. What happens otherwise is to be decided in 6.3. | Step 6.3 |
| 2026-10-01 | 3.2 | What a hook may change on an automatic event | Public API 4 said hooks run on every event. Now: on the app's events (`source = APP`) a hook may change the name, keys and values or drop the event; on automatic events (`source = AUTO`) only property values, including to `null` or another type and anything nested, with the same name and the same top-level keys and no `null` result. Context stays read-only for both. A breach drops the event (`before_send`) and throws in debug; its log code is settled in 6.3. `SDK Diagnostics` never reaches hooks. Automatic events are switched off through `autocapture.enabled`, automatic screens named with `@ScreenName`. | Public API 4, Privacy 5b (Confluence); `Event.kt`, `AnalyticsConfig.beforeSend`, root `CLAUDE.md`; steps 6.3, 8.5 |
| 2026-10-01 | 3.3 | What the no-op instance reports | `NoOpAnalytics.isOptedOut()` is `true`, since nothing is sent (the meaning Privacy 4 gives it); `deviceId` is `""`, since there is no installation id, and an erasure request with it is plainly invalid instead of hitting a real or shared id. Asked and answered by the user. | `NoOpAnalytics` KDoc |
| 2026-10-01 | 3.3 | Default arguments on `Analytics` | `track`, `screen` and `identify` default their map to `emptyMap()`; Kotlin compiles this into `Analytics$DefaultImpls` with synthetic `$default` helpers, which appear in the `.api` file. Java passes every argument (Public API 7). | - |
| 2026-10-01 | 4.1 | Shapes inside `device.json` the decisions leave open | `retry`: `paused_until`, `pause_kind` (`transient`, `throttle`, `config`), `step`, `last_failure`, `batches` (segment name to failed attempts), as Retry 5 lists them. `diagnostics`: `period_start`, `last_sent_at` (epoch ms), `dropped` (reason wire name to count) and `issues` (code to count). `consent`: `null`, `granted` or `denied`. Counts saturate at `Int.MAX_VALUE`. | `DeviceStore` |
| 2026-10-01 | 4.1 | Shape of `remote-config.json` | `{"v":1,"etag":..,"values":{..}}`; `values` is the response object as received (5.3). | `DeviceStore`, step 5.3 |
| 2026-10-01 | 4.1 | What makes a store file unreadable | Not valid UTF-8, not a JSON object, a `v` other than 1, a required field missing (`device_id`, a session's `id`, `started_at`, `last_activity`) or any field of the wrong type, including an unknown `consent` or `pause_kind`. An optional field that is absent or `null` takes its default. | `StoreFields` |
| 2026-10-01 | 4.1 | `remote-config.json` unreadable | Treated like the other stores: no cache for the process and never overwritten, so a downgrade cannot destroy it (Disk 7). | `DeviceStore` |
| 2026-10-01 | 4.1 | Counters before `DeviceStore` is loaded | The `Log` counts through `DeviceStore.issueCounter` from the start; counts made before `load` (such as `STORE_UNREADABLE` for `device.json` itself) are kept in memory and added at `load`. | `DeviceStore` |
| 2026-10-01 | 4.1 | App-private files | `FileSystem.openWrite` and `openAppend` restrict a file to its owner (`rw-------`); Android's app directories are private already. A leftover store `.tmp` is deleted on the first read of that store, which is on the writer at startup. | `FileSystem`, `FileKeyValueStore` |
| 2026-10-01 | 4.2 | Format versions (`"v"` in stores, `events/v1/`) | Formats evolve compatibly, by adding optional fields; event lines can gain fields freely since they are sent as stored. Raising a version is a last resort, decided with the user. Startup cleanup touches only `events/v1/`, so an older SDK never deletes a newer format's events after a downgrade. | `sdk-core/CLAUDE.md` Storage |
| 2026-10-01 | 4.2 | Encryption at rest | Disk 6 stands (no encryption). If it is ever needed, for example by an enterprise customer, it is discussed in detail with the user first, since it is the main change that would force a new format version. | - |
| 2026-10-01 | 4.2 | Codes for drop reasons (Queue 5 logs each reason at WARN once per process) | One code per reason without one of its own, all WARN: `QUEUE_FULL`, `EVENTS_EXPIRED`, `API_KEY_CHANGED` (used from 4.2), `BACKPRESSURE`, `EVENT_OVERSIZE` (used from 6.3). `corrupt` and `storage_error` keep `CORRUPT_LINE` and `STORAGE_WRITE_FAILED`; `before_send` has `HOOK_THREW` for a throw, and a `null` result is counted, not logged. Chosen by the user over a single `EVENTS_DROPPED`. `rejected` and `retry_exhausted` get codes the same way in 5.2. | `Code`, `docs/issue-codes.md` |
| 2026-10-01 | 4.2 | The `EventStore` interface (2.1 allowed refining it here) | `open(apiKey, clock, log, drops)` does the startup work on the writer; `writeBatch(index, sentAt, out)` streams the body into the uploader's stream and returns the message ids or `Unreadable`; `expire()` before uploads; the store counts what it drops through `DropCounter`. `Platform.eventStore` stays, so `sdk-android` builds `FileEventStore(storageRoot)` and tests pass `InMemoryEventStore`, which does not repeat the capacity, age and corruption rules. | `EventStore`, `FileEventStore` |
| 2026-10-01 | 4.2 | Trimming granularity (Disk 1: "delete the oldest segments") | Whole oldest segments are deleted until at most 900 remain, so 800 to 900 events are left; the segment the uploader is reading is never trimmed. | `FileEventStore` |
| 2026-10-01 | 4.2 | "Older than 3 days" | Strictly older: an event exactly 3 days old is kept, one millisecond older is expired. A timestamp in the future is never expired. | `FileEventStore` |
| 2026-10-01 | 4.2 | When skipped lines are counted | A segment is read again on every retry, so corrupt and expired lines skipped by its last read are counted once, when the segment is deleted; corrupt lines are logged on the first read of a segment in a process. An unreadable segment counts one `UNREADABLE_SEGMENT` issue and no dropped events (Disk 5). | `FileEventStore` |
| 2026-10-01 | 4.2 | A failed write | The lines before it stay as a closed segment (the file is cut back to them), the event counts `storage_error`, and the next event opens a new segment; a segment that cannot be closed stays `.tmp` and is recovered by the next process. | `FileEventStore` |
| 2026-10-01 | 5.1 | How the transport gets the instance's key, headers and log (2.1 allowed refining `HttpTransport` here) | `Platform.httpTransportFactory` replaces `Platform.httpTransport`: a `fun interface` that the core factory calls with `TransportSettings` (API key, validated `network.headers`, the instance's `Log`) once validation is done. `sdk-android` passes `HttpTransportFactory.DEFAULT`, tests return a fake. The reserved header check moved to `internal/http/Headers`, shared by validation and the transport. | `Platform`, `TestPlatform` |
| 2026-10-01 | 5.1 | The request id header (HTTP 4 leaves its name to the backend) | `X-Request-Id` until the backend names it; read only for the DEBUG line. | `Headers.REQUEST_ID`, backend review |
| 2026-10-01 | 5.1 | 304 | Not a redirect: the configuration request expects it with `If-None-Match` (5.3), so it is returned as `HttpError(304)` without the redirect ERROR. Uploads never send `If-None-Match`. | - |
| 2026-10-01 | 5.1 | Draining bodies | A success body and the rest of an error body are drained up to 64 KB and the stream closed; a longer body only costs connection reuse. A body that fails after the status arrived does not change the result. | - |
| 2026-10-01 | 5.1 | `Retry-After` edge cases | Whole seconds only (`1.5` and `-5` are invalid and count as absent); a number too large for milliseconds becomes `Long.MAX_VALUE`; a date in the past gives a negative value. The cap and the one-second floor are the retry layer's (Retry 3). | - |
| 2026-10-01 | 5.1 | When a host counts as blocked | Every address it resolves to is loopback or any-local; one public address means it is not blocked. | - |
| 2026-10-01 | 5.1 | JDK and Android differences in tests | On the JDK an empty error body gives no error stream (`null`), and a 401 to a streamed request withholds its body; tests accept `null` there. Android returns the body. | - |
| 2026-10-01 | 5.1 | Test-only dependency | `okhttp-tls` 4.12 for the held certificate of the TLS test. | `gradle/libs.versions.toml` |
| 2026-10-01 | 5.2 | Codes for `rejected` and `retry_exhausted` (as agreed in 4.2) | `BATCH_REJECTED` and `RETRY_EXHAUSTED`, both WARN. | `Code`, `docs/issue-codes.md` |
| 2026-10-01 | 5.2 | The poison batch rule in practice (Retry 3) | A segment with 5 or more consecutive group B failures is tried after the others, so the next batch goes first. A success of any other batch proves the server works; a proven suspect is deleted at its 10th failure. Proof lives in memory: after a restart a suspect is not deleted until another batch succeeds again. A lone failing batch is never deleted. | `RetryPolicy` |
| 2026-10-01 | 5.2 | What groups C and D do to the table | Group C with `Retry-After` neither advances the table nor counts against the batch; without it, it uses and advances the table like group B. Group D deletes at once, with no wait and the table unchanged. Group E keeps the table position. | `RetryPolicy` |
| 2026-10-01 | 5.2 | The clock guard at restore (Retry 5) | A stored wait is cut to the longest of its kind from now (10 minutes for B, 1 hour for C and E). The longest jittered waits (up to 15 minutes for B, 72 for E) are cut too after a restart; the next attempt then follows its own backoff. | `RetryPolicy` |
| 2026-10-01 | 5.2 | Who logs a configuration problem | The policy logs `UPLOAD_UNAUTHORIZED`, `UPLOAD_ENDPOINT_MISSING`, `UPLOAD_TOO_LARGE`, `UPLOAD_NOT_SUPPORTED` and `TLS_FAILED`; the transport already logs redirects, blocked hosts and unexpected errors (5.1). Every code once per process (Logging 2). | `RetryPolicy` |
| 2026-10-01 | 5.2 | Writing the retry state | The policy changes `DeviceStore` in memory; the writer persists it (6.3, 6.4), as every store write goes through the writer (Queue 1). | - |
| 2026-10-01 | 5.3 | The JSON contract (Configuration 1 leaves it to the backend) | Flat, every key optional: `enabled`, `autocapture_off` (types to switch off, so switching on cannot even be expressed), `flush_at`, `flush_interval_ms`, `sample_rate`, `diagnostics_sample_rate`. Chosen by the user; written to `docs/remote-config.md`, pending backend confirmation. | `docs/remote-config.md` |
| 2026-10-01 | 5.3 | How often the configuration is fetched | Once per launch, in the background. Chosen by the user. | `RemoteConfigClient` |
| 2026-10-01 | 5.3 | An unknown key or autocapture type | Ignored at DEBUG, so keys a newer backend adds for newer SDKs never warn in older ones. A known key with an invalid value, or one that would widen the code, is ignored with `REMOTE_CONFIG_INVALID_VALUE`, once per key. Chosen by the user. | `RemoteConfig` |
| 2026-10-01 | 5.3 | A device outside `sample_rate` | Collects nothing, like the kill switch but per device and from the next launch; the configuration is still fetched. Chosen by the user. | `RemoteConfigClient`, `RuntimeFlags` |
| 2026-10-01 | 5.3 | Reading the configuration response | `HttpResult.Success` gains optional `body` and `etag`, filled for GET only (at most 64 KB); an upload's success body is still never read (Retry 4). For a GET a body that fails to read is an `IO` failure, since the body is the result. | `HttpTransport`, `HttpURLConnectionTransport` |
| 2026-10-01 | 5.3 | What is saved | The response object as received, with its `ETag`, in `remote-config.json`; it is validated again at every launch against that launch's code, so a stricter app release is never widened by an old response. A 404 deletes it; any other failure keeps it. | `RemoteConfigClient`, `DeviceStore` |
| 2026-10-01 | 5.3 | Sampling | 52 bits of SHA-256 of a purpose salt and `device_id` give a position in `[0, 1)`; `events` and `diagnostics` use different salts so they draw independently. | `Sampling` |
| 2026-10-01 | 5.3 | The immediate flags | `RuntimeFlags` only turn off within a process; turning back on waits for the next launch. Network request capture is also off at startup when the effective configuration lacks `NETWORK_REQUESTS`, so the interceptor checks one flag (7.6). The pipeline and uploader honour `collecting` in 6.3 and 6.4. | `RuntimeFlags` |
| 2026-10-01 | 6.1 | Calls before the stores are loaded (the writer loads them first, Privacy 2; the factory never reads on the main thread) | Kept in call order and applied by the writer right after loading; snapshots taken meanwhile are filled at the same point, so `identify` then `track` still carries the new id and nothing is read on the calling thread. After loading, the synchronous in-memory path. Chosen by the user. | `Identity` |
| 2026-10-01 | 6.1 | `deviceId` before the writer has loaded `device.json` | Read once on the calling thread, like `isOptedOut()` (Public API 9), sharing one read with the writer so only one id is ever generated; a missing file's new id is written by the writer, never on the calling thread. Chosen by the user; Public API 9 lists the getter next to `isOptedOut()`. | `Identity`, `DeviceStore.readEarly`, Confluence Public API 9 |
| 2026-10-01 | 6.1 | Hooks for the session (Session 6, step 6.2) | `Identity` calls `onPersonCleared` with the old identity before `reset()` or a user switch clears the person, so 6.2 can end the session with the old `user_id` first. | `Identity` |
| 2026-10-01 | 6.2 | When a session starts | With the first event while the app is in the foreground: `Session Started` comes right before that event, with its timestamp as `started_at` (Session 5). Coming to the foreground alone starts nothing; in practice `Application Opened` is that first event. | `SessionManager` |
| 2026-10-01 | 6.2 | A clock set back at process start (Session 6, rule 1 says "the session ends") | It ends like a timed-out one: `Session Ended` backdated to the saved last activity, and the saved session is removed. | `SessionManager` |
| 2026-10-01 | 6.2 | The 30-second last activity write | Done with events in the foreground, at most every 30 s, and at once on backgrounding; no timer (Session 4). A foreground with no events at all leaves the last write as the backdated end after a crash. | `SessionManager` |
| 2026-10-01 | 6.2 | Where the session manager runs | On the writer only; foreground, background and person-cleared signals reach it as writer messages in order with events (6.3). A process that starts with a valid saved session counts its background time from the saved last activity. The session written to `PersonStore` is only ever the one the manager owns, so a person cleared on the calling thread never gets the old session back. | `SessionManager`, step 6.3 |
| 2026-10-01 | 6.2 | The end of a session at `reset()` or a user switch | Backdated to now when in the foreground, else to the last activity; it carries the previous person's identity. | `SessionManager` |
| 2026-10-01 | 6.3 | Step 6.3 is too big for one pull request | Split into 6.3a (the event pipeline, a transformation without coroutines) and 6.3b (the writer, its channel, startup order and flush triggers). | Steps 6.3a, 6.3b |
| 2026-10-01 | 6.3 | The log code of a hook result the rules do not allow (left open in 3.2) | A new ERROR code, `HOOK_VIOLATION`, separate from `HOOK_THREW`: a misuse, not a crash. Chosen by the user. | `Code`, `docs/issue-codes.md` |
| 2026-10-01 | 6.3 | A hook result that is not a `copy` of its input (Java can build events or subclass `Event`, left open in 3.2) | Dropped like any rule breach: `before_send`, `HOOK_VIOLATION`, throws in debug. A result is accepted only if it is a `TrackEvent`, `ScreenEvent` or `IdentifyEvent` of the input's class whose source, message id, timestamp, user id, device id and context are the input's. Chosen by the user. | `EventPipeline` |
| 2026-10-01 | 6.3a | The shape of `ContextProvider` (2.1 allowed refining it; 7.3 implements it) | Typed values instead of maps: `StaticValues` and `CallTimeValues`, `@RubiconInternal` so `sdk-android` can build them (the public context classes have internal constructors). Each call takes the disabled fields, so the provider never reads them (Context 6); the pipeline leaves them out again whatever it gets. | `ContextProvider`, step 7.3 |
| 2026-10-01 | 6.3a | Session events outside an event | `EventPipeline.processSessionEvents` takes the `Session Ended` produced on returning to the foreground, at process start and at a person change, so it passes the same steps as every event (hooks included). | `EventPipeline`, step 6.3b |
| 2026-10-01 | 6.3a | Details of the steps | Static context is read once, lazily, on the worker. `library.name` is `rubicon-android`. Super properties do not merge into identify events (traits only). A hook's `null` for an app event drops it quietly (DEBUG), as decided in 4.2; a throw logs `HOOK_THREW`. An app event renamed to an invalid name by a hook is a violation. The second validation cuts strings to 1024 characters like the first. Session events take the call-time context of the event that caused them. | `EventPipeline` |
| 2026-10-01 | 6.3b | The count trigger with events pending that cannot be sent | It counts the events appended since the last flush, starting at process start from the events left over (Queue 2: "including events left by a previous process"); any flush resets it. Counting everything pending would flush, and close a one-event segment, on every new event while offline. | `Writer` |
| 2026-10-01 | 6.3b | Consent changes and `clearLocalData` | Their messages are added with their behaviour in 6.5 (Privacy), which owns those rules; the writer has the message channel they will use. | Step 6.5 |
| 2026-10-01 | 6.3b | Writer details | The stores are written after every message when something changed. Lifecycle, network and person-cleared signals share the event channel, so they keep their order; if it is full they are skipped with a DEBUG line. An event created while `collecting` is off is not written. A `backpressure` drop is reported to the observer from the calling thread, since the event never reaches the writer. 6.3a and 6.3b go in one pull request, as the user asked. | `Writer` |
| 2026-10-01 | 6.4 | Waking during a wait | A wait sleeps until it ends or the next wake (flush, network); a wake checks the rules again, so the network coming back ends a network wait at once (Retry 2) while server waits go on unchanged. | `Uploader` |
| 2026-10-01 | 6.4 | When the remote configuration is fetched | At the first wake with the network there, once per launch, before the upload gates: also while opted out, pending or switched off (Configuration 1, Privacy 4). | `Uploader` |
| 2026-10-01 | 6.4 | Startup order of the uploader | It waits for the writer's startup (stores loaded), restores the retry state and fetches the remote configuration if the network is there. It sends nothing until a flush trigger fires: no startup upload (Queue 2); leftovers go with the interval the writer starts for them. | `Uploader`, `Writer.started` |
| 2026-10-01 | 6.4 | The consent gate | `uploadsAllowed`, `true` until step 6.5 wires opted out and pending to it. | Step 6.5 |
| 2026-10-01 | 6.4 | A segment that cannot be read | Skipped for the rest of the drain, so the next one is tried; the store deletes it after three failures (Disk 5). A segment with nothing left to send is deleted without a request. | `Uploader` |
| 2026-10-01 | 6.5 | Consent as a state | `GRANTED`, `PENDING` or `DENIED` (opted out), from `defaultConsent` or, with `persistConsent`, the stored choice. `optOut()` and `optIn()` change it in memory at once and tell the writer; calls before the stored choice is known keep their order and apply once it is, at the writer's load or at an early `isOptedOut()`. | `ConsentController` |
| 2026-10-01 | 6.5 | The upload gate | Closed only while `PENDING`. After an opt-out, events queued with consent are still sent (Privacy 1); from `PENDING`, `optOut()` deletes every pending event first, since all of them were collected without consent (Privacy 4). Opting in from `PENDING` flushes; from an opt-out it only resumes collection. | `ConsentController.allowsUploads`, `Writer` |
| 2026-10-01 | 6.5 | `clearLocalData()` in two parts | The identity part runs on the calling thread like `reset()`, so a later call on that thread already carries the new device id; the writer then deletes the queue, uncounted, and ends the session silently. An upload already reading a segment may still complete, and a running retry wait in the uploader runs out on its own. | `Identity.clearLocalData`, `Writer` |
| 2026-10-01 | 6.5 | Deleting on purpose | `EventStore.clear()` deletes every pending event, the open segment included, without counting: for `clearLocalData()` and for a pending consent turned into an opt-out. | `EventStore` |
| 2026-10-01 | 6.6 | Resetting the counters | The counts the event reported are taken off once it is stored, rather than setting everything to zero, so a drop counted by the uploader or a calling thread while it is written starts the next period instead of being lost. A failed write leaves them all. | `DeviceStore.diagnosticsReported` |
| 2026-10-01 | 6.6 | The property shape | `period_start` as an ISO string; `dropped` always with `total`, then each reason that counted, by name; `issues` by code, `{}` when none; keys sorted. Pinned by `golden/sdk-diagnostics.json`. | `Diagnostics` |
| 2026-10-01 | 6.6 | When it is written | Right before every flush, when due, sending allowed (code switch and remote rate), collecting, and consent granted (not pending, not opted out). It passes the pipeline without hooks; its own drop is not counted, and a flush it causes does not report again. Like any track event it carries the session and the super properties of the moment. | `Writer` |
| 2026-09-30 | 2.1 | Shape of the `Platform` interfaces | Minimal signatures from the decisions; the step that implements each one may refine it (`EventStore` in 4.2, `HttpTransport` in 5.1 and 5.3, `NetworkMonitor` in 7.2, `ContextProvider` in 7.3, `LifecycleSource` in 7.4). They are internal, so a change is not an API change. | - |
| 2026-09-30 | 1.1 | Build JDK | JDK 17 through `gradle/gradle-daemon-jvm.properties`; Gradle 8.14.3 does not run on JDK 25 and newer. | - |
| 2026-09-30 | 1.1 | OkHttp 5.x test version | 5.4.0, the newest 5.x that builds with `compileSdk` 36 (5.5.0 requires 37 and a newer AGP). Revisit when AGP is raised. | - |
| 2026-09-30 | 1.1 | `sdk-android` namespace | `com.rubicon.analytics.android`, used only for the generated `R` class. | - |
| 2026-09-30 | 1.1 | Unit test variants | `sdk-android` unit tests run on the debug variant only; release differs only in R8 (step 8.2). | - |
| 2026-09-30 | 1.2 | Where the property rules live | Property validation and `beforeSend` (Public API 3, 4) are in the root `CLAUDE.md` with the other public API rules, which keeps `sdk-core/CLAUDE.md` below 200 lines. The root file is 166 lines, above its 150-line target. | Root, `sdk-core/CLAUDE.md` |
| 2026-09-30 | 1.2 | Seed wording not carried over | "Open source" in the seed's first line waits for D8; the `:app` module, its commands and the fake server moved to the demo repository. | - |

## 16. Out of scope for the first release

Recorded on the parent Confluence page as open questions; not implemented until decided: wrapper SDK
boundaries (React Native, Flutter), SDK lifecycle states and `shutdown()`, performance and
reliability budgets, the versioned backend contract document and deduplication window, a diagnostic
API beyond `SDK Diagnostics`, typed property overloads, `group`, a runtime log level setter, TLS and
proxy hatches for on-prem.
