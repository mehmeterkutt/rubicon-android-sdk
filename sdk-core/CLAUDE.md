# sdk-core

Pure Kotlin/JVM, no Android import. The platform comes through the internal `Platform`; tests replace
its clock, stores, network monitor, HTTP transport, dispatchers and random through the
`@RubiconInternal` factory overload (Configuration 5). The fakes live in `testFixtures`, package
`com.rubicon.analytics.testing`, gathered by `TestPlatform`. Property validation and `beforeSend`
rules are in the root `CLAUDE.md`.

## Event model

- Top level is the event's identity (Event Model 1): `type`, `event` (track) or `name` (screen),
  `source` (`app`/`auto`, set only by the SDK), `message_id`, `timestamp`, `user_id`, `device_id`.
  `properties` (track, screen) and `traits` (identify) belong to the developer; the SDK never writes
  into them. Super properties merge into `properties`, the event's own key wins. `context` belongs to
  the SDK (`app`, `device`, `os`, `screen`, `network`, `locale`, `timezone`, `session`, `library`) and
  is read-only for hooks (Context 4). Wire keys are `snake_case`.
- `sealed class Event`: `TrackEvent`, `ScreenEvent`, `IdentifyEvent`; wire `type` `track`, `screen`,
  `identify` (pending backend confirmation). KDoc tells hook authors to keep an `else` branch;
  subtypes may be added in minor versions (Event Model 2).
- `message_id`: UUIDv7 from the shared monotonic generator (which also makes `device_id` and the
  session id), created on the calling thread, persisted, never regenerated (retry, batch split,
  restart) (Event Model 4).
- Time is `Long` epoch ms from the injected `Clock` at call time; `timestamp`, `sent_at` and
  `context.session.started_at` are epoch ms. `Date` in properties or traits ->
  `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` by our own formatter (no `java.time`, no `SimpleDateFormat`).
  `sent_at` is in the envelope, rewritten on every attempt (Event Model 5, Serialization 4).
- Events and `EventContext`: immutable public `class`es, `internal` constructors, a hand-written `copy`
  limited to `name` and `properties`/`traits` (`@JvmOverloads`, parameters only appended). Hooks never
  change `context`, `source`, `message_id`, `timestamp`, `type`, `user_id`, `device_id`. `toString`:
  type, name, message id only. No `equals`/`hashCode` overrides (Event Model 6).

## Configuration

- `AnalyticsConfig(apiKey)`. Top level: `host` (`DEFAULT_HOST` SaaS URL, overridable for on-prem,
  optional path prefix, trailing slash normalized; endpoints `{host}/v1/batch`, `{host}/v1/config`),
  `debug`, `beforeSend`. Groups as `val`s: `flush`, `session`, `autocapture`, `context`, `privacy`,
  `logging`, `network`, `remote` (Configuration 2). About twenty public settings; tuning values (batch
  and queue limits, backoff, retry limits, HTTP timeouts, compression) are internal constants; making
  one public is a design discussion.
- No platform types (`Context` is only a factory parameter); platform-neutral names
  (`Autocapture.APP_LIFECYCLE`), `:sdk-android` decides the mechanism; Android-only options live in
  the options class of the feature that needs them (Configuration 6).
- The factory validates (Configuration 3). Debug: throw. Release: blank key or invalid host -> no-op
  and ERROR; out-of-bounds numbers clamped, WARN; contradicting values -> group defaults, WARN.
  Log the effective host once at INFO. A second factory call throws in debug and returns the
  existing instance (first config wins) in release.
- Frozen after the factory by the freezing property delegate: a write throws in debug, logs one WARN
  and is ignored in release. Runtime changes only through `Analytics` methods (Configuration 4).
- Remote config (Configuration 1): background `GET {host}/v1/config` with ETag; it only restricts.
  Allowlist: `enabled` (kill switch), autocapture types to switch off, `flush_interval_ms`, `flush_at`,
  `sample_rate`. Saved, applied next launch; `enabled = false` and `NETWORK_REQUESTS` off apply at once
  (runtime flags outside the snapshot). Failure -> last valid remote values or code. Same validator;
  an invalid key is ignored with WARN.
