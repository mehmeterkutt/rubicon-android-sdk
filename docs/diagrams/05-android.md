# 5. Android

[Index](README.md) · Previous: [4. Identity, sessions and privacy](04-identity-session-privacy.md)

## 5.1 Startup

`Analytics(context, config)` in `Application.onCreate`. The calling thread reads nothing from disk;
the writer loads the files first, and every call made meanwhile waits for it in call order
(Public API 1, 2; Privacy 2; Disk 2d).

### 5.1a The big picture

```mermaid
sequenceDiagram
    autonumber
    participant App as Application.onCreate
    participant SDK as Analytics(context, config)
    participant W as Writer
    participant U as Uploader
    participant B as Backend

    App->>SDK: create once
    SDK->>SDK: check the configuration, start the two workers,<br/>watch the app's lifecycle and activities
    SDK-->>App: returns at once, nothing read from disk
    Note over App: track() works right away, calls wait in order
    W->>W: load the JSON files and last launch's remote settings
    W->>W: open the event files, check the saved session
    W->>W: the calls made meanwhile, in order
    U->>B: GET /v1/config, once per launch
    Note over U: no upload at start, events left from the last run wait for a flush
```

| If | `Analytics(context, config)` returns |
|---|---|
| Not the app's main process, for example a `:remote` service | An instance that does nothing, one INFO |
| A blank API key or an invalid host | An instance that does nothing, ERROR (throws in debug) |
| Called a second time in the process | The first instance, ERROR (throws in debug) |

### 5.1b The same, in detail

```mermaid
sequenceDiagram
    autonumber
    participant App as Application.onCreate
    participant F as Factory
    participant RC as RubiconCore
    participant W as Writer
    participant U as Uploader
    participant B as Backend

    App->>F: Analytics(context, config)
    F->>F: applicationContext only
    alt not the main process (a :remote service)
        F-->>App: NoOpAnalytics, one INFO
    end
    F->>F: AndroidPlatform (network monitor, context, lifecycle, Logcat)
    F->>RC: create(config, platform)
    alt a second instance in this process
        RC-->>App: the first instance (throws in debug)
    end
    RC->>RC: validate and freeze the configuration
    alt blank key or invalid host
        RC-->>App: NoOpAnalytics, ERROR (throws in debug)
    end
    RC->>RC: stores, consent, identity, transport, remote config client
    RC->>W: start()
    RC->>U: start()
    RC->>F: onCreated(capture)
    F->>F: lifecycle attach (ProcessLifecycleOwner or the fallback)
    F->>F: ActivityCapture registered when screens or deep links are on
    F-->>App: Analytics, returns at once
    Note over W: in the background, on the writer
    W->>W: device.json, person.json, consent, pending identity calls
    W->>W: remote config saved last launch applied (kill switch, sampling, autocapture, flush)
    W->>W: event store opened (other API keys deleted, expired and over-limit trimmed)
    W->>W: network monitor started, context cache filled
    W->>W: SessionManager, EventPipeline, AppLifecycle built
    W->>W: saved session checked (Session Ended if over)
    W->>W: interval started if events were left
    W->>W: lifecycle and network listeners added
    W->>W: already in the foreground? Installed or Updated, Opened, Session Started
    W->>W: stores written, writer.started completed
    W->>W: messages that waited, in call order
    Note over U: waits for writer.started
    U->>U: retry state restored from device.json
    U->>B: GET /v1/config, once per launch, if connected
    B-->>U: 200, 304 or 404
    U->>U: waits for an upload signal (no upload at startup)
```

## 5.2 Installed, updated or nothing

On the first foreground of each process, `AppLifecycle` compares `PackageManager` with the last
version and build in `device.json` (Lifecycle 4). The build is the version code.

