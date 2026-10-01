# sdk-android

The Android side: the factory, the Android `Platform` implementations, lifecycle, screens, deep
links, context collection, the network monitor and the OkHttp interceptor. Public API lives in
`com.rubicon.analytics`, the interceptor in `com.rubicon.analytics.network`, implementations in
`com.rubicon.analytics.internal.android`. Pointers such as (Lifecycle 3) name the Confluence decision.

## Factory and process

- `Analytics(context, config)` is a top-level function; Java calls
  `RubiconAnalytics.create(context, config)` (`@file:JvmName`, `@JvmName`, D3). It keeps the
  application context only.
- Main process only (Configuration 3, Disk 2d): compare `applicationInfo.processName` (falls back to
  `packageName`) with `Application.getProcessName()`, or `runningAppProcesses` matched on
  `Process.myPid()` below API 28. Never `/proc` or `ActivityThread`. Another process gets a no-op and
  one INFO line, in debug too; an unknown name is assumed main, with a WARN.
- Nothing is read on the main thread in the factory. Files live under `noBackupFilesDir` (layout in
  `sdk-core/CLAUDE.md`), never in `SharedPreferences` or a backed-up directory: Auto Backup must never
  clone a `device_id` onto another phone (Identity 1, Disk 2a).
- `logging.logger = null` -> Logcat with tag `Rubicon`; the Logcat logger
  (`internal/android/LogcatLogger.kt`) is the only code that may call `android.util.Log` (Logging 1).

## Lifecycle and automatic events

- Automatic events have plain Title Case names, no `[auto]` prefix, and `source = EventSource.AUTO`
  (`"source": "auto"`); developer calls are always `APP`. Their properties are SDK-defined
  `snake_case` (Lifecycle 5).
- Inventory (Lifecycle 1): `Application Installed`, `Application Updated`, `Application Opened`
  (`from_background`), `Application Backgrounded`, `Session Started`/`Ended`, automatic
  `ScreenEvent`, `Deep Link Opened`, `Network Request`. No element interaction, no crash events.
- `Autocapture` defaults (Lifecycle 6): `APP_LIFECYCLE`, `INSTALL_UPDATE`, `SESSIONS`,
  `SCREEN_VIEWS`, `NETWORK_REQUESTS` on; `DEEP_LINKS` off.
- The core's `AppLifecycle` makes these events on the writer; this module only reports `ON_START`
  and `ON_STOP` (`AndroidLifecycleSource`) and the package facts (`ProcessInfo.installedApp()`).
- Foreground and background come from `ProcessLifecycleOwner`; the observer is added when the SDK is
  created (`AndroidLifecycleSource.attach()` in the factory), on the main thread (post if needed), so
  a cold start's first screen is inside the session. Always call
  `AppInitializer...initializeComponent(ProcessLifecycleInitializer)` in case the app removed the
  Startup provider; lifecycle 2.6+ then throws, and an activity counter takes over (700 ms to the
  background, rotation not counted, one INFO) (Lifecycle 2).
- Network callbacks below API 26 may report a returning network with `onAvailable` alone: handle it.
- Install and update (Lifecycle 4) are evaluated on the first foreground of a process, with state in
  `DeviceStore` (version, build). No record and `firstInstallTime == lastUpdateTime` -> Installed; no
  record otherwise -> Updated without previous version; a different build -> Updated
  (`is_downgrade: true` only when lower). `installed_at`/`updated_at` are ISO strings. No install
  referrer.

## Screens and deep links

- `ActivityCapture` sends screens and links through the core's `AutomaticCapture` (`source = AUTO`,
  gated by `Autocapture` in the writer); it is registered only when one of the two is enabled.
- Screens (Lifecycle 3): activities only; skip a repeated resume of the same activity while in the
  foreground and a recreation by rotation, send again after the background. Name = the
  `@ScreenName` value, else the simple class name minus a trailing `Activity`; never titles or
  labels. One property, `name_source` (`annotation` or `class`). No Fragment or Compose automation.
  `consumer-rules.pro` keeps `@ScreenName`.
- Deep links (Privacy 5b): scheme, host, path, the `utm_*` query parameters and the referrer package
  or host only; never other parameters or the fragment. `singleTop` links are caught with
  `addOnNewIntentListener` when the activity is a `ComponentActivity` and `androidx.activity` is
  present (class presence check on `OnNewIntentProvider`, D13); otherwise the guide advises
  `setIntent()`, read on resume. Each intent counts once (weak identity set). Properties: `scheme`,
  `host`, `path`, the `utm_*` present, `referrer`.

## Context collection

- `AndroidContextProvider` implements the core `ContextProvider` (part of `Platform`); `ContextStep`
  adds its values in the pipeline. Nothing is collected in the factory or on the main thread; static
  values are collected once on the worker, on first need (Context 1).
- Cached and refreshed by signals (Context 2): network type and carrier by the `NetworkMonitor`
  callback; screen and locale by `onConfigurationChanged`; timezone read per event; app, device, OS
  and library never. Time-dependent values (network, screen, locale, timezone) are captured from the
  cache on the calling thread at call time; nothing is read at send time.
