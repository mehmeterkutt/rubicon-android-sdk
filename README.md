# Rubicon Android SDK

Product analytics for Android apps, in Kotlin, usable from Java. One dependency, one instance created
in `Application.onCreate`, and the SDK captures installs, opens, sessions, screens, deep links and
HTTP calls on its own; everything is written to disk first and uploaded later, offline included.

- Android 5.0 (API 21) and later; apps on Kotlin 1.9 or newer, or Java only.
- Runtime dependencies: `kotlinx-coroutines-core` and `androidx.lifecycle:lifecycle-process`, both
  at old versions so your app's own choices win.
- The SDK never crashes your app: a failure inside it is logged, and the events affected are lost
  and counted. With `debug = true`, your mistakes (invalid configuration, invalid property, a
  throwing hook) throw so you see them during development.

Contents: [Install](#install) · [Create the instance](#create-the-instance) ·
[Send events](#send-events) · [Identify users](#identify-users) · [Screens](#screens) ·
[Automatic events](#automatic-events) · [HTTP calls](#http-calls) ·
[Consent and privacy](#consent-and-privacy) · [Hooks](#hooks) · [Testing and QA](#testing-and-qa) ·
[R8 and app start-up](#r8-and-app-start-up) · [Java](#java) · [Developing the SDK](#developing-the-sdk)

## Install

Not published yet; the coordinates are provisional until publishing is decided.

```kotlin
dependencies {
    implementation("com.rubicon.analytics:sdk-android:0.1.0")
}
```

`sdk-android` brings `sdk-core`. Its manifest adds `INTERNET` and `ACCESS_NETWORK_STATE`, two normal
permissions that never show a prompt.

## Create the instance

Create one instance, in `Application.onCreate`, and keep it. Creating it reads nothing from disk, so
it is safe on the main thread.

```kotlin
class MyApp : Application() {
    lateinit var analytics: Analytics
        private set

    override fun onCreate() {
        super.onCreate()
        analytics =
            Analytics(
                this,
                AnalyticsConfig("your-api-key").apply {
                    debug = BuildConfig.DEBUG
                },
            )
    }
}
```

- There is no global accessor: pass the instance where it is needed, or provide it with your
  dependency injection.
- A second call returns the first instance (it throws when `debug` is on).
- In any process other than the app's main one, such as a `:remote` service, the instance does
  nothing and writes no file.
- With an unusable configuration (a blank key, an invalid host) the instance does nothing and logs an
  ERROR, or throws when `debug` is on.

### On-premises or a custom host

```kotlin
AnalyticsConfig("your-api-key").apply {
    host = "https://analytics.mycompany.com" // events go to {host}/v1/batch
}
```

`http://` works but sends events unencrypted and logs a warning; Android also requires
`usesCleartextTraffic` or a network security configuration for it.

### Settings

Settings are fixed once the instance is created; a later change is ignored (it throws in debug).

| Setting | Default | Meaning |
|---|---|---|
| `host` | the SaaS URL | Where events are sent |
| `debug` | `false` | Throw on your mistakes instead of logging them; set it to `BuildConfig.DEBUG` |
| `beforeSend` | none | Hooks that see each event before it is stored ([Hooks](#hooks)) |
| `flush.at` | 30 | Upload once this many events are waiting (1 to 100) |
| `flush.intervalMs` | 60 000 | Upload this long after the first waiting event (1 s to 10 min) |
| `flush.immediateEvents` | none | Event names uploaded as soon as they are stored, such as `Purchase Completed` |
| `session.backgroundTimeoutMs` | 300 000 | A session ends after this long in the background (10 s to 2 h) |
| `autocapture.enabled` | all but `DEEP_LINKS` | What the SDK captures by itself ([Automatic events](#automatic-events)) |
| `context.disabledFields` | none | Context fields never read nor sent |
| `privacy.defaultConsent` | `GRANTED` | `PENDING` to wait for the user's consent ([Consent and privacy](#consent-and-privacy)) |
| `privacy.persistConsent` | `true` | The SDK stores the user's choice; `false` if your consent tool owns it |
| `logging.level` | `WARN` | `DEBUG`, `INFO`, `WARN`, `ERROR` or `OFF` |
| `logging.logger` | Logcat, tag `Rubicon` | Your own `RubiconLogger` |
| `logging.eventObserver` | none | Hears what became of each event ([Testing and QA](#testing-and-qa)) |
| `logging.sendDiagnostics` | `true` | Send `SDK Diagnostics`: counts of dropped events and problems, nothing personal |
| `network.headers` | none | Extra headers on every request, for a proxy; the SDK's own headers cannot be replaced |

The server can also switch automatic capture types off, or switch the SDK off entirely, without an
app update; it can never switch anything on.

## Send events

```kotlin
analytics.track("Purchase Completed", mapOf("amount" to 9.99, "currency" to "USD", "items" to listOf("A1", "B2")))
analytics.screen("checkout")
analytics.flush() // usually unnecessary: the SDK uploads on its own
```

- Every method is thread-safe and returns at once; calls from one thread keep their order.
- Names are 1 to 255 characters and are never trimmed.
- Values: `String`, `Boolean`, `Int`, `Long`, `Float`, `Double`, `null`, and lists and maps of these,
  at most three levels deep. `java.util.Date` becomes an ISO 8601 UTC string, an enum its name.
  Anything else (a `JSONObject`, `java.time`, NaN) is dropped with a warning, and throws in debug.
- A string is cut at 1024 characters; an event over 32 KB is dropped.
- Super properties are added to every event until removed; the event's own key wins:

```kotlin
analytics.register("plan", "pro")
analytics.unregister("plan")
```

## Identify users

```kotlin
analytics.identify("user-42", mapOf("plan" to "pro")) // at login
analytics.reset()                                     // at logout
```

- Before `identify` the user is anonymous; events carry `user_id: null` and the installation's
  `device_id`, so the backend joins them to the user identified later on the same device.
- `identify` with a different id while someone is identified starts a new person.
- `reset()` forgets the user, the super properties and the session; the device id stays.
- `analytics.deviceId` is the installation's id, read-only. There is no `userId` getter: you set it.

## Screens

Every activity shown produces a screen view automatically, named after its class without the
trailing `Activity` (`CheckoutActivity` becomes `Checkout`). Name it yourself with `@ScreenName`:

```kotlin
@ScreenName("checkout")
class PaymentFlowActivity : ComponentActivity()
```

- Use the same name on every platform, from your tracking plan, such as `product_detail`; never
  displayed text.
- A screen is tracked automatically or with `screen()`, never both. A single-activity app, such as
  one built with Compose, should remove `Autocapture.SCREEN_VIEWS` and send its screens itself, with
  the route pattern (`checkout/{orderId}`), never the filled route:

```kotlin
LaunchedEffect(backStackEntry) {
    backStackEntry?.destination?.route?.let { analytics.screen(it) }
}
```

- Fragments are not tracked automatically; use `screen()`.

## Automatic events

All carry `source: "auto"`; your own events carry `source: "app"`.

| Type | Events | Default |
|---|---|---|
| `APP_LIFECYCLE` | `Application Opened` (`from_background`), `Application Backgrounded` | on |
| `INSTALL_UPDATE` | `Application Installed`, `Application Updated` (no false installs when you add the SDK to an existing app) | on |
| `SESSIONS` | `Session Started`, `Session Ended` (`duration_ms`) | on |
| `SCREEN_VIEWS` | a screen view per activity | on |
| `DEEP_LINKS` | `Deep Link Opened` | **off** |
| `NETWORK_REQUESTS` | `Network Request`, with the interceptor ([HTTP calls](#http-calls)) | on |

```kotlin
AnalyticsConfig("your-api-key").apply {
    autocapture.enabled += Autocapture.DEEP_LINKS
    autocapture.enabled -= Autocapture.SCREEN_VIEWS
}
```

Deep links send the scheme, host, path, the `utm_*` parameters and the referrer, never other query
parameters or the fragment. A path that holds a token, such as `/reset-password/abc123`, is yours
to mask in `beforeSend`. A link sent to a running `singleTop` activity is captured when the
activity is a `ComponentActivity` (androidx.activity 1.7 or later); in other activities, call
`setIntent(intent)` in `onNewIntent`.

## HTTP calls

Add the interceptor to your own `OkHttpClient` and name the hosts to capture:

```kotlin
val client =
    OkHttpClient.Builder()
        .addInterceptor(RubiconNetworkInterceptor(analytics, setOf("api.myapp.com", "*.cdn.myapp.com")))
        .build()
```

- A host is an exact name, or `*.` for every subdomain (`*.myapp.com` does not match `myapp.com`).
  Nothing else is captured.
- Sent: method, URL and path without query or fragment, host, status code, a short error type,
  duration and sizes. Headers and bodies are never captured. Ids in paths become `:id`
  (`/users/42/orders` becomes `/users/:id/orders`); `maskPathIds = false` keeps them.
- The interceptor never changes your request or response and never throws an error of its own; a
  failed call fails exactly as it would without it. It needs OkHttp 4.12 or later; an app without
  OkHttp simply does not use it.

## Consent and privacy

### Apps that must ask for consent

```kotlin
AnalyticsConfig("your-api-key").apply {
    privacy.defaultConsent = Consent.PENDING // events are stored, nothing is sent
}

// when the user decides, for example from your consent platform:
if (granted) analytics.optIn() else analytics.optOut()
```

| State | Before a decision | `optIn()` | `optOut()` |
|---|---|---|---|
| `GRANTED` (default) | collected and sent | nothing changes | new events stop; stored ones are still sent |
| `PENDING` | stored, not sent (kept at most 3 days) | waiting events are sent | waiting events are deleted |

The user's choice is stored and survives restarts and updates; with
`privacy.persistConsent = false` your consent tool owns it and you call `optIn()` or `optOut()` at
each launch. `isOptedOut()` is `true` while nothing is being sent. An app whose legal reading forbids
even storing events before consent creates the instance only after consent.

### Forget a user

```kotlin
val oldId = analytics.deviceId // send it to your backend to request erasure there
analytics.optOut()
analytics.clearLocalData()     // deletes stored events and the person, renews deviceId
```

### Checklist for child-directed apps and apps that collect no location

- Switch off the fields that hint at a location:

  ```kotlin
  context.disabledFields = setOf(ContextField.NETWORK_CARRIER, ContextField.LOCALE, ContextField.TIMEZONE)
  ```

- Enable IP anonymisation in the project settings on the backend; the SDK never sends an IP address.
- Keep `DEEP_LINKS` off.
- Send no personal data in event properties, traits or screen names.

The SDK never collects location, advertising ids, hardware ids, the IP address, or headers and bodies
of HTTP calls. For the Data Safety form: the installation id is a "device or other ID"; events are
"app activity" and "app info and performance".

## Hooks

A `BeforeSend` hook sees every event before it is stored and returns it, a changed copy, or `null`
to drop it:

```kotlin
AnalyticsConfig("your-api-key").apply {
    beforeSend =
        listOf(
            BeforeSend { event ->
                when (event) {
                    is TrackEvent -> event.copy(properties = event.properties - "email")
                    else -> event
                }
            },
        )
}
```

- Your own events may change name and properties freely. Automatic events may change property
  values only (for example to mask a token); to stop one, switch its type off.
- `event.context` is read-only; switch fields off with `context.disabledFields`.
- A hook that throws drops the event (and throws in debug).

## Testing and QA

- `logging.level = LogLevel.DEBUG` logs every step to Logcat, tag `Rubicon`; never a property value,
  id or key.
- `logging.eventObserver` hears what became of each event: `Queued`, `Sent` or `Dropped` with a
  reason. Useful for a QA screen in debug builds:

```kotlin
if (BuildConfig.DEBUG) logging.eventObserver = EventObserver { update -> Log.d("QA", update.toString()) }
```

- Every warning and error has a stable code, listed in [`docs/issue-codes.md`](docs/issue-codes.md).
- In unit tests, depend on the `Analytics` interface and pass a fake.

## R8 and app start-up

- The SDK's consumer rules are applied automatically; nothing to add to your ProGuard files.
- Foreground detection uses `ProcessLifecycleOwner`, which androidx Startup initializes. If you
  remove Startup's `InitializationProvider`, keep `ProcessLifecycleInitializer`, as WorkManager's
  documentation shows for removing only its own initializer. If it cannot start, the SDK counts
  activities instead and logs one INFO line.

## Java

The same API, with `RubiconAnalytics.create` for the factory:

```java
AnalyticsConfig config = new AnalyticsConfig("your-api-key");
config.setDebug(BuildConfig.DEBUG);
config.getAutocapture().setEnabled(EnumSet.of(Autocapture.APP_LIFECYCLE, Autocapture.SESSIONS));
Analytics analytics = RubiconAnalytics.create(this, config);

analytics.track("Purchase Completed", Collections.singletonMap("amount", 9.99));
OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(new RubiconNetworkInterceptor(analytics, Collections.singleton("api.myapp.com")))
        .build();
```

## Developing the SDK

| Module | Role |
|---|---|
| `sdk-core` | Pure Kotlin/JVM: configuration, event pipeline, storage, upload |
| `sdk-android` | Android factory, platform, automatic capture, interceptor; the one dependency apps add |
| `build-logic` | Gradle conventions, `checkSourceRules` |
| `test-app`, `emulator-tests` | Never published: the app the emulator suite drives, and the suite |

Needs JDK 17 (Gradle picks it through `gradle/gradle-daemon-jvm.properties`) and the Android SDK
(`ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
./gradlew check        # ktlint, tests, apiCheck, lint, coverage, KDoc, R8, source rules
./gradlew ktlintFormat # format before committing
./gradlew apiDump      # after a public API change; commit the .api files
ANDROID_SERIAL=emulator-5554 ./gradlew :emulator-tests:emulatorTest # with an emulator running
```

The rules for contributors are in the `CLAUDE.md` files; the wire
format for the backend is in [`docs/wire-format.md`](docs/wire-format.md). Every flow the SDK
implements is drawn in [`docs/diagrams`](docs/diagrams/README.md).

`master` holds releases, `develop` the next release. Work happens on `feature/<topic>` branches from
`develop`, merged by squash through a pull request.
