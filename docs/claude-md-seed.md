# Rubicon Analytics SDK

Open source analytics SDK for Android, written in Kotlin. This repo holds the SDK modules and the
host app that exercises them. Read this file before changing anything; it is the contract for how
code gets written here.

## Modules

| Module | Role | Android? |
|---|---|---|
| `:sdk-core` | Configuration, event pipeline, queue, storage, identity, sessions, HTTP upload. Pure Kotlin/JVM. | No |
| `:sdk-android` | Factory, lifecycle, app context, Android storage, OkHttp interceptor. Depends on `:sdk-core`. | Yes |
| `:app` | Host app (Rubicon). Exercises the SDK against `fake-server/`. Not published. | Yes |

Users add one dependency, `sdk-android`; it pulls `sdk-core` transitively. There is no separate
artifact for optional features. Android-specific code lives only in `:sdk-android`. If `:sdk-core`
needs something from the platform (clock, storage dir, connectivity), it gets an interface and
`:sdk-android` provides the implementation.

The SDK's entry point is the `Analytics` interface in `:sdk-core`. The app's stand-in,
`RubiconAnalytics` in `app/.../analytics/`, has the same shape and is replaced by it: `track`,
`screen`, `identify`, `reset`, `register`, `unregister`, `flush`, `optOut`, `optIn`, `isOptedOut`,
`clearLocalData`, plus the read-only `deviceId` (no `userId` getter).

## Commands

```bash
./gradlew build                        # compile everything
./gradlew test                         # all JVM unit tests (core, android via Robolectric)
./gradlew :sdk-core:test               # one module
./gradlew :sdk-core:test --tests "*QueueTest"
./gradlew connectedAndroidTest         # instrumented smoke tests, needs emulator
./gradlew ktlintFormat                 # format before every commit
./gradlew apiDump                      # after any public API change; commit the .api files
./gradlew apiCheck                     # binary compatibility, CI enforced
./gradlew :app:installDebug            # manual check against fake-server
python3 fake-server/server.py          # fake backend, emulator reaches it at http://10.0.2.2:8080
```

Environment, emulator and adb recipes are in `README.md`.

## Non-negotiables

These apply to every change. A PR that breaks one does not merge.

1. **Never crash the host app.** Every public entry point and every background task catches
   `Throwable`, logs, and degrades. The SDK failing is an analytics gap, not an app crash. The only
   exceptions thrown on purpose report developer mistakes when `debug = true` (invalid config,
   unsupported property type, throwing hook, second instance); with `debug = false` they log instead.
2. **No events lost silently.** An event is written to disk before `track()` returns control to the
   pipeline. Upload happens later, with retry and backoff. Offline is a normal state, not an error.
3. **Public API is forever.** Nothing public is removed or renamed. Deprecate with
   `@Deprecated("...", ReplaceWith(...))` and keep it working for at least one major version.
4. **Everything is `internal` unless it must be public.** Public surface is the entry point,
   configuration, event types and extension interfaces. Nothing else.
5. **Application context only.** Never hold an `Activity`, `View` or `Fragment`. Callbacks that need
   them use `WeakReference`.
6. **No new dependencies.** See Dependencies below. Adding one is a design discussion, not a PR detail.
7. **Every change ships with tests.** No exceptions for "small" fixes.

## Public API

Decided in Confluence: Analytics SDK Design Decisions > Public API: Decisions. In short:

- The developer creates and holds the instance: `Analytics(context, config)` in `:sdk-android` returns
  the `Analytics` interface from `:sdk-core`. No static singleton, no global accessor. One instance per
  process, enforced by the factory. The implementing class is `internal`.