- The cache is filled and re-read on the writer (`ContextProvider.refresh`, at startup and before each
  message). Before the first fill and after a configuration change, call-time values are `pending`
  and the writer fills them when it processes the event. `onConfigurationChanged` only counts.
- Screen = the whole display (`getRealMetrics`), short side as width, so it never implies orientation.
  `device.type`: `tv` (television UI mode or leanback), `tablet` (smallest width 600 dp), `phone`.
- Carrier = `networkOperatorName` of the default data subscription (`getDefaultDataSubscriptionId`,
  `createForSubscriptionId`, API 24), falling back to the default `TelephonyManager`. Dual SIM must
  report the data SIM.
- Fields (Context 3, to be revisited): `app.name|version|build|namespace`,
  `device.manufacturer|model|type|is_emulator`, `os.name|version|api_level`,
  `screen.width|height|density`, `network.type|carrier`, `locale`, `timezone`,
  `library.name|version`. Unknown values are omitted, never `null` or `""`. Never collected: location,
  advertising ids, IP, battery, memory, storage, orientation, bluetooth, NFC, cellular technology,
  user agent, `Build.DEVICE`, installer.
- `context.disabledFields: Set<ContextField>`, one enum value per field; `library.*` cannot be
  disabled; a disabled field is never read (Context 6). No ready-made sets (no `CHILD_DIRECTED`); the
  integration guide's checklist names `NETWORK_CARRIER`, `LOCALE`, `TIMEZONE` (Privacy 5a).

## Permissions and network monitor

- The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`. If the app removes the latter: assume
  connected, omit `network.type`, read the carrier at most every 60 s, WARN once
  (`NETWORK_STATE_PERMISSION_MISSING`).
- One default-network callback for the whole SDK, registered once in `try/catch(Throwable)` and never
  unregistered (a request-based fallback below API 24); on failure query before each flush.
  Connected = `NET_CAPABILITY_INTERNET`; never require `VALIDATED` (Retry 6). The same monitor feeds
  context and retry.

## HTTP call capture (package `network`)

- `RubiconNetworkInterceptor(analytics, hosts, maskPathIds = true)` (D12) lives inside `sdk-android`
  so apps need one dependency. Its options are constructor parameters, not config. It is the only
  OkHttp code in the SDK: nothing outside `com.rubicon.analytics.network` references `okhttp3.*`, so
  apps without OkHttp never load it (Configuration 5).
- OkHttp is `compileOnly`; the artifact ships none and the app's version is used at runtime.
  `consumer-rules.pro` has `-dontwarn okhttp3.**` so R8 does not fail on the missing classes.
- Compile against the **oldest** supported OkHttp (4.12), never the newest. Use only Interceptor API
  identical across 4.x and 5.x; write null-safe access (`?.`) even where 5.x made something non-null.
  Raising the OkHttp compile version is a minor release with a changelog entry, never a patch.
- CI runs the interceptor tests against 4.12 and the latest 5.x that builds
  (`-PokhttpTestMajor=5`), with a real `OkHttpClient` against MockWebServer.
- The interceptor **never** modifies the request, **never** throws, and **never** captures requests
  to the SDK's own endpoint. Opted out, `NETWORK_REQUESTS` off (checked per request, remote switch-off
  included), or a non-SDK `Analytics` instance: pass through with `chain.proceed(request)`.
- `Network Request` properties: `method`, `url` without query or fragment, `host`, `path`,
  `status_code`, `error` as a short class name (never a message), `duration_ms`, `request_bytes`,
  `response_bytes`.
- Headers and bodies are never captured, by any option. Query and fragment are never sent. Path
  segments that are all digits, a UUID or hex longer than 16 characters become `:id` in `path` and
  `url` (`maskPathIds`, default `true`) (Privacy 5c). Which hosts to capture is opt-in; nothing is
  captured for an unconfigured host. Hosts: an exact name or `*.domain.tld` (subdomains only).
- `error`: `canceled`, `timeout`, `unknown_host`, `connect`, `tls`, else the simple class name.

## Tests

- Robolectric runs with JUnit 4 (`@RunWith(RobolectricTestRunner::class)`; it has no JUnit 5 runner);
  pick API levels with `@Config(sdk = [...])` and cover both sides of every API check. It runs API 23
  to 35 here: it has no 21 or 22 (the emulator suite covers them) and 36 needs Java 21.
- Test doubles come from `testFixtures(project(":sdk-core"))`.
- Android lint does not see the compiler's module-wide opt-in: if it reports `UnsafeOptInUsageError`
  for an internal class, mark the class `@RubiconInternal` (it is internal, so nothing leaks).
- Never use the real `ProcessLifecycleOwner` in Robolectric: it stays bound to the first
  `Application` of a sandbox, so such tests depend on their order. Inject a `LifecycleRegistry`.
- Unit tests run on the debug variant only (`testDebugUnitTest`).
