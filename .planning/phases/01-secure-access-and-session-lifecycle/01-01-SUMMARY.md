---
phase: 01-secure-access-and-session-lifecycle
plan: 01
subsystem: database
tags: [spring-boot, gradle, flyway, postgresql, testcontainers, schema]

# Dependency graph
requires: []
provides:
  - "Gradle wrapper and Spring Boot application skeleton"
  - "build.gradle.kts with all Phase 1 dependencies (web, security, JPA, flyway, JWT, testcontainers)"
  - "Flyway V1 baseline schema: users, refresh_sessions, coding_sessions, session_participants"
  - "Testcontainers-based Flyway migration smoke test"
affects: [01-02-PLAN, 01-03-PLAN, all-later-phases]

# Tech tracking
tech-stack:
  added: [spring-boot-3.3.4, flyway, postgresql-driver, jjwt-0.12.5, testcontainers-1.21.4, gradle-8.14]
  patterns: [gradle-kotlin-dsl, flyway-versioned-migrations, testcontainers-dynamic-property-source, java-17-toolchain-with-foojay-resolver]

key-files:
  created:
    - build.gradle.kts
    - settings.gradle.kts
    - src/main/java/com/collabeditor/CollaborativeCodeEditorApplication.java
    - src/main/resources/application.yml
    - src/main/resources/db/migration/V1__phase1_baseline.sql
    - src/test/java/com/collabeditor/migration/FlywayMigrationTest.java
    - src/test/resources/application-test.yml
  modified: []

key-decisions:
  - "Gradle 8.14 instead of 8.10 to support Java 24 as daemon runtime"
  - "Foojay toolchain resolver for auto-provisioning Java 17 since only Java 24/25 installed locally"
  - "Testcontainers 1.21.4 with docker-java ~/.docker-java.properties for Docker API 1.44+ compatibility"
  - "Case-insensitive email uniqueness via functional unique index (not inline UNIQUE constraint)"

patterns-established:
  - "Gradle test task forwards DOCKER_HOST and DOCKER_API_VERSION for Colima/non-standard Docker setups"
  - "Flyway migrations in src/main/resources/db/migration with V prefix naming"
  - "Test profile via @ActiveProfiles('test') with application-test.yml"
  - "Testcontainers PostgreSQLContainer with @DynamicPropertySource for test datasource wiring"

requirements-completed: [QUAL-01]

# Metrics
duration: 21min
completed: 2026-03-27
---

# Phase 1 Plan 01: Bootstrap Backend Foundation and Flyway Schema Summary

**Spring Boot 3.3.4 skeleton with Gradle 8.14 wrapper, Flyway V1 baseline schema for auth and session lifecycle, and Testcontainers migration smoke test against PostgreSQL 16**

## Performance

- **Duration:** 21 min
- **Started:** 2026-03-27T03:09:27Z
- **Completed:** 2026-03-27T03:30:37Z
- **Tasks:** 3
- **Files modified:** 9

## Accomplishments
- Gradle wrapper with Kotlin DSL build, Spring Boot 3.3.4, and all Phase 1 dependencies wired
- Flyway V1 baseline migration defining users, refresh_sessions, coding_sessions, and session_participants with indexes and constraints
- PostgreSQL-backed migration smoke test verifying schema columns and constraint behavior via Testcontainers

## Task Commits

Each task was committed atomically:

1. **Task 1: Bootstrap Gradle wrapper, Spring Boot build, and base configuration** - `3564da3` (feat)
2. **Task 2: Create the Phase 1 baseline Flyway schema** - `fab6dac` (feat)
3. **Task 3: Add PostgreSQL-backed Flyway migration smoke test** - `eac96d5` (feat)

