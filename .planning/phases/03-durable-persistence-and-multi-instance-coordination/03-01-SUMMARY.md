---
phase: 03-durable-persistence-and-multi-instance-coordination
plan: 01
subsystem: database
tags: [postgresql, flyway, jpa, persistence, migration, testcontainers]

# Dependency graph
requires:
  - phase: 01-secure-access-and-session-lifecycle
    provides: V1 Flyway baseline with users, coding_sessions, session_participants tables
provides:
  - V2 Flyway migration adding session_operations, document_snapshots, execution_history tables
  - JPA entities for SessionOperation, DocumentStateSnapshot, ExecutionHistory
  - Repository interfaces with ordered replay, latest-snapshot lookup, and execution-history queries
  - PostgreSQL-backed constraint and repository integration tests
affects: [03-02, 03-03, 03-04, 04-sandboxed-code-execution]

# Tech tracking
tech-stack:
  added: []
  patterns: [append-only operation log with revision uniqueness, periodic document snapshots, constraint-level data integrity validation]

key-files:
  created:
    - src/main/resources/db/migration/V2__phase3_collaboration_persistence.sql
    - src/main/java/com/collabeditor/ot/persistence/entity/SessionOperationEntity.java
    - src/main/java/com/collabeditor/ot/persistence/SessionOperationRepository.java
    - src/main/java/com/collabeditor/snapshot/persistence/entity/DocumentStateSnapshotEntity.java
    - src/main/java/com/collabeditor/snapshot/persistence/DocumentStateSnapshotRepository.java
    - src/main/java/com/collabeditor/execution/persistence/entity/ExecutionHistoryEntity.java
    - src/main/java/com/collabeditor/execution/persistence/ExecutionHistoryRepository.java
    - src/test/java/com/collabeditor/ot/CollaborationPersistenceRepositoryTest.java
  modified:
    - src/test/java/com/collabeditor/migration/FlywayMigrationTest.java

key-decisions:
  - "DELETE constraint requires explicit length IS NOT NULL to defeat SQL three-valued logic NULL passthrough"

patterns-established:
  - "Append-only canonical operation log uniquely ordered by (session_id, revision)"
  - "Periodic document snapshots keyed by (session_id, revision) for bounded recovery replay"
  - "Execution-history schema foundation laid before Phase 4 writes execution rows"

requirements-completed: [DATA-01, DATA-02]

# Metrics
duration: 6min
completed: 2026-03-29
---

# Phase 3 Plan 1: Database Schema and Repository Foundation Summary

**Flyway V2 migration with append-only operation log, periodic document snapshots, and execution-history schema plus JPA repositories and PostgreSQL constraint tests**

## Performance

- **Duration:** 6 min
- **Started:** 2026-03-29T06:48:16Z
- **Completed:** 2026-03-29T06:54:48Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments
- V2 Flyway migration establishing three Phase 3 tables with CHECK constraints for operation type invariants
- Repository interfaces supporting ordered canonical replay, latest-snapshot lookup, and execution-history newest-first ordering
- PostgreSQL-backed integration tests proving revision ordering, snapshot selection, uniqueness enforcement, and INSERT/DELETE constraint behavior

## Task Commits

Each task was committed atomically:

1. **Task 1: Add Phase 3 Flyway schema and JPA entities** - `67c7242` (feat)
2. **Task 2: Add ordered replay, snapshot lookup, and execution-history repositories** - `a91981e` (feat)
3. **Task 3: Extend migration verification with Phase 3 durability constraints** - `37a741e` (test)

## Files Created/Modified
- `src/main/resources/db/migration/V2__phase3_collaboration_persistence.sql` - Flyway V2 migration adding session_operations, document_snapshots, execution_history
- `src/main/java/com/collabeditor/ot/persistence/entity/SessionOperationEntity.java` - JPA entity for canonical operation log
- `src/main/java/com/collabeditor/ot/persistence/SessionOperationRepository.java` - Ordered replay and latest-revision queries
- `src/main/java/com/collabeditor/snapshot/persistence/entity/DocumentStateSnapshotEntity.java` - JPA entity for document snapshots
- `src/main/java/com/collabeditor/snapshot/persistence/DocumentStateSnapshotRepository.java` - Latest-snapshot and at-revision lookup queries
- `src/main/java/com/collabeditor/execution/persistence/entity/ExecutionHistoryEntity.java` - JPA entity for execution history
- `src/main/java/com/collabeditor/execution/persistence/ExecutionHistoryRepository.java` - Newest-first execution history query
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceRepositoryTest.java` - PostgreSQL-backed repository integration tests
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java` - Extended with Phase 3 column, uniqueness, and constraint assertions

## Decisions Made
- DELETE constraint explicitly requires `length IS NOT NULL AND length > 0` to defeat SQL three-valued logic where NULL passthrough would allow omitting length on DELETE operations

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed DELETE constraint SQL three-valued logic passthrough**
- **Found during:** Task 3 (constraint verification test)
- **Issue:** `CHECK (... AND length > 0)` passes when length is NULL because `NULL > 0` evaluates to NULL and CHECK constraints pass on NULL
- **Fix:** Changed constraint to `CHECK (... AND length IS NOT NULL AND length > 0)`
- **Files modified:** src/main/resources/db/migration/V2__phase3_collaboration_persistence.sql
- **Verification:** deleteOperationRowCannotOmitLength test passes
- **Committed in:** 37a741e (Task 3 commit)

---

**Total deviations:** 1 auto-fixed (1 bug)
**Impact on plan:** Essential correctness fix for constraint enforcement. No scope creep.

## Issues Encountered
- Gradle 8.14 incompatible with Java 25 (system default); resolved by using JAVA_HOME pointed to Corretto 24.0.2 for all Gradle commands
- Testcontainers required DOCKER_HOST set to Colima socket path for container startup

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Durable schema foundation complete; Plan 02 can add persistence service for append-before-ack and snapshot cadence
- Repository APIs ready for lazy runtime hydration from latest snapshot plus replay
- Execution-history schema ready for Phase 4 to write execution records

## Self-Check: PASSED

All 9 created/modified files verified present. All 3 task commits (67c7242, a91981e, 37a741e) verified in git log.

---
*Phase: 03-durable-persistence-and-multi-instance-coordination*
*Completed: 2026-03-29*