- `network.headers` never override `Authorization`, `Content-Type`, `Content-Encoding`, `User-Agent`
  or the SDK's own `X-` headers. No IP flag or header: that is a backend setting (Privacy 6).

## Identity, super properties and privacy

- `device_id`: UUIDv7 created on first launch. `user_id`: set by `identify`, `null` = anonymous. No
  identified flag, no alias ever, no group (Identity 1, 6). Missing file: generate and write.
  Unreadable (corrupt, Direct Boot): temporary in-memory id, never overwrite, WARN.
- `identify(userId, traits)` (Identity 3, 4): anonymous -> set and persist, send `IdentifyEvent`; same
  id with traits -> `IdentifyEvent` only; same id without -> nothing; a different id while identified
  -> clear `PersonStore`, then identify, INFO without ids (no debug exception). Blank or over 255 chars
  -> throw in debug, WARN and ignore in release. Never trim. Traits are set-only, sent only in
  `IdentifyEvent`, never stored; anonymous users have none (device data goes through `register`).
- `PersonStore`: `user_id`, super properties, open session. `DeviceStore`: `device_id`, consent, last
  version and build, remote config cache, retry state, drop counters. For a new value ask: "if another
  person logs in on this device, should it carry over?" Yes -> `DeviceStore`, else `PersonStore`. A
  unit test asserts each store's exact key set. `reset()` clears `PersonStore` only, leaves the queue,
  sends no event, never renews `device_id` (Identity 5).
- Merge is implicit: both ids on every event; anonymous events belong to the first `user_id` seen
  after them on the device; two different non-null `user_id`s never merge (Identity 2).
- `identify`, `reset`, `register`, `unregister` apply synchronously in memory and asynchronously to
  disk, so `identify()` then `track()` sees the new identity. Super properties are validated at
  `register` with the property rules; `null` is stored and sent (Context 5).
- `privacy.defaultConsent`: `GRANTED` (default) or `PENDING` (write, never upload, until decided);
  `privacy.persistConsent` (default `true`) stores the choice, which beats the default (Privacy 2, 4).
  The writer loads `DeviceStore` before anything, so an opted-out user's startup events never reach
  disk; the factory never reads it on the main thread (only an early `isOptedOut()` may, once).
- `optOut()` (Privacy 1): no new events of any kind (automatic, session, `SDK Diagnostics`), the open
  session ends silently; queued events still upload; `identify`, `register`, `reset` change local
  state without events. `optIn()`: resume, no event. From `PENDING`, `optOut()` deletes waiting events
  uncounted and `optIn()` uploads them.
- `clearLocalData()` (write channel): deletes segments, `PersonStore`, drop counters, retry state;
  renews `device_id`; keeps consent, last version/build, remote config; no event or count (Privacy 3).

## Session state

- Every event carries `context.session.id` (UUIDv7) and `started_at`; changes are announced
  internally (`@RubiconInternal`), not publicly (Session 1, 2).
- Foreground (`ProcessLifecycleOwner`) keeps a session alive; backgrounding starts the countdown;
  returning before `session.backgroundTimeoutMs` (default 5 min, 10 s..2 h, not remote) continues it;
  maximum 12 h (internal). No inactivity timeout, no timers: evaluate on foreground, on each event and
  at process start; background time from `elapsedRealtime()` (Session 3, 4). Background events use a
  still-valid session but never extend it; after it ends they carry none and never start one. A
  process started in the background has no session until foreground.
- `Autocapture.SESSIONS` (on): `Session Started` right before the event that starts a session;
  `Session Ended` on detection, backdated to last activity, `duration_ms`, ended session (Session 5).
- In `PersonStore` (Session 6): id and `started_at` at start; last activity (wall clock) on `onStop`
  and at most every 30 s in foreground. Process start: clock moved back -> end; within timeout and
  maximum -> continue; else backdated `Session Ended` and clear.