## Files Created/Modified
- `.gitignore` - Excludes .gradle, build, IDE, OS artifacts
- `build.gradle.kts` - Spring Boot 3.3.4 with web, security, JPA, validation, flyway, JWT, testcontainers deps
- `settings.gradle.kts` - Root project name and Foojay toolchain resolver
- `gradlew` / `gradlew.bat` / `gradle/wrapper/*` - Gradle 8.14 wrapper
- `src/main/java/com/collabeditor/CollaborativeCodeEditorApplication.java` - Spring Boot entry point
- `src/main/resources/application.yml` - Datasource, JPA, Flyway configuration with env var support
- `src/main/resources/db/migration/V1__phase1_baseline.sql` - Four-table baseline schema
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java` - 6 tests: column presence + constraint behavior
- `src/test/resources/application-test.yml` - Test profile with JPA validate and Flyway config

## Decisions Made
- **Gradle 8.14 over 8.10:** Java 25 (installed locally) is not supported by Gradle 8.10. Upgraded to 8.14 which supports Java 24 (Corretto 24 used as daemon runtime).
- **Foojay toolchain resolver:** No local Java 17 installation. Added toolchain auto-provisioning via Foojay so Gradle downloads Temurin 17 for compilation.
- **Testcontainers 1.21.4:** Default version had docker-java API version 1.32 incompatible with Docker 29.x (requires API 1.44+). Used ~/.docker-java.properties to set api.version=1.44.
- **Functional unique index for email:** PostgreSQL does not support UNIQUE(LOWER(email)) as an inline constraint. Used CREATE UNIQUE INDEX instead.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Gradle 8.10 incompatible with Java 25 runtime**
- **Found during:** Task 1 (Bootstrap Gradle wrapper)
- **Issue:** Gradle 8.10 does not support Java 25 (installed locally); build failed immediately
- **Fix:** Upgraded to Gradle 8.14 and used Corretto 24 as daemon JVM via JAVA_HOME
- **Files modified:** gradle/wrapper/gradle-wrapper.properties
- **Verification:** ./gradlew help exits 0
- **Committed in:** 3564da3

**2. [Rule 3 - Blocking] No local Java 17 for toolchain compilation**
- **Found during:** Task 2 (compileJava)
- **Issue:** Java toolchain set to 17 but no JDK 17 installed; Gradle cannot compile
- **Fix:** Added Foojay toolchain resolver plugin to settings.gradle.kts for auto-provisioning
- **Files modified:** settings.gradle.kts
- **Verification:** ./gradlew compileJava exits 0
- **Committed in:** fab6dac

**3. [Rule 1 - Bug] UNIQUE(LOWER(email)) syntax error in PostgreSQL**
- **Found during:** Task 3 (Flyway migration test)
- **Issue:** PostgreSQL rejects functional expressions in inline UNIQUE constraints (SQL error 42601)
- **Fix:** Replaced with CREATE UNIQUE INDEX on LOWER(email)
- **Files modified:** src/main/resources/db/migration/V1__phase1_baseline.sql
- **Verification:** FlywayMigrationTest passes all 6 tests
- **Committed in:** eac96d5

**4. [Rule 3 - Blocking] Docker not installed; Testcontainers cannot start containers**
- **Found during:** Task 3 (Flyway migration test)
- **Issue:** No Docker runtime on machine; Testcontainers threw IllegalStateException
- **Fix:** Installed Docker CLI and Colima via Homebrew, configured ~/.testcontainers.properties and ~/.docker-java.properties
- **Files modified:** build.gradle.kts (Docker env forwarding in test task)
- **Verification:** FlywayMigrationTest passes
- **Committed in:** eac96d5

**5. [Rule 3 - Blocking] docker-java API version 1.32 rejected by Docker 29.x daemon**
- **Found during:** Task 3 (Flyway migration test)
- **Issue:** Docker 29.x requires minimum API 1.44; Testcontainers' shaded docker-java sends 1.32
- **Fix:** Created ~/.docker-java.properties with api.version=1.44
- **Files modified:** (user-home config only)
- **Verification:** FlywayMigrationTest passes
- **Committed in:** eac96d5

---

**Total deviations:** 5 auto-fixed (1 bug, 4 blocking)
**Impact on plan:** All fixes necessary for build and test execution. No scope creep. Final schema and test match plan spec exactly.

## Issues Encountered
- Docker API version mismatch required deep investigation of Testcontainers' shaded docker-java internals to identify that DOCKER_API_VERSION env var is not supported -- only ~/.docker-java.properties with api.version key works.

## User Setup Required

**Local Docker runtime required for Testcontainers tests.** Developers need:
- Docker runtime (Docker Desktop, Colima, or Podman) with Docker API 1.44+
- `JAVA_HOME` pointing to Java 17+ (or auto-provisioned via Foojay toolchain)
- For Colima users: `DOCKER_HOST=unix://~/.colima/default/docker.sock` and `~/.docker-java.properties` with `api.version=1.44`

## Next Phase Readiness
- Build system and schema baseline ready for auth lifecycle implementation (01-02-PLAN)
- All four tables needed by Phase 1 exist and pass migration tests
- Testcontainers pattern established for future integration tests

---
*Phase: 01-secure-access-and-session-lifecycle*
*Completed: 2026-03-27*
