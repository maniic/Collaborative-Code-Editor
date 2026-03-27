---
phase: 02-real-time-ot-collaboration
plan: 03
subsystem: websocket
tags: [presence, selection-range, cursor-throttling, participant-events, ot-transform, convergence]

# Dependency graph
requires:
  - phase: 02-real-time-ot-collaboration (plan 01)
    provides: OT engine with transform rules and convergence
  - phase: 02-real-time-ot-collaboration (plan 02)
    provides: WebSocket collaboration endpoint with typed protocol
provides:
  - Explicit participant_joined and participant_left events with userId + email
  - SelectionRange model and PresenceService with in-memory per-session storage
  - Selection range transformation through canonical insert/delete operations
  - Cursor update throttling at configurable 75ms intervals
  - Phase 2 final regression suite covering OT + WebSocket + presence convergence
affects: [03-durable-persistence, frontend-integration]

# Tech tracking
tech-stack:
  added: []
  patterns: [selection-range-transform, cursor-throttling, presence-ephemeral-store]

key-files:
  created:
    - src/main/java/com/collabeditor/websocket/model/SelectionRange.java
    - src/main/java/com/collabeditor/websocket/protocol/ParticipantJoinedPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/ParticipantLeftPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/PresenceUpdatePayload.java
    - src/main/java/com/collabeditor/websocket/protocol/PresenceUpdatedPayload.java
    - src/main/java/com/collabeditor/websocket/service/PresenceService.java
    - src/test/java/com/collabeditor/websocket/PresenceServiceTest.java
  modified:
    - src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java
    - src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java
    - src/main/java/com/collabeditor/websocket/protocol/ClientMessageType.java
    - src/main/resources/application.yml
    - src/test/java/com/collabeditor/websocket/CollaborationWebSocketHandlerTest.java

key-decisions:
  - "PresenceService uses in-memory ConcurrentHashMap keyed by sessionId+userId for ephemeral presence state"
  - "Selection range transformation applies same insert/delete logic as OT but to cursor positions"
  - "Cursor throttle window is configurable via app.collaboration.cursor-throttle-ms (default 75ms)"
  - "Email resolution uses existing UserRepository.findById rather than duplicating user data"

patterns-established:
  - "Ephemeral presence: ConcurrentHashMap store with join/leave lifecycle matching WebSocket connect/disconnect"
  - "Range transformation: insert shifts affected edges right; delete clamps edges to surviving text"
  - "Throttled broadcasting: store latest range always, broadcast only when throttle window passes"

requirements-completed: [COLL-05, COLL-06, QUAL-02]

# Metrics
duration: 10min
completed: 2026-03-27
---

# Phase 2 Plan 3: Presence and Phase 2 Verification Summary

**Explicit join/leave presence events, selection-range transformation through canonical OT operations, cursor throttling, and the final Phase 2 convergence regression suite**

## Performance

- **Duration:** 10 min
- **Started:** 2026-03-27T21:10:26Z
- **Completed:** 2026-03-27T21:20:13Z
- **Tasks:** 3
- **Files modified:** 12

## Accomplishments
- Explicit participant_joined and participant_left events broadcast on connect/disconnect with userId and email
- SelectionRange model (start/end ints, caret = start==end) with PresenceService for per-session ephemeral presence
- Selection ranges transform through canonical insert/delete operations so cursors stay aligned with the document
- Cursor update throttling at 75ms configurable interval, always storing latest range even when suppressed
- Phase 2 final regression suite: two-socket and three-socket convergence, sender receives both ack and broadcast, selection ranges survive operations

## Task Commits

Each task was committed atomically:

1. **Task 1: Implement explicit participant join/leave events and selection-range presence storage** - `974d1ae` (feat)
2. **Task 2: Transform selection ranges through canonical operations and broadcast throttled presence updates** - `1ff7d4d` (feat)
3. **Task 3: Finish the Phase 2 regression suite and full verification pass** - `d37fda4` (test)

## Files Created/Modified
- `src/main/java/com/collabeditor/websocket/model/SelectionRange.java` - Selection range record with start/end (caret = start==end)
- `src/main/java/com/collabeditor/websocket/protocol/ParticipantJoinedPayload.java` - Typed payload for participant_joined events
- `src/main/java/com/collabeditor/websocket/protocol/ParticipantLeftPayload.java` - Typed payload for participant_left events
- `src/main/java/com/collabeditor/websocket/protocol/PresenceUpdatePayload.java` - Client-sent presence update payload
- `src/main/java/com/collabeditor/websocket/protocol/PresenceUpdatedPayload.java` - Server broadcast for presence changes
- `src/main/java/com/collabeditor/websocket/service/PresenceService.java` - Ephemeral presence store with throttling and range transformation
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` - Added join/leave broadcasts, presence dispatch, email resolution
- `src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java` - Added participant_joined, participant_left, presence_updated
- `src/main/java/com/collabeditor/websocket/protocol/ClientMessageType.java` - Added update_presence
- `src/main/resources/application.yml` - Added app.collaboration.cursor-throttle-ms: 75
- `src/test/java/com/collabeditor/websocket/PresenceServiceTest.java` - 19 tests for presence, throttling, and range transformation
- `src/test/java/com/collabeditor/websocket/CollaborationWebSocketHandlerTest.java` - Extended with 7 new Phase 2 regression scenarios

## Decisions Made
- PresenceService is a stateless ephemeral store (no persistence) keyed by sessionId+userId, matching the in-memory Phase 2 approach
- Email resolved from UserRepository rather than adding it to WebSocket session attributes, keeping auth data in one place
- Selection range transformation uses the same directional logic as OT transforms: inserts shift right, deletes clamp to surviving bounds
- Throttle stores latest range immediately but defers broadcast; document operations bypass throttle since they represent canonical changes

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- FlywayMigrationTest (pre-existing Testcontainers integration test) fails without a running PostgreSQL/Docker environment. This is not a Phase 2 regression; all Phase 2 unit tests pass. The `./gradlew test` full suite gate passes for all non-infrastructure tests.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Phase 2 (Real-Time OT Collaboration) is complete: OT engine, WebSocket protocol, and presence all verified
- Ready for Phase 3: Durable Persistence and Multi-Instance Coordination
- All convergence, transform, and presence tests are green as the regression baseline

---
*Phase: 02-real-time-ot-collaboration*
*Completed: 2026-03-27*
