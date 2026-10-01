# Remote configuration contract

Status: **provisional, pending backend confirmation** (Configuration decision 1). The SDK side is
implemented in step 5.3 (`internal/remote`). Changing this contract after release follows the same
rule as the store formats: evolve compatibly by adding optional keys.

## Request

```
GET {host}/v1/config
Authorization: Bearer {apiKey}
User-Agent: rubicon-android/{version} (Android {os}; API {level})
If-None-Match: "{etag}"            only when a response was saved before
```

One request per launch, in the background, also while consent is pending or the user has opted out:
it carries no personal data. There is no retry within the launch; the next launch tries again.

## Responses

| Status | Meaning for the SDK |
|---|---|
| 200 with a JSON object | Validated and saved with its `ETag`; applies from the next launch. `enabled: false` and `network_requests` in `autocapture_off` also take effect at once. |
| 304 | Unchanged; the saved values stay. |
| 404 | No remote configuration for this key: saved values are deleted and code alone applies from the next launch. |
| Anything else, a network failure, or a body that is not a JSON object | The saved values stay; a WARN (`REMOTE_CONFIG_FAILED`) once per process. |

The body is at most 64 KB. A static file per API key is enough to start; no management panel is
needed (Configuration 1).

## Body

Every key is optional. A key that is missing means "no remote value": the app's code decides.

```json
{
  "enabled": true,
  "autocapture_off": ["network_requests", "deep_links"],
  "flush_at": 50,
  "flush_interval_ms": 120000,
  "sample_rate": 1.0,
  "diagnostics_sample_rate": 0.5
}
```

| Key | Type | Rule |
|---|---|---|
| `enabled` | boolean | `false` is the kill switch: nothing is collected or sent, at once. `true` changes nothing. |
| `autocapture_off` | array of strings | Autocapture types to switch off: `app_lifecycle`, `install_update`, `sessions`, `screen_views`, `deep_links`, `network_requests`. A type the code already disabled stays off; there is no way to switch one on. `network_requests` takes effect at once, the others at the next launch. |
| `flush_at` | integer | 1 to 100, and not below the app's own value: remote can only make flushing less frequent (Queue 3). |
| `flush_interval_ms` | integer | 1000 to 600000, and not below the app's own value. |
| `sample_rate` | number | 0 to 1. The share of devices that collect at all, chosen by `device_id`, so a device is always in or always out. Devices outside the sample collect nothing. 1.0 in the first release. |
| `diagnostics_sample_rate` | number | 0 to 1. The share of devices that send `SDK Diagnostics` (Logging 5). Above 0 is ignored when the app switched diagnostics off. |

Never from remote: API key, host, identity, consent, log level, hooks, immediate events.

## Validation

- A known key with a value of the wrong type, out of its bounds, or one that would widen what the
  app's code allows, is ignored with a WARN (`REMOTE_CONFIG_INVALID_VALUE`, once per key per
  process); the other keys still apply.
- An unknown key, or an unknown autocapture type, is ignored at DEBUG: a newer backend may send keys
  for newer SDK versions.
- Saved values are validated again at every launch against the code of that launch, so a new app
  release with stricter values is never widened by an old response.

## Backend items

- The endpoint, this body, `ETag` and 304, and 404 for "nothing configured".
- `Retry-After` and 429 on uploads remain the backend's other lever (Retry 3).
