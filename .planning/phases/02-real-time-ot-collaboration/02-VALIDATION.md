---
phase: 02
slug: real-time-ot-collaboration
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-27
---

# Phase 02 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + raw WebSocket integration tests |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*OperationalTransformServiceTest" --tests "*CollaborationSessionRuntimeTest" --tests "*CollaborationWebSocketHandlerTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30-90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*OperationalTransformServiceTest" --tests "*CollaborationSessionRuntimeTest" --tests "*CollaborationWebSocketHandlerTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `$gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 02-01-01 | 01 | 1 | COLL-02, COLL-03, COLL-04, QUAL-02 | OT unit + convergence | `./gradlew test --tests "*OperationalTransformServiceTest" --tests "*CollaborationSessionRuntimeTest"` | ❌ 02 | ⬜ pending |
| 02-02-01 | 02 | 2 | COLL-01, COLL-02, COLL-03 | handshake + protocol contract | `./gradlew test --tests "*CollaborationHandshakeInterceptorTest" --tests "*CollaborationWebSocketHandlerTest"` | ❌ 02 | ⬜ pending |
| 02-03-01 | 03 | 3 | COLL-05, COLL-06 | presence + websocket integration | `./gradlew test --tests "*PresenceServiceTest" --tests "*CollaborationWebSocketHandlerTest"` | ❌ 02 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `build.gradle.kts` — add `spring-boot-starter-websocket`
- [ ] `src/test/java/com/collabeditor/ot/` — transform edge-case and three-user convergence coverage
- [ ] `src/test/java/com/collabeditor/websocket/` — handshake, sync, ack/error, and presence coverage

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Joined participant can open a bearer-authenticated room socket and receive the full document bootstrap | COLL-01, COLL-02 | Easiest to validate with a live socket client against the running app | Start the app, create and join a session via REST, connect to `/ws/sessions/{sessionId}` with `Authorization: Bearer <token>`, and confirm a `document_sync` payload arrives immediately. |
| Two participants see explicit join/leave and selection updates in real time | COLL-05, COLL-06 | Real interaction timing and visibility are clearest with two live clients | Connect two users to the same room, move carets or selection ranges, disconnect one socket, and confirm `participant_joined`, `presence_updated`, and `participant_left` events appear on the peer connection. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 90s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-03-27
