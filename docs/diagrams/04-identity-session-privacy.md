# 4. Identity, sessions and privacy

[Index](README.md) · Previous: [3. Queue, storage and upload](03-queue-and-upload.md) · Next: [5. Android](05-android.md)

## 4.1 Sessions

A session is one visit: it starts with the first event while the app is in the foreground, and ends
when the app has been in the background longer than `session.backgroundTimeoutMs` (default 5
minutes), or after 12 hours at most. `SessionManager` runs on the writer and has no timers: an end
is noticed when the app returns to the foreground, an event arrives or the process starts
(Session 1 to 6).

### 4.1a An example

| Time | What happens | Session |
|---|---|---|
| 10:00 | App opened, `Application Opened` | S1 starts: `Session Started`, then the event |
| 10:20 | App goes to the background | S1 counts down; its last activity, 10:20, is saved |
| 10:23 | Back within 5 minutes | S1 goes on |
| 10:30 | Background again | S1 counts down from 10:30 |
| 10:50 | Back after 20 minutes | `Session Ended` for S1, dated 10:30, `duration_ms` 30 minutes; S2 starts with `Application Opened` |
| 10:52 | The process is killed | S2 is saved in `person.json` |
| 10:54 | App started again | Within 5 minutes of S2's last activity: S2 goes on |

### 4.1b The states

```mermaid
stateDiagram-v2
    state "No session" as NoSession
    state "Active, app in the foreground" as Active
    state "Counting down, app in the background" as Countdown

    [*] --> NoSession
    NoSession --> Active: first event in the foreground (Session Started, then the event)
    NoSession --> NoSession: an event in the background carries no session
    Active --> Active: an event (last activity saved at most every 30 s)
    Active --> Countdown: app to the background (last activity saved now)
    Countdown --> Active: back before the timeout
    Countdown --> Countdown: an event in the background uses the session, never extends it
    Countdown --> NoSession: back, or an event, after the timeout (Session Ended)
    Active --> NoSession: 12 hours passed (Session Ended, a new one starts with the event)
```

Some calls end a session in any state:

| Call | Session |
|---|---|
| `reset()`, or `identify` with a different user | `Session Ended`, sent as the previous user; the next foreground event starts a new one |
| `optOut()`, `clearLocalData()` | Ends without an event |

### 4.1c At process start

The saved session in `person.json` is checked with the wall clock, the only clock that survives a
restart:

```mermaid
flowchart TD
    start(["Writer startup: sessions.start()"]) --> saved{"A saved session?"}
    saved -->|"no"| none(["No session until the first foreground event"])
    saved -->|"yes"| back{"Clock earlier than the last activity?"}
    back -->|"yes"| ended(["Session Ended, backdated to the last activity,<br/>with duration_ms; the saved session is cleared"])
    back -->|"no"| timeout{"Last activity older than the timeout?"}
    timeout -->|"yes"| ended
    timeout -->|"no"| max{"Started 12 hours ago or more?"}
    max -->|"yes"| ended
    max -->|"no"| cont(["Continued, as if in the background since its last activity;<br/>the next ON_START decides again"])
```

| Event | Timestamp | Properties | Session |
|---|---|---|---|
| `Session Started` | The session's start, right before the event that opened it | none | The new one |
| `Session Ended` | The last activity, backdated | `duration_ms` | The ended one; after a reset or user switch, with the previous `user_id` |

With `Autocapture.SESSIONS` off, session ids still go on every event; only these two events are not
created.

## 4.2 Identity

The user id and super properties live in `person.json`, the device id in `device.json`. Every call
changes memory on the calling thread at once, so a `track` right after `identify` already carries
the new id (Identity 1 to 5, Public API 9).

```mermaid
stateDiagram-v2
    state "Anonymous, device_id only" as Anonymous
    state "Identified as a user" as Identified

    [*] --> Anonymous: first launch (a new device_id)
    Anonymous --> Identified: identify(A), identify sent
    Identified --> Identified: identify(A) with traits, identify sent<br/>identify(A) without traits, nothing sent<br/>identify(B), Session Ended as A, super properties cleared, identify sent as B
    Identified --> Anonymous: reset(), Session Ended as A, super properties cleared, no event<br/>clearLocalData(), also a new device_id, no event
    Anonymous --> Anonymous: reset(), no change<br/>clearLocalData(), a new device_id, no event
```

The device id never changes on `reset()`; only `clearLocalData()` gives a new one. Traits go with
the identify event only and are never stored. While opted out, `identify` still updates the stored
identity but sends nothing (Privacy 1).

