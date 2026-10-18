# 2. The journey of an event

[Index](README.md) · Previous: [1. Architecture](01-architecture.md) · Next: [3. Queue, storage and upload](03-queue-and-upload.md)

## 2.1 From `track()` to the server

One event, from the app's call to its deletion after the server accepted it. `screen` takes the
same path; `identify` goes through `Identity` first (see 4.2).

### 2.1a The big picture

```mermaid
sequenceDiagram
    autonumber
    participant App as Your app
    participant SDK as SDK on your thread
    participant W as Writer
    participant D as Disk
    participant U as Uploader
    participant B as Backend

    App->>SDK: track("Purchase", properties)
    SDK->>SDK: check and copy the properties
    SDK->>W: leave the event in the queue
    SDK-->>App: returns at once
    W->>W: session, context, hooks
    W->>D: write the event as one line
    Note over W,D: later, after 30 events, 60 seconds, going to the background or flush()
    W-->>U: wake up (no data)
    U->>D: read the oldest batch
    U->>B: POST /v1/batch
    B-->>U: 200
    U->>D: delete the batch
```

- Step 7 is a bell, not a hand-over: the uploader takes the batches from disk itself, all of them,
  oldest first, including any left from earlier runs.
- Between steps 3 and 6 the event is only in the in-memory queue. If the process is killed in that
  window, usually a few milliseconds, the event is lost. From step 6 on it survives a crash or a
  kill and stays on disk until the server accepts it.

### 2.1b The same, in detail

```mermaid
sequenceDiagram
    autonumber
    participant App as App thread
    participant DA as DefaultAnalytics
    participant N as PropertyNormalizer
    participant CH as Write channel
    participant W as Writer
    participant P as EventPipeline
    participant S as FileEventStore
    participant U as Uploader
    participant B as Backend
    participant O as eventObserver

    App->>DA: track(name, properties)
    DA->>N: checkName(name)
    alt blank or over 255 characters
        N-->>DA: problem
        DA-->>App: WARN INVALID_EVENT_NAME, dropped (throws in debug)
    end
    DA->>DA: collecting? (not opted out, not switched off)
    DA->>N: normalize(properties): deep copy, convert, drop bad parts
    DA->>DA: message_id (UUIDv7), timestamp, identity snapshot, call-time context
    DA->>CH: trySend(CapturedEvent)
    alt channel full (1000)
        CH-->>DA: dropped as backpressure, counted
        DA-->>O: Dropped (on the calling thread)
    end
    DA-->>App: returns at once
    CH->>W: next message, in call order
    W->>W: collecting and type enabled?
    W->>P: process(event)
    P->>P: session: end an expired one, start one in the foreground
    P->>P: context: static values, call-time values, session, library
    P->>P: super properties merged, the event's own key wins
    P->>P: beforeSend hooks (not for SDK Diagnostics)
    P->>P: validate again, serialize, 32 KB check
    P-->>W: Accepted(line) or Dropped(reason)
    W->>S: append(line): one write of line and newline
    W-->>O: Queued(event)
    alt flush.at reached, immediate event, interval, background, network back, flush()
        W->>S: close the open segment (fsync, rename)
        W-->>U: upload signal, a bell with no data
    end
    U->>U: connected? collecting? consent not pending? no wait running?
    U->>S: writeBatch(oldest segment): sent_at and the stored lines
    U->>B: POST /v1/batch, gzip
    B-->>U: 200
    U->>S: delete the segment
    U-->>O: Sent(message ids)
```

`eventObserver` is optional and is called on the SDK's threads (the writer or the uploader, or the
calling thread for a backpressure drop), never posted to the main thread: an observer that updates
the UI must switch threads itself.

## 2.2 Inside the pipeline

`EventPipeline.process` on the writer: the steps every event goes through, in order, and where it
can be dropped.

### 2.2a The steps

```mermaid
flowchart LR
    from(["From the queue"]) --> gate{"Collecting, its type on,<br/>identify has something new?"}
    gate -->|"no"| gone(["Not created<br/>not counted"])
    gate -->|"yes"| session["1. Session<br/>end an expired one,<br/>start one in the foreground"]
    session --> context["2. Context<br/>screen, network, locale, timezone: taken on your thread at the call<br/>app, device, os, session, library: added here"]
    context --> supers["3. Super properties<br/>the event's own keys win"]
    supers --> hooks["4. beforeSend hooks<br/>in order, see 2.2b"]
    hooks --> check["5. Check the values again"]
    check --> size{"6. 32 KB or less?"}
    size -->|"yes"| disk[("Written to disk")]
    hooks -->|"dropped, threw or broke a rule"| d1(["Dropped: before_send"])
    check -->|"a bad value"| d2(["Only that key dropped,<br/>WARN"])
    size -->|"no"| d3(["Dropped: oversize"])
```

`SDK Diagnostics` skips step 4; `Session Started` and `Session Ended` are created in step 1 and go
through the same steps.

### 2.2b The rules for a hook

Each hook gets the event and returns it, a changed copy or `null`. The SDK checks the result before
the next hook runs (Public API 4).

