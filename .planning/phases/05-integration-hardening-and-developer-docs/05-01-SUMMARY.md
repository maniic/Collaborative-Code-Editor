---
phase: 05-integration-hardening-and-developer-docs
plan: 01
subsystem: testing
tags: [gradle, junit5, testcontainers, integration-testing, docker, redis, postgresql]

# Dependency graph
requires:
  - phase: 04-sandboxed-code-execution
    provides: ExecutionIntegrationTest, ExecutionServiceTest, ExecutionEventRelayServiceTest
  - phase: 03-durable-persistence-and-multi-instance-coordination
    provides: DistributedCollaborationWebSocketHandlerTest, CollaborationPersistenceIntegrationTest
  - phase: 01-secure-access-and-session-lifecycle
    provides: FlywayMigrationTest
provides:
  - Dedicated Gradle integrationTest task as the canonical Phase 5 verification command
  - @Tag("integration") markers on all six focused Testcontainers-backed proof suites
  - One command to prove persistence bootstrap, Redis coordination, and Docker execution

affects:
  - 05-02
  - 05-03
  - 05-04

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "JUnit 5 @Tag(\"integration\") used to curate canonical proof suites behind one Gradle command"
    - "tasks.withType<Test> wiring shared across all Test tasks via Gradle task inheritance"

key-files:
  created: []
  modified:
    - build.gradle.kts
    - src/test/java/com/collabeditor/migration/FlywayMigrationTest.java
    - src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java
    - src/test/java/com/collabeditor/websocket/DistributedCollaborationWebSocketHandlerTest.java
    - src/test/java/com/collabeditor/execution/ExecutionServiceTest.java
    - src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java
    - src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java

key-decisions:
  - "The integrationTest task uses includeTags(\"integration\") rather than filename heuristics — focused suites stay focused while one command proves the whole stack"
  - "tasks.withType<Test> block provides shared Docker/Testcontainers env wiring automatically inherited by the registered integrationTest task"

patterns-established:
  - "Phase 5 proof suites: annotate with @Tag(\"integration\") to enroll in the canonical integrationTest run without moving test files"

requirements-completed:
  - QUAL-03

# Metrics
duration: 25min
completed: 2026-03-29
---

# Phase 5 Plan 01: Integration Test Curation Summary

**Dedicated `./gradlew integrationTest` task backed by JUnit @Tag("integration") markers across six focused Testcontainers proof suites covering PostgreSQL, Redis, and Docker execution**

## Performance

- **Duration:** 25 min
- **Started:** 2026-03-29T22:05:00Z
- **Completed:** 2026-03-29T22:30:00Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments

- `./gradlew integrationTest` is now the one obvious automated proof command for Phase 5
- All six existing focused integration suites (FlywayMigrationTest, CollaborationPersistenceIntegrationTest, DistributedCollaborationWebSocketHandlerTest, ExecutionServiceTest, ExecutionEventRelayServiceTest, ExecutionIntegrationTest) enrolled in the canonical run via @Tag("integration")
- Canonical verification run proves persistence bootstrap, Redis coordination/relay, and live Docker execution without a new mega-test

## Task Commits

Each task was committed atomically:

1. **Task 1: Add dedicated Gradle integrationTest task** - `c490b0e` (feat)
2. **Task 2: Tag persistence and Redis coordination suites** - `8bd5ab4` (feat)
3. **Task 3: Tag execution proof suites** - `05d71cc` (feat)

## Files Created/Modified

- `build.gradle.kts` - Added `tasks.register<Test>("integrationTest")` with `group="verification"` and `includeTags("integration")`; shared Docker/Testcontainers wiring via `tasks.withType<Test>` is inherited automatically
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java` - Added `@Tag("integration")` annotation
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java` - Added `@Tag("integration")` annotation
- `src/test/java/com/collabeditor/websocket/DistributedCollaborationWebSocketHandlerTest.java` - Added `@Tag("integration")` annotation
- `src/test/java/com/collabeditor/execution/ExecutionServiceTest.java` - Added `@Tag("integration")` annotation
- `src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java` - Added `@Tag("integration")` annotation
- `src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java` - Added `@Tag("integration")` annotation

## Decisions Made

- The integrationTest task relies on `tasks.withType<Test>` configuration inheritance so the Docker and Testcontainers environment is forwarded without duplicating `environment()` calls.
- Existing focused test classes are kept as-is; only `@Tag("integration")` is added — no test code is moved, merged, or restructured.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

Pre-existing environment constraint (not caused by plan changes): The running JVM is Java 25.0.1, which triggers a version-string parsing bug in the Kotlin compiler bundled with Gradle 8.14. This bug exists for all Gradle tasks (not just `integrationTest`) and is unrelated to the plan changes. Running with `JAVA_HOME` pointing to Java 24 resolves it. The `./gradlew integrationTest` exits with status 0 when invoked with Java 24.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- The canonical `./gradlew integrationTest` command is operational (requires Java 24; Java 25 is a pre-existing Gradle incompatibility)
- Phase 5 plan 02 (container packaging / Dockerfile) can proceed independently
- The integration suite enrollment pattern is established for any additional suites added in future plans

---
*Phase: 05-integration-hardening-and-developer-docs*
*Completed: 2026-03-29*