The sequence below is a user switch. `identify` decides on your thread whether an event is due and
gives the writer that decision as a ticket with the event, so the writer never has to guess what the
identity was at the time of the call.

```mermaid
sequenceDiagram
    autonumber
    participant App as App thread
    participant I as Identity
    participant CH as Write channel
    participant W as Writer
    participant SM as SessionManager

    App->>I: identify("B", traits)
    I->>I: user id 1 to 255 characters? traits validated
    alt stores not loaded yet
        I->>I: kept in call order, applied when the writer loads them
    end
    I->>I: known id is A, a different user
    I->>CH: PersonCleared(snapshot of A)
    I->>I: person state cleared, user id = B, persist requested
    I-->>App: IdentifyTicket(SendEvent with B)
    App->>CH: Enqueue(identify event with its ticket)
    App->>CH: Enqueue(next track, already as B)
    CH->>W: PersonCleared(A)
    W->>SM: onPersonCleared(A)
    SM-->>W: Session Ended with user_id A
    CH->>W: identify event
    W->>W: ticket says send, with traits
    W->>SM: onEvent (Session Started in the foreground)
    W->>W: identify as B, then the track as B
```

| Call | Stored user id | Super properties | Event | Session |
|---|---|---|---|---|
| `identify(A)` while anonymous | A | Kept | `identify` | Same |
| `identify(A)` again | A | Kept | Only with traits | Same |
| `identify(B)` while A | B | Cleared | `identify` | Ends as A; a new one starts |
| `reset()` | none | Cleared | none | Ends as the previous user |
| `register(key, value)` | Same | Added, validated at once | none | Same |
| `unregister(key)` | Same | Removed | none | Same |

## 4.3 Consent

`ConsentController` holds three states. `privacy.defaultConsent` gives the first one; with
`privacy.persistConsent` (default on) the user's stored choice wins at every start (Privacy 1, 2, 4).

```mermaid
stateDiagram-v2
    [*] --> GRANTED: default GRANTED, or stored GRANTED
    [*] --> PENDING: default PENDING, nothing stored
    [*] --> DENIED: stored DENIED
    PENDING --> GRANTED: optIn() (flush now)
    PENDING --> DENIED: optOut() (waiting events deleted, session ends silently)
    GRANTED --> DENIED: optOut() (session ends silently, queued events still sent)
    DENIED --> GRANTED: optIn() (collection resumes, a new session with the next event)
```

| State | New events | Written to disk | Uploaded | `isOptedOut()` | Remote config fetch |
|---|---|---|---|---|---|
| `GRANTED` | Yes | Yes | Yes | `false` | Yes |
| `PENDING` | Yes | Yes | No, they wait | `true` | Yes, it carries no personal data |
| `DENIED` | No | No | Yes, what was queued with consent | `true` | Yes |

The change applies in memory at once on the calling thread; the writer then gets `ConsentChanged`
in order with the events, so an event tracked right before `optOut()` is still written. No event
records the change; one INFO line does.

## 4.4 `clearLocalData()`: forget this user

For a deletion request: everything the SDK holds about the person goes, and the device becomes a new
installation for analytics. Consent, the last version and build and the remote configuration stay,
so the user is not asked again and no false update is reported (Privacy 3).

```mermaid
sequenceDiagram
    autonumber
    participant App as App thread
    participant DA as DefaultAnalytics
    participant I as Identity
    participant CH as Write channel
    participant W as Writer
    participant FS as FileEventStore
    participant RP as RetryPolicy

    App->>DA: clearLocalData()
    DA->>I: clearLocalData(new UUIDv7)
    I->>I: person = anonymous (user id and super properties gone)
    I->>I: device = new device_id, retry state and diagnostics counters cleared
    I->>CH: Persist
    DA->>CH: ClearLocalData
    DA-->>App: returns at once
    Note over App,DA: a track now already carries the new device_id
    CH->>W: ClearLocalData
    W->>W: session ends, no event
    W->>W: interval cancelled, count since flush = 0
    W->>FS: clear(): every segment deleted, not counted as drops
    W->>W: device.json and person.json written
    RP->>RP: waitMs() sees the stored wait gone and ends a running wait
```

| Cleared | Kept |
|---|---|
| `user_id`, super properties, the open session | Consent choice |
| `device_id` (a new one; sampling draws again) | Last app version and build |
| Every queued event | Remote configuration and its ETag |
| Retry wait and attempts | The configuration in code |
| `SDK Diagnostics` counters | |