```mermaid
flowchart TD
    first(["First foreground of the process"]) --> pm{"PackageInfo readable,<br/>with a build?"}
    pm -->|"no"| nothing(["Nothing"])
    pm -->|"yes"| last{"A last build stored?"}
    last -->|"no"| same{"firstInstallTime = lastUpdateTime?"}
    same -->|"yes"| installed(["Application Installed<br/>version, build, installed_at"])
    same -->|"no, the app existed before the SDK"| updatedNew(["Application Updated<br/>version, build, updated_at, no previous values"])
    last -->|"yes, a different build"| updated(["Application Updated<br/>version, build, previous_version, previous_build,<br/>updated_at, is_downgrade when lower"])
    last -->|"yes, the same build"| nothing
    installed & updatedNew & updated & nothing --> store["The version and build are stored either way,<br/>even when the event is off or consent is not given"]
    store --> opened(["Application Opened, from_background false"])
```

| Situation | Stored build | Installed app | Event |
|---|---|---|---|
| First install | none | build 42, installed and updated at the same time | `Application Installed` |
| An app already in use adds the SDK in an update | none | build 42, updated later than installed | `Application Updated` without previous values |
| Update from 41 to 42 | 41 | 42 | `Application Updated`, `previous_build` 41 |
| Rollback from 42 to 41 | 42 | 41 | `Application Updated`, `is_downgrade` true |
| Normal launch | 42 | 42 | none, only `Application Opened` |

Stored either way, so an opt-in or a feature switched on later never reports a false update. After
`clearLocalData()` the last build is kept for the same reason (Privacy 3).

## 5.3 Screen views and deep links

`ActivityCapture` listens to the activity callbacks on the main thread and holds activities only
weakly (Lifecycle 3, 6; Privacy 5b; non-negotiable 5).

```mermaid
flowchart TD
    resumed(["onActivityResumed(activity)"]) --> same{"The same activity as the last resumed,<br/>or its recreation after a rotation?"}
    same -->|"yes, a dialog closed or a rotation"| noScreen(["No screen"])
    same -->|"no"| named{"@ScreenName on the class?"}
    named -->|"yes"| annotated(["screen(value), name_source annotation"])
    named -->|"no"| klass(["screen(class name without Activity),<br/>name_source class"])
    stopped(["onActivityStopped, no activity left started"]) --> forget["Forget the last resumed activity:<br/>back from the background is a new visit"]
```

```mermaid
flowchart TD
    created(["onActivityCreated"]) --> restored{"savedInstanceState?"}
    restored -->|"null, opened now"| check
    restored -->|"restored"| mark["Its intent marked as seen<br/>(counted when first opened)"]
    created --> listen["OnNewIntentProvider listener<br/>androidx.activity 1.7+, behind a class check"]
    listen --> newIntent(["singleTop onNewIntent"]) --> check
    resume(["onActivityResumed<br/>an activity that called setIntent()"]) --> check
    check{"ACTION_VIEW with data,<br/>intent not seen before?"}
    check -->|"no"| skip(["Nothing"])
    check -->|"yes"| event(["Deep Link Opened<br/>scheme, host, path, utm_source, utm_medium, utm_campaign,<br/>utm_term, utm_content, referrer package or host"])
```

| The user | Screen event | Deep link event |
|---|---|---|
| Opens the app on `HomeActivity` | `Home` | none |
| Opens `ProductActivity` annotated `@ScreenName("product_detail")` | `product_detail` | none |
| Rotates the phone | none | none |
| Closes a dialog over the same activity | none | none |
| Goes to the background and comes back to the same activity | `product_detail` again | none |
| Taps `shop://product/42?utm_source=mail&token=x` | the opened activity's screen | `Deep Link Opened`: scheme, host, path, `utm_source`; never `token` |

Never another query parameter and never the fragment: the list is fixed, because an extensible one
invites tokens. A token in a path is the app's to mask in `beforeSend`.

## 5.4 Network request capture

