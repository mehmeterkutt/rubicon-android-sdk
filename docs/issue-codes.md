# Issue codes

Every WARN and ERROR line of the SDK carries one of these codes (Logging decisions 1 and 2). A code
is logged at its own level once per process; later occurrences are logged at DEBUG. Every
occurrence is counted, and `SDK Diagnostics` reports the counts in `issues` (Logging decision 5).

The names are part of the backend contract. A code is never renamed or reused; a new situation gets
a new code, added here in the same pull request as the log line. `IssueCodesTest` fails when this
file and `Code` in `sdk-core` disagree.

Status: approved for the SDK in step 2.2, pending backend review. Only `HOST_BLOCKED`,
`STORAGE_WRITE_FAILED`, `NETWORK_STATE_PERMISSION_MISSING` and `UNREADABLE_SEGMENT` are named in the
decisions; the levels of the first three and of `CORRUPT_LINE` were settled in step 2.2 (plan
section 15).

## Logging

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `LOGGER_THREW` | WARN | The developer's logger threw; the platform logger is used for the rest of the process. | Logging 4b |
| `OBSERVER_THREW` | ERROR | The developer's `eventObserver` threw; it is switched off for the rest of the process. | Logging 4b |
| `WORKER_FAILED` | ERROR | An SDK coroutine failed with an unexpected exception; the SDK keeps running. | Queue 1 |

## Configuration

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `INVALID_API_KEY` | ERROR | The API key is blank; the factory returns a no-op instance. | Configuration 3 |
| `INVALID_HOST` | ERROR | The host is not a valid base URL; the factory returns a no-op instance. | Configuration 3 |
| `CLEARTEXT_HOST` | WARN | The host uses `http://`. | Configuration 3 |
| `SETTING_OUT_OF_BOUNDS` | WARN | A numeric setting was out of bounds and was clamped to the nearest bound. | Configuration 3 |
| `CONTRADICTING_SETTINGS` | WARN | Settings of a group contradict each other; the group's defaults are used. | Configuration 3 |
| `CONFIG_FROZEN` | WARN | A setting was written after the SDK was created; the write is ignored. | Configuration 4 |
| `RESERVED_HEADER` | WARN | `network.headers` tried to override a reserved header; it is ignored. | Configuration 5 |
| `SECOND_INSTANCE` | ERROR | A second instance was created in the process; the first one is returned. | Configuration 3, Public API 1 |
| `PROCESS_NAME_UNKNOWN` | WARN | The process name could not be determined; the main process is assumed. | Disk 2d |
| `REMOTE_CONFIG_FAILED` | WARN | The remote configuration could not be fetched or read; the last valid values, or code, are used. | Configuration 1 |
| `REMOTE_CONFIG_INVALID_VALUE` | WARN | A remote configuration value was invalid and is ignored. | Configuration 3 |

## Events and properties

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `PROPERTY_DROPPED` | WARN | A property or trait was dropped: unsupported type, NaN or Infinity, `java.time`, invalid key or too deep. | Public API 3, Serialization 2, 3 |
| `PROPERTY_TRUNCATED` | WARN | A string value was longer than 1024 characters and was truncated. Logged once per key. | Serialization 3 |
| `TOO_MANY_PROPERTIES` | WARN | An event had more than 256 properties; the extra ones were dropped, super properties first. | Serialization 3 |
| `INVALID_EVENT_NAME` | WARN | An event or screen name was blank or longer than 255 characters; the event was dropped. | Serialization 3 |
| `HOOK_THREW` | ERROR | A `beforeSend` hook threw; the event was dropped. | Public API 4 |

## Identity

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `INVALID_USER_ID` | WARN | `identify` was called with a blank user id or one longer than 255 characters; the call is ignored. | Identity 3 |

## Storage

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `STORE_UNREADABLE` | WARN | `device.json`, `person.json` or `remote-config.json` exists but cannot be read; in-memory values are used and the file is left untouched. | Identity 1, Disk 7 |
| `STORAGE_WRITE_FAILED` | ERROR | An event or a store could not be written, for example on a full disk. | Disk 4, Logging 2 |
| `CORRUPT_LINE` | ERROR | A stored event line was corrupt and was skipped. | Disk 5 |
| `UNREADABLE_SEGMENT` | ERROR | A segment could not be read; it is deleted after three consecutive failures. | Disk 5 |
| `UNKNOWN_FILE` | WARN | A file in the events directory that does not match the naming scheme was deleted. | Disk 5 |

## Upload

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `HOST_BLOCKED` | WARN | The host resolves to a local address, as ad blockers and private DNS do. | HTTP 6 |
| `HTTP_REDIRECT` | ERROR | The host answered with a redirect; `host` must be set to the final URL. | HTTP 5, Retry 1 |
| `TLS_FAILED` | ERROR | The TLS handshake failed, for example on an untrusted certificate. | HTTP 4, Retry 1 |
| `HTTP_UNEXPECTED` | ERROR | The HTTP stack threw an unexpected exception. | HTTP 4, Retry 1 |
| `UPLOAD_UNAUTHORIZED` | ERROR | The server rejected the API key (401, 403). | Retry 1 |
| `UPLOAD_ENDPOINT_MISSING` | ERROR | The endpoint does not exist (404, 410); `host` is probably wrong. | Retry 1 |
| `UPLOAD_TOO_LARGE` | ERROR | A gateway rejected the request size (413). | Retry 1, 4 |
| `UPLOAD_NOT_SUPPORTED` | ERROR | The server does not support the request (501, 505). | Retry 1 |
| `COMPRESSION_FAILED` | WARN | Compressing a request failed; it was sent uncompressed. | HTTP 2 |

## Network

| Code | Level | Meaning | Decision |
|---|---|---|---|
| `NETWORK_STATE_PERMISSION_MISSING` | WARN | The app removed `ACCESS_NETWORK_STATE`; connectivity is assumed. | Retry 6, Logging 2 |
| `NETWORK_CALLBACK_FAILED` | WARN | Registering the network callback failed; connectivity is queried before each flush. | Retry 6 |
