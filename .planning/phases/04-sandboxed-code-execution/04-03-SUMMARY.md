---
phase: 04-sandboxed-code-execution
plan: 03
subsystem: api
tags: [execution, redis, websocket, queue, rest]

# Dependency graph
requires:
  - phase: 04-01
    provides: Canonical source capture and durable execution lifecycle helpers
  - phase: 04-02
    provides: Real Docker sandbox runner and language contracts
  - phase: 03-04
    provides: Existing room websocket and relay infrastructure
provides:
  - Bounded execution worker queue with distributed five-second cooldown enforcement
  - Authenticated REST enqueue API returning accepted execution metadata
  - Redis-backed execution lifecycle relay and websocket execution_updated broadcasts
  - Controller, service, and relay tests covering accepted, rejected, and cross-node room-visible updates
affects: [04-04, 05-integration-hardening-and-developer-docs]

# Tech tracking
tech-stack:
  added: []
  patterns: [REST enqueue plus websocket observe, relay-owned execution fan-out, durable queued-running-terminal transitions]

key-files:
  created:
    - src/main/java/com/collabeditor/execution/api/ExecutionController.java
    - src/main/java/com/collabeditor/execution/service/ExecutionCoordinatorService.java
    - src/main/java/com/collabeditor/execution/service/ExecutionEventRelayService.java
    - src/main/java/com/collabeditor/execution/service/ExecutionBroadcastGateway.java
    - src/test/java/com/collabeditor/execution/ExecutionControllerTest.java
    - src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java
  modified:
    - src/main/java/com/collabeditor/common/api/ApiExceptionHandler.java
    - src/main/java/com/collabeditor/redis/config/RedisConfig.java
    - src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java
    - src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java
    - src/test/java/com/collabeditor/execution/ExecutionServiceTest.java

key-decisions:
  - "Execution requests return 202 Accepted immediately and rely on websocket lifecycle updates for async progress and terminal output."
  - "Execution lifecycle fan-out reuses the existing room websocket via a dedicated Redis execution relay path instead of a second realtime channel."
  - "Redis relay callbacks run through a synchronous task executor so room participants observe execution lifecycle events in publish order."

patterns-established:
  - "ExecutionCoordinatorService owns the full enqueue, cooldown, queue, runner, persistence, and relay flow."
  - "ExecutionEventRelayService mirrors CollaborationRelayService but carries execution_updated payloads."
  - "Controller tests exercise the production JWT filter chain rather than bypassing auth."

requirements-completed: [EXEC-01, EXEC-04]

# Metrics
duration: 28min
completed: 2026-03-29
---

# Phase 4 Plan 3: Queueing and Relay Summary

**The backend now accepts execution requests asynchronously, enforces a distributed cooldown, and relays execution_updated lifecycle events over the existing room socket**

## Performance

- **Duration:** 28 min
- **Started:** 2026-03-29T20:32:00Z
- **Completed:** 2026-03-29T21:24:16Z
- **Tasks:** 3
- **Files modified:** 11

## Accomplishments
- Added the fixed-size execution worker pool, distributed cooldown key, and durable queued/rejected/running/terminal orchestration service.
- Exposed `POST /api/sessions/{sessionId}/executions` as the authenticated enqueue contract with explicit 429 and 503 outcomes.
- Delivered room-visible `execution_updated` websocket fan-out on top of a Redis-backed execution relay and preserved message ordering for lifecycle transitions.

## Task Commits

Plan work was committed as one coupled Phase 4 implementation because the execution foundation, sandbox runner, relay contract, and verification suite share a single execution API surface:

1. **Task 1-3: Execution admission, sandbox orchestration, relay delivery, and verification** - `c0a78ca` (feat)

## Files Created/Modified
- `src/main/java/com/collabeditor/execution/config/ExecutionTaskConfig.java` - Declares the bounded worker queue that surfaces `TaskRejectedException`.
- `src/main/java/com/collabeditor/execution/service/ExecutionRateLimitService.java` - Implements the exact Redis cooldown key `collab:execution:user:{userId}:cooldown`.
- `src/main/java/com/collabeditor/execution/service/ExecutionCoordinatorService.java` - Orchestrates capture, cooldown, queued/rejected persistence, runner execution, and relay publishing.
- `src/main/java/com/collabeditor/execution/api/ExecutionController.java` - Exposes the authenticated enqueue endpoint.
- `src/main/java/com/collabeditor/execution/service/ExecutionEventRelayService.java` - Publishes and subscribes to room-scoped execution lifecycle updates.
- `src/main/java/com/collabeditor/execution/service/ExecutionBroadcastGateway.java` - Broadcasts `execution_updated` payloads to local room sockets.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` - Ensures room sockets subscribe to execution lifecycle updates on connect.
- `src/test/java/com/collabeditor/execution/ExecutionControllerTest.java` - Covers accepted enqueue, cooldown rejection, queue-full rejection, and JWT-protected 401 behavior.
- `src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java` - Proves room-scoped relay delivery and two-node lifecycle visibility.

## Decisions Made
- Accepted execution requests publish a queued lifecycle update immediately, then transition to running/terminal states asynchronously.
- Queue-full rejection releases the acquired cooldown key so a failed admission does not burn a user's five-second window.
- Execution relay ordering is treated as part of the room contract, not a test-only concern.

## Deviations from Plan

None - the queue, API, relay, and websocket delivery path matched the intended design after the runner contract stabilized.

## Issues Encountered

None.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Final hardening can now verify the complete Phase 4 contract end to end against live Docker runs and distributed relay delivery.
- Phase 5 can build on the new execution REST/WebSocket contract and relay test harness.

---
*Phase: 04-sandboxed-code-execution*
*Completed: 2026-03-29*
