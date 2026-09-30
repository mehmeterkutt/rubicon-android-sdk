<!-- Title: Conventional Commits, for example "feat(queue): segmented event store". -->

**Public API change:** none <!-- or: list the changed symbols; the .api diff is committed -->

## Problem

<!-- What is missing or wrong, and which plan step this is. -->

## Change

<!-- What this pull request does. -->

## Decisions implemented

<!-- For example "Queue 4, Disk 1". Name any implementation decision added to section 15 of the plan. -->

## How it was tested

<!-- The tests added, and anything checked by hand. -->

## Checklist

- [ ] `./gradlew check` is green
- [ ] Every test the plan lists for the step exists
- [ ] New dependencies: none <!-- or: test-only dependencies named here -->
- [ ] Every new WARN or ERROR has a `Code` in `docs/issue-codes.md`; every new way to lose an event has a `DropReason`
- [ ] No value, id, key, header, body or query string reaches a log line
- [ ] `CLAUDE.md` updated if a rule changed; plan status, traceability and decisions log updated