`RubiconNetworkInterceptor` is added by the app to its own `OkHttpClient`. It never changes the
request or the response and never throws anything the call did not throw (Lifecycle 1, 6; Privacy
5c; D13).

```mermaid
flowchart TD
    req(["intercept(chain)"]) --> captures{"The SDK's instance,<br/>NETWORK_REQUESTS configured and not switched off remotely,<br/>collecting, not the SDK's own host,<br/>host matches a pattern?"}
    captures -->|"no"| pass(["chain.proceed(request), nothing recorded"])
    captures -->|"yes"| timed["Start time, chain.proceed(request)"]
    timed --> result{"Response or exception?"}
    result -->|"response"| ok["status_code, response_bytes when declared"]
    result -->|"exception"| err["error: canceled, timeout, unknown_host,<br/>connect, tls or the class name; then rethrown"]
    ok & err --> props["method, url and path without query or fragment,<br/>host, duration_ms, request_bytes;<br/>ids in the path become :id (digits, UUID, hex over 16)"]
    props --> track(["Network Request, source AUTO"])
```

| Pattern | Matches | Does not match |
|---|---|---|
| `api.shop.com` | `api.shop.com`, `API.shop.com` | `x.api.shop.com` |
| `*.shop.com` | `api.shop.com`, `a.b.shop.com` | `shop.com` |
| `*.com`, `https://x.com`, `x.com/path` | invalid: throws in debug, else WARN and ignored | |

Headers, bodies, query strings and fragments are never read, only the bodies' declared lengths.

## 5.5 Context collection

Every event carries `context`. Each value is read where it is cheapest and safest; a value that
cannot be read is left out, and a field in `context.disabledFields` is never read (Context 1 to 3, 6).

```mermaid
flowchart LR
    subgraph once["Static: read once on the writer"]
        app["app: name, version, build, namespace"]
        dev["device: manufacturer, model, type (phone, tablet, tv), is_emulator"]
        os["os: name, version, api_level"]
    end
    subgraph cache["Cache: written by the writer and the network callback,<br/>read by the calling thread"]
        screen["screen: width (short side), height, density"]
        locale["locale"]
        net["network: type, carrier (the data SIM)"]
    end
    subgraph call["Read per event on the calling thread"]
        tz["timezone"]
    end
    subgraph pipe["Added by the pipeline"]
        session["session: id, started_at"]
        lib["library: name, version"]
    end
    config(["onConfigurationChanged, main thread"]) -->|"marks the cache stale"| cache
    cb(["Network callback thread"]) -->|"type and carrier"| net
    writer(["Writer, before its next message"]) -->|"reads screen and locale again"| cache
```

```mermaid
flowchart TD
    ev(["An event is captured on the app thread"]) --> fresh{"Cache filled and not stale?"}
    fresh -->|"yes"| values["Screen, locale, network values taken now<br/>with the timezone"]
    fresh -->|"no: before the first refresh,<br/>or after a configuration change"| pending["Marked pending, timezone only"]
    pending --> later(["The writer refreshes first, and the event<br/>gets the new values when it is written"])
    values --> done(["Call-time values travel with the event"])
```

Without a network callback (no permission, or registration failed) the writer reads the network type
and carrier at most once a minute. Without `ACCESS_NETWORK_STATE` the SDK assumes it is connected.

## 5.6 Remote configuration

Remote values can only restrict what the code allows. One fetch per launch, in the background; the
saved values apply at the next launch, except two switches that apply at once (Configuration 1).