- `reset()` and a different user: `Session Ended` with the old `user_id` first, then clear
  `PersonStore`; the next foreground event starts a new session with `Session Started`.

## Writer, uploader and flushing

- Two single-consumer coroutines in the SDK scope (`SupervisorJob` + `CoroutineExceptionHandler`),
  each on `Dispatchers.IO.limitedParallelism(1)` from `Platform`, never `Dispatchers.Main` (Queue 1).
  **Writer**: hooks, second validation, serialization, append; the only disk writer (identity,
  session, device state too). **Uploader**: one request in flight, deletes only after acknowledgement.
- Write channel of 1000, `trySend`; full -> drop (`backpressure`). Every flush is a `Flush` message in
  the write channel (includes all earlier events); the upload signal is `CONFLATED`. `flush()`
  triggers and returns; `flushBlocking(timeout)` is `internal`, for tests and shutdown only.
- Triggers (Queue 2, 3, 7): count (`flush.at`, 30, 1..100, all pending); interval (`flush.intervalMs`,
  60 s, 1 s..10 min, only while events are pending, from the first); `ON_STOP`; network regained;
  `flush()`; `flush.immediateEvents` (final name after hooks, flush after persisting, never a bypass).
  No upload offline, during backoff (network never cuts a `Retry-After`), opted out or disabled. No
  public flush policy, pause, resume or WorkManager (Queue 6). `debug` never changes thresholds;
  defaults are to be tuned from field data.
- Batches: at most 100 events and 512 KB before gzip, oldest first, sequential, stop at the first
  failure (Queue 4). Limits: 1000 pending and 3 days from `timestamp`; over the count drop the oldest
  down to 900 in one step; expired ones deleted unsent, at start and before every upload (Queue 5).

## Storage and JSON

- Stream files, never a whole queue file in a `String`. Plain `java.io` behind the internal
  `EventStore` and stores; never replaceable, not encrypted (Disk 6).
