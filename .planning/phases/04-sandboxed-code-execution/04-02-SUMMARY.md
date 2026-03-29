---
phase: 04-sandboxed-code-execution
plan: 02
subsystem: execution
tags: [docker, sandbox, java, python, httpclient5]

# Dependency graph
requires:
  - phase: 04-01
    provides: Typed execution settings and canonical source snapshots
provides:
  - docker-java based Docker client configuration for Phase 4 execution work
  - Explicit Python and Java runtime contracts with fixed image and command rules
  - Docker sandbox runner that enforces isolation limits and cleans up containers and temp inputs
  - Unit and live integration proof for language execution contracts
affects: [04-03, 04-04, 05-integration-hardening-and-developer-docs]

# Tech tracking
tech-stack:
  added: [docker-java 3.7.1, Apache HttpClient5 5.5.1, Apache HttpCore5 5.3.6]
  patterns: [docker-java transport pinning, tmpfs-only writable execution workspace, live Docker contract verification]

key-files:
  created:
    - src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java
    - src/test/java/com/collabeditor/execution/DockerSandboxRunnerTest.java
    - src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java
  modified:
    - build.gradle.kts
    - src/main/java/com/collabeditor/execution/service/ExecutionLanguageSpecResolver.java

key-decisions:
  - "Docker execution uses docker-java directly; the backend never shells out to docker run."
  - "Sandbox input directories live under the user home so Colima and local Docker mounts can see the bind-mounted source."
  - "Writable execution paths use tmpfs mounts owned by uid/gid 65534 so non-root Java compilation works without widening filesystem access."

patterns-established:
  - "Language resolution is explicit and deterministic: PYTHON -> main.py, JAVA -> package-less Main.java."
  - "DockerSandboxRunner validates the locked sandbox config before every run and returns structured SandboxExecutionResult values."
  - "Live Docker integration tests verify the same runner used by production code."

requirements-completed: [EXEC-01, EXEC-02, EXEC-03]

# Metrics
duration: 24min
completed: 2026-03-29
---

# Phase 4 Plan 2: Docker Sandbox Contract Summary

**docker-java now drives fixed Python and Java sandbox contracts with tmpfs-only writable paths, strict non-root limits, and live Docker proof**

## Performance

- **Duration:** 24 min
- **Started:** 2026-03-29T20:18:00Z
- **Completed:** 2026-03-29T21:24:16Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments
- Replaced the older Docker dependency mix with a Phase 4-compatible docker-java stack and aligned Apache HttpClient/Core versions.
- Encoded exact Python and Java language contracts, including preflight Java `Main` validation.
- Added a real Docker sandbox runner and live integration suite covering successful runs, no-network behavior, readonly filesystem failures, and timeout handling.

## Task Commits

Plan work was committed as one coupled Phase 4 implementation because the execution foundation, sandbox runner, relay contract, and verification suite share a single execution API surface:

1. **Task 1-3: Execution admission, sandbox orchestration, relay delivery, and verification** - `c0a78ca` (feat)

## Files Created/Modified
- `build.gradle.kts` - Upgraded docker-java and pinned Apache HttpClient/Core versions that match the transport implementation.
- `src/main/java/com/collabeditor/execution/service/ExecutionLanguageSpecResolver.java` - Locked the Python and Java runtime contracts and Java entrypoint validation.
- `src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java` - Added the real container create/start/log/wait/kill/remove path with strict host config and shared-mount hardening.
- `src/test/java/com/collabeditor/execution/DockerSandboxRunnerTest.java` - Added unit coverage for image, command, host-config, and timeout behavior.
- `src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java` - Added live Docker-backed proof for Python, Java, timeout, no-network, and readonly filesystem behavior.

## Decisions Made
- Sandbox tmpfs mounts include uid/gid/mode options so the non-root runtime can compile Java inside `/workspace`.
- Timeout exceptions from docker-java are normalized to the `TIMED_OUT` lifecycle result instead of leaking transport-specific failures.
- Successful execution proof uses the same runner implementation as production rather than a fake sandbox adapter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Pinned Apache HttpClient/Core versions to match docker-java 3.7.1**
- **Found during:** Task 1 (Docker client configuration)
- **Issue:** Spring's managed BOM downgraded `httpclient5` and `httpcore5`, causing `ApacheDockerHttpClient` startup failures in Phase 4 tests.
- **Fix:** Added explicit `httpclient5`, `httpcore5`, and `httpcore5-h2` versions compatible with docker-java 3.7.1.
- **Files modified:** `build.gradle.kts`
- **Verification:** `./gradlew test --tests "*ExecutionServiceTest"`, `./gradlew test`
- **Committed in:** `c0a78ca`

**2. [Rule 1 - Bug] Moved sandbox input binds into a Docker-visible home-directory workspace**
- **Found during:** Task 3 (live Docker verification)
- **Issue:** System temp directories and default file permissions caused Docker/uid 65534 to miss or reject the mounted source files.
- **Fix:** Created sandbox input directories under `~/.collabeditor-sandbox`, then applied world-readable input permissions before container startup.
- **Files modified:** `src/main/java/com/collabeditor/execution/service/DockerSandboxRunner.java`
- **Verification:** `./gradlew test --tests "*ExecutionIntegrationTest"`, `./gradlew test`
- **Committed in:** `c0a78ca`

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug)
**Impact on plan:** Both fixes were required for the intended Docker runner contract to work on the real local Docker/Colima setup. No scope creep.

## Issues Encountered

- Docker transport startup initially failed because Spring's managed Apache HTTP stack was older than docker-java's expected runtime classes.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Queue admission and websocket lifecycle broadcasting can now execute real Python/Java sandbox jobs instead of mocked contracts.
- Final hardening already has live Docker proof and a stable runner to build on.

---
*Phase: 04-sandboxed-code-execution*
*Completed: 2026-03-29*
