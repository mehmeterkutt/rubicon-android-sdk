# How the SDK works, in diagrams

Every flow the SDK implements, drawn from the code and the design decisions. The diagrams are
Mermaid; GitHub and most IDEs render them. Pointers such as (Queue 4) name a design decision;
the decision pages are kept outside this repository. When the code changes a flow, the diagram
changes in the same pull request.

| File | Diagram | Shows | Decisions |
|---|---|---|---|
| [1. Architecture](01-architecture.md) | 1.1 Modules and the platform boundary | The big picture by role, then the same with class names | Public API 1, 2, 6; Configuration 5; HTTP 1; D13 |
| | 1.2 Runtime components | The big picture, what goes into the writer, what happens around the uploader | Queue 1 |
| | 1.3 Threads | Which work runs on which thread, in plain words | Queue 1; Public API 9 |
| [2. The journey of an event](02-event-journey.md) | 2.1 From `track()` to the server | The big picture in six actors, then every step | Public API 3, 9; Queue 1 to 3 |
| | 2.2 Inside the pipeline | The steps with their drop points, then the rules for a hook | Public API 4; Event Model 3; Serialization 3 |
| | 2.3 Validating a property value | Kept, converted or dropped: a table, then the decision tree | Serialization 2, 3 |
| | 2.4 The states of an event | Everywhere an event can be and every way it ends | Queue 5; Logging 5 |
| [3. Queue, storage and upload](03-queue-and-upload.md) | 3.1 Flushing | When a flush happens, then the four checks before sending | Queue 2, 3, 6, 7 |
| | 3.2 Files on the device | Directory layout, segments and limits | Disk 1 to 7; Identity 1 |
| | 3.3 From an HTTP result to a decision | Every status and failure, grouped A to E | Retry 1, 4; HTTP 2 to 6 |
| | 3.4 Retry | Waits, pauses, poison batches | Retry 1 to 5 |
| [4. Identity, sessions and privacy](04-identity-session-privacy.md) | 4.1 Sessions | An example timeline, the states, the check at process start | Session 1 to 6 |
| | 4.2 Identity | `identify`, `reset`, super properties, a user switch | Identity 1 to 5; Context 5 |
| | 4.3 Consent | `GRANTED`, `PENDING`, `DENIED` and what each allows | Privacy 1, 2, 4 |
| | 4.4 `clearLocalData()` | What is forgotten and what is kept | Privacy 3 |
| [5. Android](05-android.md) | 5.1 Startup | The big picture, then the factory, the writer's loading order and the uploader's start | Public API 1, 2; Privacy 2; Disk 2d |
| | 5.2 Installed, updated or nothing | The first-foreground check, with examples | Lifecycle 4 |
| | 5.3 Screen views and deep links | Which activity callbacks produce which event, with examples | Lifecycle 3, 6; Privacy 5b |
| | 5.4 Network request capture | The interceptor's decisions and what it records | Lifecycle 1, 6; Privacy 5c; D13 |
| | 5.5 Context collection | Where each context value is read | Context 1 to 3, 6 |
| | 5.6 Remote configuration | Fetch, ETag, what applies now and what at the next launch | Configuration 1; Privacy 4 |
| | 5.7 SDK Diagnostics | Counters, when the event goes out, every drop reason | Logging 5 |

Related references: [wire format](../wire-format.md), [issue codes](../issue-codes.md),
[remote configuration contract](../remote-config.md).