- `noBackupFilesDir/rubicon/`: `device.json`, `person.json`, `remote-config.json`,
  `events/v1/{keyHash}/` (16 hex of the key's SHA-256); other key directories are deleted at startup
  (`api_key_changed`) (Disk 2).
- Segmented log (Disk 1, 3): the open `{index}.tmp` (fixed width, next = max in directory + 1, no
  stored counter) closes at 100 events, 512 KB or flush and is renamed; closed segments are immutable;
  one segment = one batch, deleted after 2xx. One compact UTF-8 line per event, exactly the wire event,
  one `write()` of line + `\n`. Body `{"sent_at":..,"batch":[` + comma-joined lines + `]}`, streamed
  through gzip.
- fsync only when a segment closes (before rename), on `ON_STOP`, and on every store write. A `.tmp`
  at startup: keep complete lines, drop a half last line (`corrupt`), close it (Disk 4).
- Bad lines are skipped (`corrupt`), never logged (ERROR with segment, line number, `message_id`).
  Unreadable segment: retried, deleted after 3 consecutive failures (`UNREADABLE_SEGMENT`). Unknown
  files: deleted with WARN. No quarantine, no free-space check (Disk 5).
- Stores (Disk 7): whole-file JSON with `"v"`, `.tmp` + fsync + rename, coalesced on the writer.
  Missing -> create; unreadable (corrupt, Direct Boot, newer `v`) -> in-memory, never overwrite.
- Only the internal conversion layer touches `org.json`: `JSONObject.NULL` for null (a Kotlin null
  removes the key); read with `has()`, never trust `opt*` defaults (Serialization 1). Tests compare
  structurally and parse every line with an independent parser; golden files per event type are the
  backend contract (Serialization 5).

## HTTP and retry

- `HttpTransport` (HTTP 2 to 6): gzip in memory, `setFixedLengthStreamingMode`, never chunked. Connect
  15 s, read 20 s, 45 s watchdog calling `disconnect()`. `Authorization: Bearer {apiKey}` (never in the
  body); User-Agent `rubicon-android/{version} (Android {os}; API {level})`; `useCaches = false`;
  `instanceFollowRedirects = false` (a 3xx is an ERROR). One attempt; no retry, backup host or
  `TrafficStats` tagging.
- Never throws: `HttpResult` = `Success`, `HttpError(code, body, retryAfterMs)`, `NetworkFailure(kind)`
  (`TIMEOUT|DNS|CONNECT|TLS|IO|BLOCKED|UNEXPECTED`). Read at most 4 KB, always drain and close.
  `Retry-After` in seconds or HTTP date. Only after `CONNECT`/`DNS` failures resolve the host:
  loopback or any-local -> `BLOCKED` (skipped for `localhost` and IPs). Never log bodies.
- One `when` over `HttpResult` (Retry 1, 4): 2xx delete. `TIMEOUT`, `CONNECT`, `IO`, `DNS`, 408, 5xx
  except 501/505, 1xx: keep, back off. 429, or 503 with `Retry-After`: pause. 400, 422, other 4xx:
  delete (`rejected`). 401, 403, 404, 410, 413, 3xx, 501, 505, `TLS`, `BLOCKED`, `UNEXPECTED`: keep,
  pause 1 h ±20 percent, ERROR once. Offline is waiting. The server answers 200 for every well-formed
  batch; success bodies are never parsed.
- Backoff 2 s, 10 s, 30 s, 2 min, 5 min, then 10 min, jitter x0.5..1.5, reset on success; retries by
  itself. Network regained ends only network waits. `Retry-After` replaces the table: cap 1 h, floor
  1 s, jitter x1.0..1.1, never shortened (Retry 2, 3). Poison batch: after 5 consecutive transient
  failures of a segment try the next; if that succeeds move the failing one to the end; drop it
  (`retry_exhausted`) after 10 while others succeed.
- `paused_until`, `pause_kind`, `step`, `last_failure` and per-segment attempts live in `DeviceStore`,
  restored at start; clamp `paused_until` to the kind's maximum if the clock moved back (Retry 5).

## Logging and diagnostics

- `fun interface RubiconLogger { log(level, message, throwable) }`, owned by the instance;
  `logging.logger = null` -> no-op in core, Logcat on Android; never replace a developer's logger
  (Logging 1).
- `DEBUG|INFO|WARN|ERROR|OFF`, default `WARN`, no runtime setter, independent of `debug`. ERROR = data
  lost or SDK cannot work; WARN = misconfiguration, dropped or changed data; INFO = a few lifecycle
  lines; DEBUG = every step (Logging 2). Level and repeat limit are checked before the lambda runs.
  A stable `Code` per WARN/ERROR (`docs/issue-codes.md`), once per process, later at DEBUG (Logging 3);
  `repeatKey` makes it once per key where a decision says so (Serialization 3). New code: add it to
  `Code` and the catalogue in the same pull request (`IssueCodesTest`).
- Logger and `eventObserver` run behind a guard: a throwing logger -> the platform default (Logcat on
  Android) for the rest of the process; a throwing observer -> switched off; log lines and observer
  updates they cause on their own thread are ignored. `eventObserver`: `Queued(event)`, `Sent(messageIds)`,
  `Dropped(messageIds, reason)`; no `onError` (Logging 4).
- Per-reason drop counters in `DeviceStore`: `queue_full`, `expired`, `backpressure`, `before_send`,
  `storage_error`, `corrupt`, `api_key_changed`, `oversize`, `rejected`, `retry_exhausted`; every
  WARN/ERROR code increments an issue counter (Queue 5).
- `SDK Diagnostics` (Logging 5): right before a flush, if a counter is non-zero and 1 h has passed
  since the last (time in `DeviceStore`; a future time counts as now), one event with `period_start`,
  `dropped{total,...}`, `issues{CODE: n}` (no text, values or stack traces). Counters reset when it is
  written; its own drop is not counted. Sampled by `device_id` (remote `diagnostics_sample_rate`); off
  with `logging.sendDiagnostics = false`; never while opted out, pending or killed; not `Autocapture`.