```mermaid
flowchart TD
    h(["A hook returns"]) --> threw{"Threw an exception?"}
    threw -->|"yes"| x1(["Dropped: before_send<br/>ERROR HOOK_THREW, throws in debug"])
    threw -->|"no"| nul{"Returned null?"}
    nul -->|"yes, an app event"| x2(["Dropped: before_send<br/>the app's choice"])
    nul -->|"yes, an automatic event"| v(["Dropped: before_send<br/>ERROR HOOK_VIOLATION, throws in debug"])
    nul -->|"no"| same{"Same type, source, ids,<br/>time and context as its input?"}
    same -->|"no"| v
    same -->|"yes"| named{"Name still valid?<br/>1 to 255 characters"}
    named -->|"no"| v
    named -->|"yes"| auto{"An automatic event?"}
    auto -->|"no"| ok(["Accepted: the next hook gets it"])
    auto -->|"yes"| names{"Same name and<br/>same top-level keys?"}
    names -->|"yes"| ok
    names -->|"no"| v
```

| Event source | What a hook may do |
|---|---|
| `APP` (the app's calls) | Any valid name, any keys and values, or `null` to drop it |
| `AUTO` (the SDK's own) | Change values only (also to `null` or another type); same name, same top-level keys, never `null` |
| `SDK Diagnostics` | Never reaches a hook |

## 2.3 Validating a property value

`PropertyNormalizer`, on the calling thread for the app's maps, and again after the hooks. A bad
key or value drops only itself; the event is kept (Public API 3, Serialization 2 and 3).

| Kept as is | Converted | Dropped, with a WARN (throws in debug) |
|---|---|---|
| `null`, `Boolean`, `Int`, `Long` | `Float`, `Double` to a number (`Float` through its decimal text) | NaN, Infinity |
| `String` up to 1024 characters | A longer `String` cut at 1024 on a code point (WARN, never throws) | `java.time` types |
| `List` and `Map` of allowed values, 3 levels deep | An unpaired surrogate to U+FFFD | `JSONObject`, `JSONArray` |
| | `Byte`, `Short` to `Int` | A map key that is blank or not a `String` |
| | `BigDecimal` to `Double`, `BigInteger` to `Long`, when they fit | `BigDecimal`, `BigInteger` that do not fit |
| | `Char` and `enum` to text, `Date` to ISO 8601 in UTC | Nesting deeper than 3 |
| | Arrays, `Set` and other collections to a list | Anything else |

The tree below is the same, decision by decision:

```mermaid
flowchart TD
    v(["A value at nesting level n"]) --> t{"Type"}
    t -->|"null, Boolean, Int, Long"| keep(["Kept as is"])
    t -->|"String"| str{"Over 1024 characters?"}
    str -->|"yes"| cut(["Cut at 1024 on a code point<br/>WARN PROPERTY_TRUNCATED, never throws"])
    str -->|"no"| sur{"Unpaired surrogate?"}
    sur -->|"yes"| fffd(["Replaced by U+FFFD"])
    sur -->|"no"| keep
    t -->|"Double, Float"| fin{"NaN or Infinity?"}
    fin -->|"yes"| drop
    fin -->|"no"| num(["Number; Float through its decimal text"])
    t -->|"Byte, Short"| int(["Int"])
    t -->|"BigDecimal, BigInteger"| big{"Fits a Double or a Long?"}
    big -->|"yes"| num
    big -->|"no"| drop
    t -->|"Char, enum, Date"| text(["Text: the char, the enum name,<br/>yyyy-MM-ddTHH:mm:ss.SSSZ in UTC"])
    t -->|"Map, List, Set, Array"| depth{"n over 3?"}
    depth -->|"yes"| drop
    depth -->|"no"| each["Each entry or element at level n + 1"]
    each --> key{"Map key a non-blank String?"}
    key -->|"no"| drop
    key -->|"yes"| v
    t -->|"java.time, JSONObject, JSONArray, anything else"| drop(["Dropped: the key or the element<br/>WARN PROPERTY_DROPPED with key and type, never the value<br/>throws in debug"])
```

Nesting: a property may hold up to three maps or lists inside each other. `{"a": {"b": {"c": {"d": 1}}}}`
(three maps under the property `a`) is kept; one more, `{"a": {"b": {"c": {"d": {"e": 1}}}}}`,
drops only `d` with a WARN and keeps the rest.

After the values: more than 256 properties, super properties included, drops the extra ones,
super properties first (`TOO_MANY_PROPERTIES`).

## 2.4 The states of an event

Where an event can be, and every way it can end. Each drop is counted under its reason and reported
by `SDK Diagnostics` (Queue 5, Logging 5).

```mermaid
stateDiagram-v2
    state "Checked on your thread" as Validated
    state "In the queue" as InChannel
    state "Through the pipeline" as Processed
    state "In the open file" as OpenSegment
    state "In a closed file" as ClosedSegment
    state "Being sent" as InFlight
    state "Sent" as Sent
    state "Dropped and counted" as Dropped

    [*] --> Validated: track, screen, identify
    Validated --> [*]: invalid name, opted out, switched off
    Validated --> InChannel: left in the queue
    Validated --> Dropped: backpressure (queue full)
    InChannel --> [*]: opted out, type switched off remotely, no identify needed
    InChannel --> Processed: the writer takes it
    Processed --> Dropped: before_send, oversize, storage_error
    Processed --> OpenSegment: written as one line
    OpenSegment --> ClosedSegment: 100 events, 512 KB or a flush
    OpenSegment --> Dropped: queue_full, expired, api_key_changed
    ClosedSegment --> InFlight: the uploader reads the batch
    ClosedSegment --> Dropped: queue_full, expired, corrupt, api_key_changed
    InFlight --> Sent: 2xx
    InFlight --> ClosedSegment: kept, retried later
    InFlight --> Dropped: rejected (400, 422), retry_exhausted
    Sent --> [*]
    Dropped --> [*]
```

`clearLocalData()` and an opt-out from `PENDING` delete waiting events on purpose: they are not
counted as drops (Privacy 3 and 4).
