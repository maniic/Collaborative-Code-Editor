---
phase: 02-real-time-ot-collaboration
plan: 02
subsystem: websocket
tags: [websocket, spring-websocket, ot, json-protocol, handshake-auth]

# Dependency graph
requires:
  - phase: 02-01
    provides: OT core engine and CollaborationSessionRuntime for operation transform and apply
  - phase: 01-secure-access-and-session-lifecycle
    provides: JWT auth, session membership, and participant repository
provides:
  - Raw WebSocket endpoint at /ws/sessions/{sessionId} with handshake JWT auth
  - Typed collaboration protocol with document_sync, operation_ack, operation_applied, operation_error, resync_required
  - CollaborationSessionRegistry for per-session runtime and socket tracking
  - CollaborationHandshakeInterceptor for bearer-token and active-membership enforcement
affects: [02-03-presence-broadcasting, 03-persistence, redis-relay]

# Tech tracking
tech-stack:
  added: [spring-boot-starter-websocket]
  patterns: [raw-websocket-over-stomp, typed-envelope-protocol, handshake-interceptor-auth]

key-files:
  created:
    - src/main/java/com/collabeditor/websocket/config/WebSocketConfig.java
    - src/main/java/com/collabeditor/websocket/security/CollaborationHandshakeInterceptor.java
    - src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java
    - src/main/java/com/collabeditor/websocket/service/CollaborationSessionRegistry.java
    - src/main/java/com/collabeditor/websocket/protocol/CollaborationEnvelope.java
    - src/main/java/com/collabeditor/websocket/protocol/ClientMessageType.java
    - src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java
    - src/main/java/com/collabeditor/websocket/protocol/DocumentSyncPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/SubmitOperationPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/OperationAckPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/OperationAppliedPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/OperationErrorPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/ResyncRequiredPayload.java
    - src/main/java/com/collabeditor/websocket/protocol/ParticipantInfo.java
    - src/test/java/com/collabeditor/websocket/CollaborationHandshakeInterceptorTest.java
    - src/test/java/com/collabeditor/websocket/CollaborationWebSocketHandlerTest.java
  modified:
    - build.gradle.kts
    - src/main/java/com/collabeditor/auth/service/JwtTokenService.java
    - src/main/java/com/collabeditor/auth/security/SecurityConfig.java

key-decisions:
  - "WebSocket endpoint permits in SecurityConfig; handshake interceptor handles auth independently"
  - "JwtTokenService.extractIdentity() shared helper avoids duplicate UUID/email parsing between HTTP filter and handshake interceptor"
  - "CollaborationSessionRegistry uses ConcurrentHashMap + CopyOnWriteArraySet for thread-safe socket tracking"
  - "Future base revision triggers resync_required with full snapshot rather than closing the socket"

patterns-established:
  - "Raw WebSocket with typed JSON envelopes: {type, payload} for all collaboration messages"
  - "Handshake-time auth via HandshakeInterceptor storing identity on session attributes"
  - "Per-message active-membership re-check with socket close on authorization failure"

requirements-completed: [COLL-01, COLL-02, COLL-03]

# Metrics
duration: 9min
completed: 2026-03-27
---

# Phase 2 Plan 02: WebSocket Collaboration Endpoint Summary

**Raw WebSocket endpoint with JWT handshake auth, typed document-sync/operation protocol, and full contract test coverage for the OT collaboration wire format**

## Performance

- **Duration:** 9 min
- **Started:** 2026-03-27T20:58:47Z
- **Completed:** 2026-03-27T21:07:40Z
- **Tasks:** 3
- **Files modified:** 19

## Accomplishments
- Raw WebSocket endpoint at /ws/sessions/{sessionId} with handshake-time JWT validation and active-membership enforcement
- Typed protocol covering document_sync bootstrap, submit_operation, operation_ack, operation_applied, operation_error, and resync_required
- CollaborationSessionRegistry managing per-session runtime instances and socket tracking
- Comprehensive contract tests covering handshake rejection/acceptance, protocol message flows, and edge cases

## Task Commits

Each task was committed atomically:

1. **Task 1: Add raw WebSocket endpoint registration and handshake-time room authentication** - `d3476b3` (feat)
2. **Task 2: Implement the typed sync, submit, acknowledgement, error, and resync protocol** - `7a8f72d` (feat)
3. **Task 3: Add WebSocket contract tests for handshake auth, sync bootstrap, and canonical broadcasts** - `14c3d3a` (test)

## Files Created/Modified
- `build.gradle.kts` - Added spring-boot-starter-websocket dependency
- `src/main/java/com/collabeditor/auth/service/JwtTokenService.java` - Added extractIdentity() and TokenIdentity record
- `src/main/java/com/collabeditor/auth/security/SecurityConfig.java` - Permitted /ws/** for WebSocket handshake
- `src/main/java/com/collabeditor/websocket/config/WebSocketConfig.java` - Raw WebSocket endpoint registration
- `src/main/java/com/collabeditor/websocket/security/CollaborationHandshakeInterceptor.java` - JWT + membership handshake auth
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` - Full protocol message handling
- `src/main/java/com/collabeditor/websocket/service/CollaborationSessionRegistry.java` - Per-session runtime and socket registry
- `src/main/java/com/collabeditor/websocket/protocol/*.java` - 9 typed protocol DTOs
- `src/test/java/com/collabeditor/websocket/CollaborationHandshakeInterceptorTest.java` - 8 handshake contract tests
- `src/test/java/com/collabeditor/websocket/CollaborationWebSocketHandlerTest.java` - 11 handler contract tests

## Decisions Made
- WebSocket endpoint is permitted in SecurityConfig; handshake interceptor handles auth independently from the HTTP filter chain
- Added extractIdentity() shared helper to JwtTokenService to avoid duplicate UUID/email parsing
- CollaborationSessionRegistry uses ConcurrentHashMap + CopyOnWriteArraySet for thread-safe socket tracking
- Future base revision triggers resync_required with full snapshot rather than closing the socket
- handleTextMessage made public (widened from protected) for direct unit test invocation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Made handleTextMessage public for testability**
- **Found during:** Task 2 (Handler implementation)
- **Issue:** TextWebSocketHandler.handleTextMessage is protected; tests in a different package cannot call it directly
- **Fix:** Widened access modifier to public on the override
- **Files modified:** CollaborationWebSocketHandler.java
- **Verification:** Tests compile and pass
- **Committed in:** 7a8f72d (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Minimal - access modifier change required for testability. No scope creep.

## Issues Encountered
None

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- WebSocket collaboration endpoint fully operational with typed protocol
- Ready for Plan 02-03: presence broadcasting (cursor/selection updates, participant join/leave events)
- OT runtime integration verified through handler tests

---
*Phase: 02-real-time-ot-collaboration*
*Completed: 2026-03-27*
