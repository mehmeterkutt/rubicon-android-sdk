# Rubicon Android SDK

Analytics SDK for Android in Kotlin. This file and each module's `CLAUDE.md` are the contract for how
code is written here; read them before changing anything.

- The design is decided in Confluence (space SD, "Analytics SDK Design Decisions", page 3112961;
  Atlassian MCP, `cloudId` `rubiconn.atlassian.net`) and is binding. A pointer such as (Queue 4) means
  decision 4 on the Queue with Flush Policy page; page ids are in section 1 of
  `docs/implementation-plan.md`. The reasoning lives there, not here.
- Work follows `docs/implementation-plan.md`: read its "Working agreement" and "Status" at the start
  of every session and take the first unchecked step.
- Never decide something the decisions do not cover: stop and ask, with options and a recommendation.

## Modules

| Module | Role |
|---|---|
| `:sdk-core` | `kotlin("jvm")`: configuration, pipeline, queue, storage, identity, sessions, upload. Rules: `sdk-core/CLAUDE.md` |
| `:sdk-android` | Factory, lifecycle, context, Android platform, OkHttp interceptor; `api(":sdk-core")`. Rules: `sdk-android/CLAUDE.md` |
| `build-logic` | Included build: module conventions, `checkSourceRules`, `BuildInfo` generation |

Apps add one dependency, `sdk-android`, which brings `sdk-core`; no separate artifact for optional
features (Public API 6). Android code lives only in `:sdk-android`; when `:sdk-core` needs the
platform (clock, storage directory, connectivity) it gets an interface that `:sdk-android` implements.
The demo app and its fake backend are a separate repository (`rubicon-team/rubicon-android-demo`).

## Commands

```bash
./gradlew check           # ktlintCheck, test, apiCheck, lint, koverVerify, checkSourceRules (JDK 17)
./gradlew :sdk-core:test --tests "*QueueTest"
./gradlew :sdk-android:testDebugUnitTest -PokhttpTestMajor=5   # Android tests against OkHttp 5
./gradlew ktlintFormat    # before every commit
./gradlew apiDump         # after a public API change; commit the .api files
./gradlew :sdk-android:dependencies --configuration releaseRuntimeClasspath
./gradlew connectedAndroidTest   # instrumented suite, needs an emulator (from step 8.1)
```

## Non-negotiables

A pull request that breaks one does not merge.

1. **Never crash the host app.** Every public entry point and background task catches `Throwable`,
   logs and degrades: the SDK failing is an analytics gap, not an app crash. The only exceptions
   thrown on purpose report developer mistakes when `debug = true` (invalid config, unsupported
   property type, throwing hook, second instance); with `debug = false` they log instead.
2. **No events lost silently.** An event is written to disk before `track()` returns control to the
   pipeline. Upload happens later, with retry and backoff. Offline is a normal state, not an error.
3. **Public API is forever.** Nothing public is removed or renamed. Deprecate with
   `@Deprecated("...", ReplaceWith(...))` and keep it working for at least one major version.
4. **Everything is `internal` unless it must be public.** Public surface: the entry point,
   configuration, event types and extension interfaces. Nothing else.
5. **Application context only.** Never hold an `Activity`, `View` or `Fragment`; callbacks that need
   one use `WeakReference`.
6. **No new dependencies.** Adding one is a design discussion, not a PR detail.
7. **Every change ships with tests.** No exceptions for small fixes.

## Public API

- The developer creates and holds the instance: `Analytics(context, config)` in `:sdk-android`
  returns the `Analytics` interface of `:sdk-core`. No static singleton or global accessor; one
  instance per process, enforced by the factory; the implementation is `internal` (Public API 1, 2).
- Fixed vocabulary: `track`, `screen`, `identify`, `reset`, `register`, `unregister`, `flush`,
  `optOut`, `optIn`, `isOptedOut`, `clearLocalData`, read-only `deviceId`, no `userId` getter (Public
  API 8). Public methods return `Unit`: no chaining, no event ids (Public API 5).
- Properties are `Map<String, Any?>`, no typed event classes. Validated in one pass on the calling
  thread, before enqueueing, into an immutable deep copy; never keep the caller's map (Public API 3,
  Event Model 3). Validated again after the `beforeSend` chain.