```mermaid
sequenceDiagram
    autonumber
    participant W as Writer
    participant D as device.json
    participant U as Uploader
    participant B as Backend
    participant RF as RuntimeFlags

    Note over W,RF: launch N
    W->>D: saved values and ETag
    W->>W: validate against the code, ignore what would widen it
    W->>W: snapshot = code minus autocapture_off, larger flush_at and interval
    W->>W: sample_rate by device_id, enabled
    alt enabled false, or the device outside the sample
        W->>RF: stopCollecting (nothing created, nothing uploaded)
    end
    U->>B: GET /v1/config, If-None-Match ETag
    alt 200
        B-->>U: JSON object
        U->>D: saved with its ETag, for launch N+1
        opt enabled false
            U->>RF: stopCollecting now
        end
        opt network_requests in autocapture_off
            U->>RF: stopCapturingNetworkRequests now
        end
    else 304
        B-->>U: not modified, nothing changes
    else 404
        B-->>U: no remote config for this key
        U->>D: saved values deleted
    else other error or no network
        U->>U: WARN REMOTE_CONFIG_FAILED, last values kept
    end
    Note over W,RF: launch N+1 starts with what was saved
```

| Key | Effect | When |
|---|---|---|
| `enabled: false` | The kill switch: nothing collected or uploaded | At once and at the next launch |
| `enabled: true` | Changes nothing; turning back on waits for the next launch | Next launch |
| `autocapture_off: ["network_requests"]` | The interceptor stops | At once |
| `autocapture_off: [other types]` | Those automatic events stop | Next launch |
| `flush_at`, `flush_interval_ms` | Only larger than the code's values | Next launch |
| `sample_rate` | The share of devices that collect, by `device_id` | Next launch |
| `diagnostics_sample_rate` | The share of devices that send `SDK Diagnostics` | Next launch |
| An unknown key | Ignored at DEBUG, for newer SDKs | |
| A value that is invalid or would widen the code | Ignored, WARN `REMOTE_CONFIG_INVALID_VALUE` | |

The fetch runs also while consent is pending or the user opted out: it carries no personal data
(Privacy 4).

## 5.7 SDK Diagnostics

The SDK's health as counters, without text, values or stack traces. Every WARN and ERROR code and
every drop is counted in `device.json`; the counters go out as one event at most once an hour
(Logging 5).

```mermaid
flowchart TD
    warn(["A WARN or ERROR with a code"]) --> issues["issues[CODE] + 1"]
    drop(["An event dropped"]) --> dropped["dropped[reason] + n"]
    issues & dropped --> stored[("device.json<br/>counters since period_start")]
    flush(["Any flush"]) --> due{"Diagnostics sent by this device<br/>(sendDiagnostics and diagnostics_sample_rate),<br/>collecting and consent GRANTED?"}
    due -->|"no"| skip(["Nothing"])
    due -->|"yes"| any{"Anything counted,<br/>and an hour since the last one?"}
    any -->|"no"| skip
    any -->|"yes"| build["SDK Diagnostics, source AUTO<br/>skips the beforeSend hooks"]
    build --> append{"Stored?"}
    append -->|"yes"| sub["Reported counts taken off,<br/>period_start = now, last sent = now"]
    append -->|"no"| keep(["Counters kept; its own drop is not counted"])
    sub --> close(["Then the flush closes the segment as usual"])
```

```json
{
  "event": "SDK Diagnostics",
  "properties": {
    "period_start": "2026-10-05T08:00:00.000Z",
    "dropped": { "total": 1240, "before_send": 3, "expired": 37, "queue_full": 1200 },
    "issues": { "HOST_BLOCKED": 12, "PROPERTY_DROPPED": 4 }
  }
}
```

| Drop reason | Where |
|---|---|
| `backpressure` | The write channel was full (2.1) |
| `before_send` | A hook dropped, threw or broke the rules (2.2) |
| `oversize` | Over 32 KB after the hooks (2.2) |
| `storage_error` | The append to disk failed |
| `queue_full` | Over 1000 pending, trimmed to 900 (3.2) |
| `expired` | Older than 3 days (3.2) |
| `corrupt` | An unreadable line or segment (3.2) |
| `api_key_changed` | Events of another key at startup (3.2) |
| `rejected` | 400, 422 or another 4xx (3.3) |
| `retry_exhausted` | A poison batch (3.4) |
