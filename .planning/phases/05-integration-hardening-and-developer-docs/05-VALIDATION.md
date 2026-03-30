---
phase: 05
slug: integration-hardening-and-developer-docs
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-30
---

# Phase 05 - Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers + Docker Compose config verification + README grep checks |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew integrationTest` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~180-360 seconds |

---

## Sampling Rate

- **After every task commit:** Run the narrowest matching command from the per-task map below.
- **After every plan wave:** Run `./gradlew integrationTest`.
- **Before `$gsd-verify-work`:** Full suite must be green.
- **Max feedback latency:** 360 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | QUAL-03 | curated Testcontainers verification task | `./gradlew integrationTest` | ❌ 05 | ⬜ pending |
| 05-02-01 | 02 | 2 | DOCS-01 | health endpoint plus container build proof | `./gradlew test --tests "*Health*Test" && ./gradlew bootJar` | ❌ 05 | ⬜ pending |
| 05-03-01 | 03 | 3 | DOCS-01 | Compose stack contract validation | `docker compose config` | ❌ 05 | ⬜ pending |
| 05-04-01 | 04 | 4 | DOCS-01, DOCS-02 | README contract verification | `rg -n "## Prerequisites|## Quickstart|## Verification|## REST API|## WebSocket Protocol|## Architecture|## Design Decisions" README.md` | ❌ 05 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠ flaky*

---

## Wave 0 Requirements

- [ ] `build.gradle.kts` - add a dedicated `integrationTest` task before Phase 5 verification can use one canonical command
- [ ] `build.gradle.kts` and `src/main/resources/application.yml` - add Actuator health support that matches the already-permitted `/actuator/health` route
- [ ] `Dockerfile`, `.dockerignore`, `docker-compose.yml`, and `.env.example` - add the container packaging and Compose artifacts required by DOCS-01
- [ ] `README.md` - replace the placeholder with executable setup, verification, API, websocket, architecture, and design-decision documentation

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Full-stack Compose boot reaches a healthy app container | DOCS-01 | The cleanest proof is observing the actual container startup and health transitions | Set `APP_JWT_SECRET` and `DOCKER_SOCKET_PATH`, run `docker compose up --build`, and confirm `app`, `postgres`, and `redis` all become healthy or running with the app answering `GET /actuator/health`. |
| App container can launch Phase 4 sandbox executions through the mounted Docker socket | DOCS-01 | This depends on the host's Docker runtime and socket path, which is environment-specific | With the Compose stack running, register/login, create or join a room, enqueue a Python execution, and confirm the app container launches the sandbox and returns a terminal execution result. |
| README onboarding path is accurate from a clean shell | DOCS-01, DOCS-02 | Human docs quality cannot be fully reduced to grep checks | Follow the README in order on a clean checkout and confirm no undocumented setup step is required to start, verify, and understand the backend. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 360s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-03-30
