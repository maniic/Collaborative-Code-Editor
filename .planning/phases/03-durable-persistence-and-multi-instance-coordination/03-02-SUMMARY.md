---
phase: 03-durable-persistence-and-multi-instance-coordination
plan: 02
subsystem: ot, snapshot, persistence
tags: [postgresql, ot, snapshot, testcontainers, spring-data-jpa]

# Dependency graph
requires:
  - phase: 03-01
    provides: Flyway migrations, session_operations and document_snapshots tables, JPA entities and repositories
provides:
  - CollaborationSessionRuntime.restore factory for lazy rebuild from persisted state
  - CollaborationPersistenceService for durable canonical operation append with snapshot cadence
  - SnapshotRecoveryService for on-demand runtime rebuild from latest snapshot plus replay
  - PostgreSQL-backed integration tests proving snapshot cadence and rebuild correctness
affects: [03-03, 03-04, websocket-handler-refactor]

# Tech tracking
tech-stack:
  added: []
  patterns: [static-factory-restore, snapshot-cadence-modulo-50, lazy-rebuild-from-snapshot-plus-replay]

key-files:
  created:
    - src/main/java/com/collabeditor/ot/service/CollaborationPersistenceService.java
    - src/main/java/com/collabeditor/snapshot/service/SnapshotRecoveryService.java
    - src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java
  modified:
    - src/main/java/com/collabeditor/ot/service/CollaborationSessionRuntime.java
    - src/main/java/com/collabeditor/ot/persistence/SessionOperationRepository.java
    - src/test/java/com/collabeditor/ot/CollaborationSessionRuntimeTest.java
    - src/test/java/com/collabeditor/ot/CollaborationPersistenceServiceTest.java

key-decisions:
  - "restore() is a static factory that initializes runtime from persisted document, revision, and history without losing stale-op transform guarantees"
  - "SnapshotRecoveryService replays post-snapshot operations on the snapshot document before restoring the runtime"
  - "No-snapshot fallback rebuilds from empty document with full ordered operation list replay"

patterns-established:
  - "Static factory restore pattern: CollaborationSessionRuntime.restore(sessionId, document, revision, history, otService)"
  - "Snapshot cadence: revision % 50 == 0 triggers document_snapshots write"
  - "Lazy rebuild: on-demand loadRuntime(sessionId) instead of boot-time warm loading"

requirements-completed: [DATA-01, DATA-02]

# Metrics
duration: 7min
completed: 2026-03-29
---

# Phase 3 Plan 02: Durable Append and Snapshot Recovery Summary

**Durable canonical operation persistence with every-50-revision snapshot cadence and lazy runtime rebuild from snapshot-plus-replay against PostgreSQL**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-29T06:57:26Z
- **Completed:** 2026-03-29T07:04:26Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments
- CollaborationSessionRuntime gains a restore() static factory for hydrating from persisted state while preserving stale-op transform guarantees
- CollaborationPersistenceService durably appends canonical operations and creates snapshots exactly at revisions 50, 100, 150, etc.
- SnapshotRecoveryService lazily rebuilds a full runtime from the latest snapshot plus replay of later operations
- PostgreSQL-backed integration tests prove snapshot cadence at exact revision boundaries and rebuild correctness through canonical revision 53

## Task Commits

Each task was committed atomically:

1. **Task 1: Make the canonical runtime restorable** - `b3a109c` (feat)
2. **Task 2: Implement durable append and snapshot recovery services** - `8938e98` (feat)
3. **Task 3: Add PostgreSQL-backed integration tests** - `c29102d` (test)

_Note: TDD tasks had RED commit (test) merged into GREEN commit for cleaner history_

## Files Created/Modified
- `src/main/java/com/collabeditor/ot/service/CollaborationSessionRuntime.java` - Added restore() static factory for lazy rebuild
- `src/main/java/com/collabeditor/ot/service/CollaborationPersistenceService.java` - Durable append with snapshot cadence at every 50th revision
- `src/main/java/com/collabeditor/snapshot/service/SnapshotRecoveryService.java` - Lazy runtime rebuild from latest snapshot plus replay
- `src/main/java/com/collabeditor/ot/persistence/SessionOperationRepository.java` - Added findBySessionIdOrderByRevisionAsc for full replay
- `src/test/java/com/collabeditor/ot/CollaborationSessionRuntimeTest.java` - Restore-focused runtime tests
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceServiceTest.java` - Unit tests for persistence service
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java` - PostgreSQL-backed integration tests

## Decisions Made
- restore() is a static factory rather than a second constructor to make the lazy-rebuild entry point explicit
- SnapshotRecoveryService replays post-snapshot operations on the snapshot document before calling restore, ensuring the runtime receives the document at the actual latest revision (not just the snapshot revision)
- No-snapshot fallback replays the full operation list from revision 0 to reconstruct both document and history

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed SnapshotRecoveryService snapshot-plus-replay document reconstruction**
- **Found during:** Task 3 (PostgreSQL integration tests)
- **Issue:** loadRuntime passed the snapshot document (at revision 50) directly to restore without replaying later operations, producing a runtime at revision 50 instead of 53
- **Fix:** Added post-snapshot operation replay loop to reconstruct the document at the actual latest revision before calling restore
- **Files modified:** src/main/java/com/collabeditor/snapshot/service/SnapshotRecoveryService.java
- **Verification:** Integration test asserting canonical revision 53 now passes
- **Committed in:** c29102d (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Essential correctness fix caught by TDD integration test. No scope creep.

## Issues Encountered
- Gradle 8.14 is incompatible with Java 25 as the daemon JVM; resolved by setting JAVA_HOME to Corretto 24 (toolchain still auto-provisions Java 17 for compilation)
- Testcontainers requires explicit DOCKER_HOST for Colima; resolved with DOCKER_HOST=unix:///Users/abdullah/.colima/default/docker.sock

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Durable append and lazy rebuild services are ready for Plans 03 and 04 to wire into Redis coordination and distributed WebSocket handler
- SnapshotRecoveryService.loadRuntime is the single rebuild API for lazy hydration after restart or local eviction

## Self-Check: PASSED

All 7 files verified present. All 3 task commits verified in git log.

---
*Phase: 03-durable-persistence-and-multi-instance-coordination*
*Completed: 2026-03-29*
