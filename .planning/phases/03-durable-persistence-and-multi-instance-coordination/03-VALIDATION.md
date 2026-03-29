---
phase: 03
slug: durable-persistence-and-multi-instance-coordination
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-29
---

# Phase 03 - Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers PostgreSQL + Testcontainers GenericContainer Redis |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*FlywayMigrationTest" --tests "*CollaborationPersistenceRepositoryTest" --tests "*CollaborationPersistenceServiceTest" --tests "*SessionCoordinationServiceTest" --tests "*CollaborationRelayServiceTest" --tests "*CollaborationWebSocketHandlerTest" --tests "*DistributedCollaborationWebSocketHandlerTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90-180 seconds |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*FlywayMigrationTest" --tests "*CollaborationPersistenceRepositoryTest" --tests "*CollaborationPersistenceServiceTest" --tests "*SessionCoordinationServiceTest" --tests "*CollaborationRelayServiceTest" --tests "*CollaborationWebSocketHandlerTest" --tests "*DistributedCollaborationWebSocketHandlerTest"`
- **After every plan wave:** Run `./gradlew test`
- **Before `$gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 180 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 03-01-01 | 01 | 1 | DATA-01 | migration + repository integration | `./gradlew test --tests "*FlywayMigrationTest" --tests "*CollaborationPersistenceRepositoryTest"` | ❌ 03 | ⬜ pending |
| 03-02-01 | 02 | 2 | DATA-01, DATA-02 | persistence + replay recovery | `./gradlew test --tests "*CollaborationPersistenceServiceTest"` | ❌ 03 | ⬜ pending |
| 03-03-01 | 03 | 3 | DATA-03, DATA-04 | Redis coordination + relay | `./gradlew test --tests "*SessionCoordinationServiceTest" --tests "*CollaborationRelayServiceTest"` | ❌ 03 | ⬜ pending |
| 03-04-01 | 04 | 4 | DATA-01, DATA-02, DATA-03, DATA-04 | distributed websocket integration | `./gradlew test --tests "*CollaborationWebSocketHandlerTest" --tests "*DistributedCollaborationWebSocketHandlerTest"` | ❌ 03 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠ flaky*

---

## Wave 0 Requirements

- [ ] `build.gradle.kts` - add `spring-boot-starter-data-redis` and keep Testcontainers support compatible with Redis-backed tests
- [ ] `src/main/resources/db/migration/V2__phase3_collaboration_persistence.sql` - durable schema for operation logs, snapshots, and execution history
- [ ] `src/test/java/com/collabeditor/redis/` - Redis coordination and relay regression coverage
- [ ] `src/test/java/com/collabeditor/ot/CollaborationPersistenceRepositoryTest.java` - ordered replay and latest-snapshot repository coverage

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Persisted collaboration state survives a real service restart | DATA-01, DATA-02 | The clearest proof is a live reconnect after stopping and restarting the app | Create edits in a joined session, stop the backend, restart it against the same PostgreSQL and Redis stack, reconnect to `/ws/sessions/{sessionId}`, and confirm `document_sync` returns the last canonical document and revision instead of an empty room. |
| Canonical collaboration events relay across two app instances without divergence | DATA-03, DATA-04 | The full operator view of two JVMs and two live sockets is still easiest to judge manually | Run two backend instances against shared PostgreSQL and Redis, connect one client to each instance, submit an operation and a presence update from one side, and confirm both clients see the same canonical revision, document, and participant events. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 180s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-03-29