- The vocabulary above is fixed. Public methods return `Unit`; no chaining, no event ids.
- Properties are `Map<String, Any?>`, validated on the calling thread before the event is enqueued.
  Allowed: `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, `null`, and `List`/`Map` of those.
  `java.util.Date` becomes an ISO 8601 UTC string, enums their `name`; `Float` goes through its
  decimal text, `Byte`/`Short` -> `Int`, `BigDecimal` -> `Double`, `BigInteger` -> `Long` if it fits,
  `Char` -> string, arrays and collections -> list, unpaired surrogates -> U+FFFD. `java.time`, NaN,
  Infinity, non-String or blank keys, `JSONObject` and anything else: drop the key or entry, log a
  warning (key and type only, never the value), keep the event; throw in debug. No typed event classes.
- Limits: event line 32 KB (drop, `oversize`), event/screen name and key 1..255 chars (drop, never
  trim), string values 1024 chars (truncate on a code point boundary, no marker, WARN, no throw),
  256 properties incl. super properties (super properties dropped first), nesting depth 3.
- The only public extension point is `AnalyticsConfig.beforeSend: List<BeforeSend>`, fixed at
  creation. `BeforeSend` is a `fun interface` returning `Event?`; `null` drops the event, a throwing
  hook drops it too (fail-closed). No runtime add/remove, no public plugin API. Internally the
  pipeline is a list of `internal` steps.
- Interfaces evolve by adding members with a default body. Existing signatures never change.
- Configuration classes are builders: required values in the constructor, everything else a mutable
  property with a default. Never add constructor parameters. Validation, snapshot and freezing are
  described under Configuration.
- Java works by construction; there is no separate Java API. `@JvmOverloads` on public functions and
  constructors with default parameters, except interface members, where Kotlin does not allow it and
  Java callers pass all arguments. `@JvmStatic` on companion/object members callers use. Hooks and
  callbacks are `fun interface`. No `suspend` and no `Flow` in the user-facing API; those are allowed
  only in extension interfaces implemented by SDK developers (for example custom storage).
- Optional values are `Type? = null`. Required values are non-null.
- KDoc on every public symbol, with a short usage example when it prevents misuse.
- `internal` for implementation. Where something must be public for cross-module use but is not
  for users, annotate it `@RubiconInternal` (a `@RequiresOptIn` marker).
- Run `apiDump` and commit the `.api` diff. A diff in those files is the definition of "public API
  change" and must be called out at the top of the PR.

## Event model

Decided in Confluence: Analytics SDK Design Decisions > Event Model: Decisions. In short:

- Ownership decides placement. Top level is the event's identity: `type`, `event` (track) or `name`
  (screen), `source` (`app` or `auto`, set only by the SDK), `message_id`, `timestamp`, `user_id`,
  `device_id`. `properties` (track, screen) and
  `traits` (identify) belong to the developer; the SDK never writes into them. Super properties are
  merged into `properties`, the event's own key wins. `context` belongs to the SDK: `app`, `device`,
  `os`, `screen`, `network`, `locale`, `timezone`, `session`, `library`. Wire keys are `snake_case`.
- Types: `sealed class Event` with `TrackEvent`, `ScreenEvent`, `IdentifyEvent`. Wire discriminator
  `type` is `track`, `screen` or `identify` (pending backend confirmation). KDoc tells hook authors to
  keep an `else` branch; subtypes may be added in minor versions.
- `properties`/`traits` are immutable, normalized `Map<String, Any?>`. Validate and normalize on the
  public call and again after the `beforeSend` chain. Never keep a reference to the caller's map.
- `message_id` is a UUIDv7 from the shared monotonic generator, created on the calling thread,
  persisted with the event and never regenerated (retry, batch split, restart). The same generator
  makes `device_id` and the session id.
- Time is `Long` epoch ms from the injected `Clock`, taken at call time. Top-level wire time fields
  (`timestamp`, `sent_at`, `context.session.started_at`) are epoch ms numbers. `Date` inside
  properties or traits becomes ISO 8601 UTC `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` via our own formatter
  (epoch ms -> calendar fields; no `java.time`, no `SimpleDateFormat`). `sent_at` goes in the
  request envelope and is rewritten on every HTTP attempt.
- Events and `EventContext` are immutable public `class`es with `internal` constructors. Each event
  has a hand-written `copy` limited to `name` and `properties`/`traits` (`@JvmOverloads`, new
  parameters appended only). Hooks can read but never change `context`, `source`, `message_id`,
  `timestamp`, `type`, `user_id` or `device_id`. `toString` prints type, name and message id only. No
  `equals`/`hashCode` overrides.

## Configuration

Decided in Confluence: Analytics SDK Design Decisions > Configuration: Decisions. In short:

- One `AnalyticsConfig(apiKey)` in `:sdk-core`. Top level: `host` (default SaaS base URL, overridable
  for on-prem; optional path prefix; trailing slash normalized; endpoints are `{host}/v1/batch` and
  `{host}/v1/config`), `debug`, `beforeSend`. Groups held in `val` properties: `flush`, `session`,
  `autocapture`, `context`, `privacy`, `logging`, `network`, `remote`.
- About twenty public settings. Tuning values (batch and queue limits, backoff table, retry limits,
  HTTP timeouts, compression) are internal constants. Making one public is a design discussion.
- No platform types in configuration. `Context` is only a factory parameter. Settings use
  platform-neutral names (`Autocapture.APP_LIFECYCLE`, not activity terms); `:sdk-android` decides the
  mechanism. Android-only options live in the options class of the Android feature that needs them.
- The factory validates. `debug = true`: throw. `debug = false`: blank key or invalid host returns a
  no-op `Analytics` and logs ERROR; out-of-bounds numbers are clamped with a WARN; contradicting values
  fall back to the group defaults with a WARN. Log the effective host once at INFO on startup.
- Main process only (`applicationInfo.processName` vs `Application.getProcessName()` / `runningAppProcesses`
  on API < 28; never `/proc` or `ActivityThread`): elsewhere the factory returns a no-op and logs INFO
  once, in debug too; unknown name -> assume main, WARN.
- One instance per process. A second factory call throws in debug and returns the existing instance
  (first config wins) in release.
- After the factory, the config object is frozen. Settings use the freezing property delegate:
  writing throws in debug, logs one WARN and is ignored in release. Runtime behaviour changes only
  through `Analytics` methods, never through config.
- Remote config: `GET {host}/v1/config` with ETag, in the background. Remote can only restrict what
  code allows. Allowlist: `enabled` (kill switch), autocapture types to switch off, `flush_interval_ms`,
  `flush_at`, `sample_rate`. Saved to disk and applied on the next launch; `enabled = false` and
  switching off `NETWORK_REQUESTS` apply at once (runtime flags outside the snapshot). Any failure falls back to the last valid remote values or to code. Remote values go through
  the same validator; an invalid key is ignored with a WARN.
- `network.headers` can never override reserved headers (`Authorization`, `Content-Type`,
  `Content-Encoding`, `User-Agent`, the SDK's own `X-` headers).
- Test-only replacements (clock, stores, network monitor, HTTP transport, dispatchers, random) live in
  the internal `Platform`, reached through the `@RubiconInternal` factory overload.

## Context

Decided in Confluence: Analytics SDK Design Decisions > Context Enrichment: Decisions. In short:

- Internal `ContextProvider` (core, part of `Platform`) plus `ContextStep`; `AndroidContextProvider`
  in `:sdk-android`. Nothing is collected in the factory or on the main thread; static values are
  collected once on the worker, on first need.
- Cached, refreshed by signals: network type and carrier by the `NetworkMonitor` callback; screen and
  locale by `onConfigurationChanged`; timezone read per event; app, device, OS, library never.
- Time-dependent values (network, screen, locale, timezone) are captured from the cache on the calling
  thread at call time. Nothing is read at send time.
- `:sdk-android` declares `INTERNET` and `ACCESS_NETWORK_STATE`. If the app removes the latter: omit
  `network.type`, read the carrier at most every 60 s.
- Carrier = `networkOperatorName` of the default data subscription (`getDefaultDataSubscriptionId`,
  `createForSubscriptionId`), falling back to the default `TelephonyManager`. Dual SIM must report the
  data SIM.
- Fields (to be revisited): `app.name|version|build|namespace`, `device.manufacturer|model|type|
  is_emulator`, `os.name|version|api_level`, `screen.width|height|density`, `network.type|carrier`,
  `locale`, `timezone`, `library.name|version`. Unknown values are omitted, never `null` or `""`.
  Never collected: location, advertising ids, IP, battery, memory, storage, orientation, bluetooth,
  NFC, cellular technology, user agent, `Build.DEVICE`, installer.
- `context.disabledFields: Set<ContextField>`, one enum value per field. `library.*` cannot be
  disabled. A disabled field is never read. No ready-made sets (no `CHILD_DIRECTED`); the integration
  guide's checklist names `NETWORK_CARRIER`, `LOCALE`, `TIMEZONE`.
- Super properties are validated at `register` with the property rules; `null` is stored and sent.

## Session

Decided in Confluence: Analytics SDK Design Decisions > Session Management: Decisions. In short:

- Every event carries `context.session.id` (UUIDv7, shared generator) and `started_at` (epoch ms).
  Session changes are announced internally (`@RubiconInternal` context), not publicly.
- Foreground (`ProcessLifecycleOwner`) keeps a session alive. Backgrounding starts the countdown;
  returning before `session.backgroundTimeoutMs` (default 5 min, 10 s..2 h, not remote) continues it.
  Max duration 12 h (internal). No foreground inactivity timeout, no timers: evaluate on foreground,
  on each event and at process start. Background time is measured with `elapsedRealtime()`.
- Background events belong to a still-valid session but never extend it. After it ends they carry no
  session and never start one. A process started in the background has no session until foreground.
- `Autocapture.SESSIONS` (default on): `Session Started` right before the event that starts a
  session; `Session Ended` when the end is detected, backdated to the last activity, with
  `duration_ms`, carrying the ended session.
- Session state lives in `PersonStore`: id and `started_at` written at start; last activity (wall
  clock) written on `onStop` and at most every 30 s in the foreground. At process start: clock moved
  back -> end; within the background timeout and max duration -> continue; else backdated
  `Session Ended` and clear.
- `reset()` and `identify` with a different user: `Session Ended` with the old `user_id` first, then
  clear `PersonStore`; the next foreground event starts a new session with `Session Started`.

## Lifecycle

Decided in Confluence: Analytics SDK Design Decisions > Automatic Lifecycle Events: Decisions. In short:

- Automatic events have plain Title Case names, no `[auto]` prefix, and `source = EventSource.AUTO`
  (`"source": "auto"`). Developer calls are always `APP`. Their properties are SDK-defined `snake_case`.
- Inventory: `Application Installed`, `Application Updated`, `Application Opened` (`from_background`),
  `Application Backgrounded`, `Session Started`/`Ended`, automatic `ScreenEvent`, `Deep Link Opened`,
  `Network Request` (`method`, `url` without query or fragment, `host`, `path`, `status_code`, `error`
  as a short class never a message, `duration_ms`, `request_bytes`, `response_bytes`). No element
  interaction, no crash events, `reset()` produces no event.
- `Autocapture` defaults: `APP_LIFECYCLE`, `INSTALL_UPDATE`, `SESSIONS`, `SCREEN_VIEWS`,
  `NETWORK_REQUESTS` on; `DEEP_LINKS` off. Remote switch-off applies on the next launch, except
  `NETWORK_REQUESTS`, which the interceptor checks per request (off -> `chain.proceed(request)`).
- Foreground from `ProcessLifecycleOwner`, observer added on the main thread (post if needed). Always
  call `AppInitializer...initializeComponent(ProcessLifecycleInitializer)` in case the app removed the
  Startup provider.
- Screens: activities only; skip a repeated resume of the same activity. Name = `@ScreenName` value,
  else the simple class name minus a trailing `Activity`. Never titles or labels. One property,
  `name_source` (`annotation` or `class`). No Fragment or Compose automation.
- Install/update on the first foreground of a process, state in `DeviceStore` (version, build). No
  record and `firstInstallTime == lastUpdateTime` -> Installed; no record otherwise -> Updated without
  previous version; different build -> Updated (`is_downgrade: true` only when lower). Properties
  `installed_at`/`updated_at` as ISO strings. No install referrer.
- Deep links: scheme, host, path, `utm_*` query parameters and referrer package or host only. Catch
  `singleTop` links with `addOnNewIntentListener`; dedupe with a weak reference to the last intent.

## Identity

Decided in Confluence: Analytics SDK Design Decisions > Identity Management: Decisions. In short:

- Two ids. `device_id`: UUIDv7 from the shared generator, created on first launch. `user_id`: set by
  `identify`, `null` means anonymous. No identified flag, no alias (ever), no group.
- Identity lives in files under `noBackupFilesDir`, never in `SharedPreferences`: Android Auto Backup
  must never clone a `device_id` onto another phone.
- "Missing" and "unreadable" are different. Missing: generate and write. Unreadable (corrupt, Direct
  Boot): use a temporary in-memory id, never overwrite the file, WARN.
- `identify(userId, traits)`: anonymous -> set and persist `user_id`, send `IdentifyEvent`; same id with
  traits -> `IdentifyEvent` only; same id without traits -> nothing; a different id while identified ->
  clear `PersonStore`, then identify, INFO log without ids (no debug exception: account switching is
  legitimate). Blank or longer than 255 chars -> throw in debug, WARN and ignore in release. Never trim.
- Traits are set-only, sent only inside `IdentifyEvent`, never stored on the device. Anonymous users
  have no traits; device-level information goes through `register`.
- Two stores. `PersonStore`: `user_id`, all super properties, the open session. `DeviceStore`:
  `device_id`, consent choice, last seen version and build, remote config cache, retry state, drop
  counters. `reset()` clears
  `PersonStore` only and leaves the queue alone. To place a new persisted value ask: "if another person
  logs in on this device, should it carry over?" Yes -> `DeviceStore`; no or unsure -> `PersonStore`.
  A unit test asserts the exact key set of each store.
- Merge is implicit: both ids on every event. Backend rule: anonymous events belong to the first
  `user_id` seen after them on the same device; two different non-null `user_id`s never merge.

## Dependencies

- `:sdk-core` and `:sdk-android` depend on `kotlinx-coroutines` and nothing else, with one exception:
  `:sdk-android` also has `androidx.lifecycle:lifecycle-process` (`implementation`, oldest supported
  stable version) for `ProcessLifecycleOwner`.
- SDK upload traffic uses `HttpURLConnection` behind the internal `HttpTransport` interface, in
  every module. Uploads never use OkHttp, Retrofit, Ktor or the app's `OkHttpClient`.
- `HttpTransport` rules: always gzip, compressed in memory, sent with `setFixedLengthStreamingMode`
  (never chunked). Connect 15 s, read 20 s, whole request 45 s via a watchdog that calls
  `disconnect()`. `Authorization: Bearer {apiKey}` (never in the body), User-Agent
  `rubicon-android/{version} (Android {os}; API {level})`, `useCaches = false`,
  `instanceFollowRedirects = false` (a 3xx is an error logged at ERROR). One attempt per call; no
  retry, backup host or `TrafficStats` tagging in the transport.
- `HttpTransport` never throws: it returns `HttpResult` (`Success`, `HttpError(code, body,
  retryAfterMs)`, `NetworkFailure(kind)` with `TIMEOUT|DNS|CONNECT|TLS|IO|BLOCKED|UNEXPECTED`). Read at
  most 4 KB of a body, always drain and close streams. Parse `Retry-After` as seconds or HTTP date.
- After a `CONNECT`/`DNS` failure only, resolve the host; loopback or any-local -> `BLOCKED` (skip
  when `host` is `localhost` or an IP). Never log request or response bodies.
- JSON via `org.json` (`compileOnly` in core, present on Android). No Gson, Moshi or
  kotlinx.serialization at runtime. JVM tests use `com.vaadin.external.google:android-json`
  (Android's own implementation), never `org.json:json`. Only the internal JSON conversion layer
  touches `org.json`: write `JSONObject.NULL` for null (a Kotlin null removes the key), read with
  `has()` (never trust `opt*` defaults), compare JSON structurally in tests.
- `java.time` is never imported in SDK code (missing below API 26 without desugaring). Lint
  `NewApi` is an error in `:sdk-android`; a build check forbids `java.time` in `:sdk-core`.
- Test-only dependencies are free: JUnit 5, MockK, kotlinx-coroutines-test, MockWebServer, Robolectric,
  android-json, and kotlinx-serialization-json as an independent parser to check every line is valid
  JSON. Golden wire files per event type are the backend contract; compare structurally.

### HTTP call capture (`:sdk-android`, package `network`)

- The interceptor lives inside `sdk-android` so users need only one dependency. OkHttp is
  `compileOnly`: the artifact ships no OkHttp and the host app's version is used at runtime. The
  interceptor is the only OkHttp code in the SDK. Its options are constructor parameters, not config. Nothing
  outside the `network` package may reference `okhttp3.*`, so apps without OkHttp never load it.
- Ship `consumer-rules.pro` with `-dontwarn okhttp3.**` so R8 does not fail on the missing classes.
- Compile against the **oldest** supported OkHttp (4.12), never the newest.
- Use only Interceptor API that is identical across 4.x and 5.x. Write null-safe access (`?.`) even
  where 5.x made something non-null.
- Bumping the OkHttp compile version is a minor release with a changelog entry, never a patch.
- CI runs the interceptor tests against both 4.12 and the latest 5.x.
- The interceptor **never** modifies the request, **never** throws, and **never** captures requests
  to the SDK's own endpoint.
- Headers and bodies are never captured, by any option; only `request_bytes`/`response_bytes`. Query
  and fragment are never sent. Path segments that are all digits, a UUID or hex longer than 16 chars
  become `:id` in `path` and `url` (`maskPathIds = true` by default, constructor option). Opted out or
  `NETWORK_REQUESTS` off: pass through with `chain.proceed(request)`.
- Which hosts to capture is opt-in configuration. Nothing is captured for an unconfigured host.

## Threading

- Two single-consumer coroutines in an SDK-owned scope (`SupervisorJob` + `CoroutineExceptionHandler`,
  never `GlobalScope` or app scopes), each on `Dispatchers.IO.limitedParallelism(1)` from `Platform`:
  the **writer** (hooks, second validation, serialize, append; the only code that writes to disk,
  identity/session/device state included) and the **uploader** (one request in flight, delete only
  after the server acknowledged). Never touch `Dispatchers.Main` from either.
- Write channel capacity 1000; callers use `trySend`, a full channel drops the event with reason
  `backpressure`. Every flush request is a `Flush` message in the write channel (so it includes all
  earlier events); the upload signal is a `CONFLATED` channel.
- Every public method is thread-safe and non-blocking. The calling thread only copies arguments,
  validates properties and snapshots identity and super properties; disk and network work is
  enqueued. Calls from the same thread take effect in call order.
- `flush()` triggers an upload and returns. `flushBlocking(timeout)` is `internal`, for tests and
  shutdown only.
- Identity changes (`identify`, `reset`, `register`, `unregister`) apply synchronously in memory and asynchronously
  to disk, so `identify()` followed by `track()` sees the new identity.
- Deep-copy mutable maps at every boundary: public API in, storage out, plugin in/out. Shallow copies
  do not prevent `ConcurrentModificationException` on nested collections.
- Shared mutable state is `@Volatile` or guarded by a private lock object. Never `synchronized(this)`.

## Storage and network

- Persist events before upload. Batch, then upload. Delete only after the server acknowledged.
- Stream files; never load a whole queue file into a `String`.
- Retry classification (one `when` over `HttpResult`): 2xx delete. Transient (`TIMEOUT`/`CONNECT`/`IO`/
  `DNS`, 408, 5xx except 501/505, 1xx) keep and back off. 429 or 503+`Retry-After` pause the pipeline.
  400, 422, other 4xx delete (`rejected`). 401, 403, 404, 410, 413, 3xx, 501, 505, `TLS`, `BLOCKED`,
  `UNEXPECTED` keep the data, pause the pipeline 1 h (±20%), ERROR once. Offline is waiting, not failure.
- Backoff table 2 s, 10 s, 30 s, 2 min, 5 min, then 10 min, jitter x0.5..1.5, reset on success; the
  uploader retries by itself when the wait ends. Network regained ends only network-caused waits.
  `Retry-After` replaces the table, capped 1 h, floor 1 s, jitter x1.0..1.1, never shortened.
- Poison batch: after 5 consecutive transient failures of one segment try the next; if it succeeds move
  the failing one to the end; drop it (`retry_exhausted`) after 10 failures while others succeed.
- Retry state (`paused_until`, `pause_kind`, `step`, `last_failure`, per-segment attempts) lives in
  `DeviceStore`, restored at startup; clamp `paused_until` to the kind's max wait if the clock moved
  back. The server answers 200 for every well-formed batch; never parse success bodies.
- One default-network callback for the whole SDK, registered once in `try/catch(Throwable)`; on failure
  query before each flush. Connected = `NET_CAPABILITY_INTERNET` (never require `VALIDATED`); without
  `ACCESS_NETWORK_STATE` assume connected.
- Queue limits (internal): 1000 pending events and 3 days from `timestamp`. Over the count, drop the
  oldest down to 900 in one step; expired events are deleted unsent (checked at process start and
  before every upload). Batches: at most 100 events and 512 KB of JSON before gzip, oldest first,
  sent one after another, stop at the first failure.
- Every drop increments a per-reason counter in `DeviceStore` (`queue_full`, `expired`,
  `backpressure`, `before_send`, `storage_error`, `corrupt`, `api_key_changed`, `oversize`, `rejected`,
  `retry_exhausted`); every WARN/ERROR code increments an issue counter. Right before a flush, if any
  counter is non-zero and >= 1 h since the last one (stored in `DeviceStore`, future time = now), emit
  one `SDK Diagnostics` event (`period_start`, `dropped{total,...}`, `issues{CODE: n}`; no text, values
  or stack traces); counters reset when it is written; its own drop is never counted. Sampled by
  `device_id` with remote `diagnostics_sample_rate`; off with `logging.sendDiagnostics = false`; never
  produced while opted out, pending or killed. Not gated by `Autocapture`.
- Flush triggers: count (`flush.at`, default 30, 1..100, counted over all pending events), interval
  (`flush.intervalMs`, default 60 s, 1 s..10 min, timer runs only while events are pending and counts
  from the first one), `ON_STOP`, network regained, `flush()`, and names in `flush.immediateEvents`
  (matched on the final name after hooks, flush after persisting, never a bypass). No upload while the
  monitor reports offline, during backoff (network regained never cuts a `Retry-After`), or when
  opted out / disabled. No public flush policy, no pause/resume, no WorkManager. `debug` never changes
  thresholds. Defaults are to be tuned from field data.
- Layout: `noBackupFilesDir/rubicon/` holds `device.json`, `person.json`, `remote-config.json` and
  `events/v1/{keyHash}/` (first 16 hex of SHA-256 of the API key). Other key directories are deleted
  at startup and counted as `api_key_changed`.
- Events: segmented append log in `:sdk-core`, plain `java.io`, behind internal `EventStore`. Open
  segment `{index}.tmp` (fixed-width index, next = max in directory + 1, no stored counter) closes at
  100 events / 512 KB / flush and is renamed; closed segments are immutable, one segment = one batch,
  deleted after a 2xx. JSON Lines: one compact UTF-8 line per event, exactly the wire event, written
  with one `write()` of line + `\n`. Body = `{"sent_at":..,"batch":[` + lines joined by commas + `]}`,
  streamed through gzip.
- fsync only when a segment closes (before rename), on `ON_STOP`, and on every store write. A `.tmp`
  found at startup is a leftover: keep complete lines, drop a half last line (`corrupt`), close it.
- Corruption: skip bad lines (`corrupt`), never log line content (ERROR with segment, line number,
  `message_id`). Unreadable segment: retry, delete after 3 consecutive failures (issue `UNREADABLE_SEGMENT`).
  Unknown files: delete with WARN. No quarantine, no encryption, no free-space check.
- `PersonStore`/`DeviceStore`: whole-file JSON with `"v"`, written as `.tmp` + fsync + rename, coalesced
  on the writer. Missing -> create. Unreadable (corrupt, Direct Boot, newer `v`) -> in-memory values
  for this process and never overwrite. Storage is internal; developers can never replace it.
- Privacy (see Privacy Controls: Decisions). `privacy.defaultConsent` is `GRANTED` (default) or
  `PENDING` (write, never upload, until a decision); `privacy.persistConsent` (default `true`) stores
  the choice in `DeviceStore`, and a stored choice beats the default. The writer loads `DeviceStore`
  before processing anything, so startup events of an opted-out user never reach disk; the factory
  never reads it on the main thread (only an early `isOptedOut()` may read `device.json` once).
- `optOut()`: no new events of any kind (auto, session, `SDK Diagnostics` included), the open session
  ends silently, the interceptor passes through; events queued before still upload; `identify`,
  `register`, `reset` update local state without events. `optIn()`: resume, no opt-in event. From
  `PENDING`, `optOut()` deletes the waiting events (uncounted) and `optIn()` uploads them.
- `clearLocalData()` goes through the write channel: deletes every segment, `PersonStore`, drop
  counters and retry state, renews `device_id`; keeps the consent choice, last version/build and the
  remote config cache; produces no event and counts nothing. `reset()` never renews `device_id`.
- No IP flag or header: IP anonymisation is a backend project setting.

## Testing

- Unit tests first, on the JVM: JUnit 5, MockK, `kotlinx-coroutines-test`, MockWebServer.
  `:sdk-android` tests use Robolectric.
- A small instrumented suite covers what the JVM cannot: process kill and recovery, real offline
  transitions, R8-minified release build. Keep it small and slow-but-real.
- Test names are backtick sentences: `` `does not upload while offline` ``. Group with
  `@Nested inner class`.
- Coroutine tests use `runTest` with `StandardTestDispatcher` and `advanceUntilIdle()`. No
  `Thread.sleep`.
- Tests for the interceptor run against a MockWebServer through a real `OkHttpClient`.
- Before opening a PR: `ktlintFormat`, `test`, `apiCheck` all green, and one manual run of `:app`
  against `fake-server` for anything touching upload, storage or identity.

## Code style

- `ktlint` official style, enforced by `ktlintFormat`. Explicit imports, no wildcards.
- Readability over cleverness. Small functions, one responsibility.
- `class` for public types (never `data class`, its `copy`/`componentN` become API).
  `data class` for internal DTOs. `sealed` for closed hierarchies. `enum` for fixed sets.
- Constants in `companion object` or a private top-level `const val`.
- Logging (see Logging: Decisions). Public `fun interface RubiconLogger { log(level, message,
  throwable) }`, owned by the instance; `logging.logger = null` -> Logcat tag `Rubicon` in
  `:sdk-android`, no-op in core; never replace a developer's logger. Levels `DEBUG|INFO|WARN|ERROR|OFF`,
  default `WARN`, no runtime setter, `debug` does not change it. ERROR = data lost or SDK cannot work;
  WARN = misconfiguration or dropped/changed data; INFO = a few lifecycle lines; DEBUG = every step.
- Log only through the internal inline extensions (`log.debug { }`, `log.warn(Code.X) { }`,
  `log.error(Code.X, e) { }`): level and repeat limit checked before the lambda runs. Every WARN/ERROR
  has a stable `Code`, logged once per process per code (later ones at DEBUG). Never `println` or
  `Log.*` directly.
- Never log property or trait values, `userId`, `deviceId`, session id, the API key, header values,
  bodies or query strings, at any level; names, keys, types, counts, codes, `message_id` are fine.
- The developer's logger and `eventObserver` run behind a guard: a throwing logger -> Logcat for the
  rest of the process; a throwing observer -> switched off; calls into the SDK from inside them are
  ignored on that thread. `eventObserver` gets `Queued(event)`, `Sent(messageIds)`,
  `Dropped(messageIds, reason)`. No `onError` callback.

## Git and PRs

Branching, commit format and the PR template are defined in `README.md` and
`.github/pull_request_template.md`. In short: Conventional Commits, feature branches squash-merged
into `develop`, `master` only receives releases.

A PR description leads with the problem, then the change, then how it was tested. Public API
changes are named explicitly at the top.

## Keeping this file honest

This file is loaded into every AI session and read by every contributor. When a rule here stops
matching the code, fix one or the other in the same PR. Keep it under 300 lines; put module-specific
detail in a `CLAUDE.md` inside that module, not here.
