# Wire format

What the Android SDK sends to `{host}/v1/batch`: the contract with the backend. The golden files in
`sdk-core/src/test/resources/golden/` are the normative examples; the tests compare what the SDK
writes with them structurally and parse each one with an independent parser (Serialization 5). When
this page and a golden file disagree, the golden file is right and this page is fixed.

Status: complete for the first release (step 8.4), pending backend review.

## Request

- `POST {host}/v1/batch`, `Content-Type: application/json`, `Authorization: Bearer {apiKey}`.
- The body is gzipped (`Content-Encoding: gzip`), or sent uncompressed if compression failed; it
  has a fixed length, never chunked.
- `User-Agent: rubicon-android/{version} (Android {os}; API {level})`.
- One batch holds at most 100 events and 512 KB before compression, oldest first.
- The server answers 200 for every well-formed batch; a success body is never read. Status codes
  and `Retry-After` are handled as the HTTP and Retry decisions say (`sdk-core/CLAUDE.md`).

## Envelope

Golden file: `envelope.json`.

```json
{ "sent_at": 1759147260000, "batch": [ { ...event... }, { ...event... } ] }
```

| Key | Type | Meaning |
|---|---|---|
| `sent_at` | number | Epoch milliseconds when this attempt was sent; rewritten on every retry, so the backend can correct device clock skew against `timestamp` |
| `batch` | array | The events, each exactly as stored on the device |

The same event can arrive more than once (at-least-once delivery: a retry after a lost response, a
process killed during an upload). It always carries the same `message_id`; the backend
deduplicates on it.

## Event

| Key | Type | Present | Meaning |
|---|---|---|---|
| `type` | string | always | `track`, `screen` or `identify` |
| `event` | string | `track` | The event name, 1 to 255 characters |
| `name` | string | `screen` | The screen name, 1 to 255 characters |
| `source` | string | always | `app` for the developer's calls, `auto` for what the SDK captures; new values may come in minor versions |
| `message_id` | string | always | UUIDv7, unique per event, never regenerated |
| `timestamp` | number | always | Epoch milliseconds on the device when the event was created |
| `user_id` | string or null | always | The identified user; `null` while anonymous |
| `device_id` | string | always | UUIDv7 of this installation |
| `properties` | object | `track`, `screen` | The developer's or the SDK's properties; may be empty |
| `traits` | object | `identify` | The user's traits |
| `context` | object | always | What the SDK measured; below |

Property and trait values are strings, booleans, numbers, `null`, lists and objects of those, at
most three levels deep. Dates are strings, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC. A string is at
most 1024 characters, a line at most 32 KB.

Golden files: `track.json`, `screen.json`, `identify.json`.

## Context

A value the SDK could not read is left out, never sent as `null` or `""`; a group with no value is
left out. The app can switch fields off (`context.disabledFields`); `library` is always sent.

| Key | Type | Example |
|---|---|---|
| `app.name`, `app.version`, `app.build`, `app.namespace` | string | `Rubicon`, `1.4.0`, `140`, `com.rubicon.demo` |
| `device.manufacturer`, `device.model` | string | `Google`, `Pixel 8` |
| `device.type` | string | `phone`, `tablet` or `tv` |
| `device.is_emulator` | boolean | `false` |
| `os.name`, `os.version` | string | `Android`, `16` |
| `os.api_level` | number | `36` |
| `screen.width`, `screen.height` | number | `1080`, `2400`: the whole display in pixels, the short side as width |
| `screen.density` | number | `2.625` |
| `network.type` | string | `wifi`, `cellular`, `ethernet`, `other` or `none` |
| `network.carrier` | string | `Vodafone TR`: the operator of the SIM used for mobile data |
| `locale` | string | `tr-TR` (BCP 47) |
| `timezone` | string | `Europe/Istanbul` (IANA) |
| `session.id` | string | UUIDv7; absent outside a session |
| `session.started_at` | number | Epoch milliseconds |
| `library.name`, `library.version` | string | `rubicon-android`, `0.1.0` |

## Automatic events

All have `source: "auto"`. Each type can be switched off by the app (`autocapture.enabled`) or by
remote configuration.

| Event | Type | Properties | Golden file |
|---|---|---|---|
| `Application Installed` | track | `version`, `build` (string), `installed_at` (date) | `application-installed.json` |
| `Application Updated` | track | `version`, `build`, `previous_version` and `previous_build` when known, `updated_at`, `is_downgrade: true` only when the build went down | `application-updated.json` |
| `Application Opened` | track | `from_background` (boolean) | `application-opened.json` |
| `Application Backgrounded` | track | none | `application-backgrounded.json` |
| `Session Started` | track | none; right before the event that starts the session | `session-started.json` |
| `Session Ended` | track | `duration_ms`; timestamped at the session's last activity, with the ended session in `context.session` | `session-ended.json` |
| Screen view | screen | `name_source`: `annotation` or `class` | `screen.json` |
| `Deep Link Opened` | track | `scheme`, `host`, `path`, the `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content` present, `referrer` (package or host); never other query parameters or the fragment | `deep-link-opened.json` |
| `Network Request` | track | `method`, `url` and `path` without query or fragment and with ids as `:id`, `host`, `status_code` (absent when the call failed), `error` (`canceled`, `timeout`, `unknown_host`, `connect`, `tls` or a class name), `duration_ms`, `request_bytes` and `response_bytes` when known | `network-request.json` |
| `SDK Diagnostics` | track | `period_start` (date), `dropped` (`total` and a count per reason), `issues` (a count per code of `docs/issue-codes.md`); never through `beforeSend` | `sdk-diagnostics.json` |

A developer may send events with the same names; `source` tells them apart. The backend shows
`source` as a breakdown rather than merging both into one row.