- Allowed: `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, `null`, `List`/`Map` of those.
  `java.util.Date` -> ISO 8601 UTC string, enum -> `name`, `Float` via its decimal text, `Byte`/`Short`
  -> `Int`, `BigDecimal` -> `Double`, `BigInteger` -> `Long` if it fits, `Char` -> string, arrays and
  collections -> list, unpaired surrogates -> U+FFFD (Serialization 2).
- Rejected: `java.time` (detected by class name, never referenced), NaN, Infinity, non-String or
  blank keys, `JSONObject`, anything else. Drop the key or entry (a bad collection element drops only
  itself), WARN with key and type only, never the value; keep the event; throw in debug.
- Limits (Serialization 3): event line 32 KB (drop, `oversize`); event or screen name and key 1..255
  chars (drop, never trim); string values 1024 chars (truncate on a code point boundary, no marker,
  WARN, never throw); 256 properties with super properties (those dropped first); nesting depth 3.
- `AnalyticsConfig.beforeSend: List<BeforeSend>`, fixed at creation; `fun interface` returning
  `Event?`. `null` drops, a throwing hook drops too (fail-closed, `before_send`). No runtime add or
  remove, no public plugin API; internally the pipeline is a list of `internal` steps (Public API 4).
- Interfaces evolve by adding members with a default body; existing signatures never change.
- Configuration classes are builders: required values in the constructor, everything else a mutable
  property with a default. Never add constructor parameters. Optional values are `Type? = null`,
  required values non-null.
- Java works by construction, no separate Java API (Public API 7). `@JvmOverloads` on public functions
  and constructors with default parameters, except interface members (Java passes all arguments).
  `@JvmStatic` on companion and object members callers use. Hooks and callbacks are `fun interface`.
  No `suspend` or `Flow` in the user-facing API, only in extension interfaces SDK developers implement.
- KDoc on every public symbol, with a short usage example when it prevents misuse.
- Public only for cross-module use: annotate `@RubiconInternal` (`@RequiresOptIn`).
- Explicit API mode is on. Run `apiDump` and commit the `.api` diff: a diff there is the definition of
  a public API change and is named at the top of the pull request.

## Dependencies

- Runtime: `kotlinx-coroutines-core` 1.9.0 and `kotlin-stdlib` 2.0.0 in both modules;
  `androidx.lifecycle:lifecycle-process` 2.4.0 (`implementation`, oldest supported stable) in
  `:sdk-android` only. Nothing else (D5, D6).
- `compileOnly`, provided at runtime: `android-json` in `:sdk-core` (Android's `org.json`, never
  `org.json:json`); OkHttp 4.12 and `androidx.activity` 1.7 in `:sdk-android`, behind a class presence
  check (Configuration 5, D13). No Gson, Moshi or kotlinx.serialization at runtime (Serialization 1).
- Published code compiles with `apiVersion`/`languageVersion` 2.0 and `jvmTarget` 1.8 so apps on
  Kotlin 1.9 and newer can use it (D5); the build conventions set this, do not override it.
- Uploads use `HttpURLConnection` behind the internal `HttpTransport` in every module; never OkHttp,
  Retrofit, Ktor or the app's `OkHttpClient` (HTTP 1).
- `java.time` is never used in SDK code (missing below API 26 without desugaring): `checkSourceRules`
  forbids it in main sources, lint `NewApi` is an error in `:sdk-android` (Serialization 2).
- Test-only dependencies are free (name them in the pull request): JUnit 5, JUnit 4, MockK,
  kotlinx-coroutines-test, MockWebServer, Robolectric, androidx.test core, android-json, and
  kotlinx-serialization-json as an independent parser.

## Threading

- Every public method is thread-safe and non-blocking. The calling thread only copies arguments,
  validates properties and snapshots identity and super properties; disk and network work is
  enqueued. Calls from the same thread take effect in call order (Public API 9).
- Deep-copy mutable maps at every boundary (public API in, storage out, hook in and out); shallow
  copies do not prevent `ConcurrentModificationException` on nested collections.
- Shared mutable state is `@Volatile` or guarded by a private lock object; never `synchronized(this)`.
- The SDK owns its coroutine scope, never `GlobalScope` or app scopes (writer and uploader: sdk-core).

## Testing

- Layers, tools and the rules for every test: section 5 of the plan.
- Unit tests first, on the JVM: JUnit 5 in `:sdk-core`, Robolectric with JUnit 4 in `:sdk-android`. A
  small instrumented suite covers what the JVM cannot (process kill, real offline, R8 build).
- Names are backtick sentences, `` `does not upload while offline` ``, grouped with
  `@Nested inner class` (JUnit 5).
- Coroutine tests use `runTest` with `StandardTestDispatcher` and `advanceUntilIdle()`; no
  `Thread.sleep`.
- Coverage gates (`koverVerify`): 90 percent of lines in `:sdk-core`, 80 percent in `:sdk-android`.
- Anything touching upload, storage or identity gets one manual run in the demo app against its fake
  server, once the demo app uses the SDK (step 9.2).

## Code style

- ktlint official style (`ktlintFormat`). Explicit imports, no wildcards. Readability over
  cleverness: small functions, one responsibility.
- `class` for public types, never `data class` (its `copy` and `componentN` become API);
  `data class` for internal DTOs, `sealed` for closed hierarchies, `enum` for fixed sets.
- Constants in a `companion object` or a private top-level `const val`.
- Log only through the internal `Log` functions (`log.debug { }`, `log.warn(Code.X) { }`,
  `log.error(Code.X, e) { }`), never `println`, `System.out` or `android.util.Log` (Logging 1, 3;
  `checkSourceRules`). Full logging rules: `sdk-core/CLAUDE.md`.
- Never log property or trait values, `userId`, `deviceId`, session id, the API key, header values,
  bodies or query strings, at any level; names, keys, types, counts, codes and `message_id` are fine
  (Logging 4).
- Documents and code comments are in English, without emoji.

## Git and pull requests

- `master` holds releases, `develop` the next one. One plan step is one `feature/<topic>` branch from
  `develop` (no step number) and one pull request, squash-merged into `develop`. Conventional Commits
  (`feat(queue): ...`, `build: ...`).
- Before committing: `ktlintFormat`, then `./gradlew check` green (it includes `test` and `apiCheck`).
- Commit locally and stop for review; push and open the pull request only after the user approves.
  Never merge, never push to `develop` or `master`.
- The description follows `.github/pull_request_template.md`: public API changes first, then problem,
  change, decisions implemented, how it was tested.

## Keeping these files honest

They are loaded into every AI session and read by every contributor. When a rule stops matching the
code, fix one or the other in the same pull request. State each rule once; module detail goes into
the module's `CLAUDE.md`. Each file stays under 200 lines; this one near 150.
