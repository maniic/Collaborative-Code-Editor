---
phase: 04-sandboxed-code-execution
plan: 04
subsystem: testing
tags: [docker, integration, cleanup, timeout, relay]

# Dependency graph
requires:
  - phase: 04-02
    provides: Live Docker sandbox runner and language contracts
  - phase: 04-03
    provides: Async enqueue orchestration and execution relay path
provides:
  - Live Docker-backed proof for Python, Java, timeout, no-network, and readonly filesystem guarantees
  - Ordered execution lifecycle relay delivery across backend nodes
  - Sandbox permission and tmpfs ownership hardening needed for real non-root execution
  - Green focused Phase 4 pack and green repository-wide test suite
affects: [05-integration-hardening-and-developer-docs]

# Tech tracking
tech-stack:
  added: []
  patterns: [live Docker regression gate, ordered redis relay callbacks, sandbox hardening through real execution feedback]

key-files:
  created:
    - src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java
  modified:
    - src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java
    - src/main/java/com/collabeditor/redis/config/RedisConfig.java
    - src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java

key-decisions:
  - "Sandbox tmpfs mounts explicitly set uid/gid/mode so non-root Java compilation and runtime writes succeed without widening access."
  - "docker-java timeout exceptions are normalized to the Phase 4 TIMED_OUT lifecycle state."
  - "Execution relay ordering is preserved by running Redis pub/sub callbacks on a synchronous task executor."

patterns-established:
  - "Real Docker integration tests run in the normal Gradle suite rather than a one-off manual script."
  - "Colima-friendly sandbox inputs live under the user home and are cleaned up after each run."
  - "Phase closeout runs both the focused execution pack and the full repository suite."

requirements-completed: [EXEC-01, EXEC-02, EXEC-03, EXEC-04]

# Metrics
duration: 30min
completed: 2026-03-29
---

# Phase 4 Plan 4: Hardening and Verification Summary

**Live Docker execution, sandbox cleanup, and ordered execution relay delivery are now proven end to end with a green full suite**

## Performance

- **Duration:** 30 min
- **Started:** 2026-03-29T20:50:00Z
- **Completed:** 2026-03-29T21:24:16Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- Closed the remaining real-environment gaps uncovered by live Docker execution: bind-mount location, input permissions, tmpfs ownership, and timeout normalization.
- Proved that two backend nodes subscribed to the same room execution channel receive the same `QUEUED`, `RUNNING`, and `COMPLETED` lifecycle payloads.
- Finished Phase 4 with a green focused execution pack and a green repository-wide `./gradlew test`.

## Task Commits

Plan work was committed as one coupled Phase 4 implementation because the execution foundation, sandbox runner, relay contract, and verification suite share a single execution API surface:

1. **Task 1-3: Execution admission, sandbox orchestration, relay delivery, and verification** - `c0a78ca` (feat)

## Files Created/Modified
- `src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java` - Hardened bind-mount location, POSIX input permissions, tmpfs ownership, and timeout handling.
- `src/main/java/com/collabeditor/redis/config/RedisConfig.java` - Serialized Redis callback execution to preserve execution lifecycle ordering.
- `src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java` - Added the live Docker regression gate for success, timeout, no-network, and readonly filesystem behavior.
- `src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java` - Added the final two-node lifecycle visibility proof.

## Decisions Made
- Real execution hardening stays in production code instead of being papered over in tests.
- Execution lifecycle ordering is part of the observable contract and is protected at the Redis listener layer.
- Live Docker verification remains mandatory for Phase 4 because mocked host-config assertions are not enough.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed non-root write access for the tmpfs workspace**
- **Found during:** Task 1-2 (live Docker integration and cleanup hardening)
- **Issue:** Java compilation inside `/workspace` failed because the tmpfs mount was root-owned while the sandbox ran as `65534:65534`.
- **Fix:** Added uid/gid/mode options to `/workspace` and `/tmp` tmpfs mounts.
- **Files modified:** `src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java`
- **Verification:** `./gradlew test --tests "*ExecutionIntegrationTest"`, `./gradlew test`
- **Committed in:** `c0a78ca`

**2. [Rule 1 - Bug] Normalized docker-java wait timeout failures to TIMED_OUT**
- **Found during:** Task 2 (live timeout verification)
- **Issue:** docker-java surfaced timeouts as an `Awaiting status code timeout.` runtime exception, which incorrectly mapped to `FAILED`.
- **Fix:** Treated that wait-path exception as the intended timeout signal, then killed the container and returned `TIMED_OUT`.
- **Files modified:** `src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java`
- **Verification:** `./gradlew test --tests "*ExecutionIntegrationTest"`, `./gradlew test`
- **Committed in:** `c0a78ca`

**3. [Rule 2 - Missing Critical] Preserved execution relay ordering across Redis callbacks**
- **Found during:** Task 3 (cross-instance lifecycle visibility test)
- **Issue:** Async Redis listener dispatch could deliver `RUNNING` before `QUEUED` on a local node even though publish order was correct.
- **Fix:** Configured the Redis listener container to use a synchronous task executor and matched the same ordering model in relay tests.
- **Files modified:** `src/main/java/com/collabeditor/redis/config/RedisConfig.java`, `src/test/java/com/collabeditor/execution/ExecutionEventRelayServiceTest.java`
- **Verification:** `./gradlew test --tests "*ExecutionControllerTest" --tests "*ExecutionEventRelayServiceTest" --tests "*ExecutionIntegrationTest"`, `./gradlew test`
- **Committed in:** `c0a78ca`

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 missing critical)
**Impact on plan:** All three changes were necessary for the live Docker and distributed relay guarantees that define Phase 4. No scope creep.

## Issues Encountered

- Real Docker runs on the local Colima-backed setup surfaced mount visibility, tmpfs ownership, and timeout-translation bugs that unit tests could not catch.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 5 can treat execution as a shipped backend capability and focus on integration hardening, docker-compose, and onboarding docs.
- The repo now has stable execution tests to guard future changes in docs/setup work.

---
*Phase: 04-sandboxed-code-execution*
*Completed: 2026-03-29*
