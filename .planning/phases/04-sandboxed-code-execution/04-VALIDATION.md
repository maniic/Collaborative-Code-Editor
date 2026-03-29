---
phase: 04
slug: sandboxed-code-execution
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-29
---

# Phase 04 - Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Redis-backed relay tests + Docker-backed execution integration tests |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*ExecutionControllerTest" --tests "*ExecutionServiceTest" --tests "*DockerSandboxRunnerTest" --tests "*ExecutionEventRelayServiceTest" --tests "*ExecutionIntegrationTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~120-240 seconds |

---

## Sampling Rate

- **After every task commit:** Run the narrowest matching command from the per-task map below.
- **After every plan wave:** Run `./gradlew test --tests "*ExecutionControllerTest" --tests "*ExecutionServiceTest" --tests "*DockerSandboxRunnerTest" --tests "*ExecutionEventRelayServiceTest" --tests "*ExecutionIntegrationTest"`
- **Before `$gsd-verify-work`:** Full suite must be green.
- **Max feedback latency:** 240 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 04-01-01 | 01 | 1 | EXEC-01, EXEC-04 | source-capture + lifecycle service tests | `./gradlew test --tests "*ExecutionServiceTest"` | ❌ 04 | ⬜ pending |
| 04-02-01 | 02 | 1 | EXEC-01, EXEC-02, EXEC-03 | Docker contract + HostConfig unit tests | `./gradlew test --tests "*DockerSandboxRunnerTest"` | ❌ 04 | ⬜ pending |
| 04-03-01 | 03 | 2 | EXEC-01, EXEC-04 | controller + relay + cooldown tests | `./gradlew test --tests "*ExecutionControllerTest" --tests "*ExecutionEventRelayServiceTest"` | ❌ 04 | ⬜ pending |
| 04-04-01 | 04 | 3 | EXEC-01, EXEC-02, EXEC-03, EXEC-04 | live Docker integration + final suite | `./gradlew test --tests "*ExecutionIntegrationTest"` | ❌ 04 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠ flaky*

---

## Wave 0 Requirements

- [ ] `build.gradle.kts` - add docker-java runtime dependencies before any execution implementation begins
- [ ] `src/main/java/com/collabeditor/execution/` - create the Phase 4 execution config, model, api, and service packages
- [ ] `src/test/java/com/collabeditor/execution/` - add focused execution service, runner, controller, relay, and integration test classes
- [ ] `src/main/resources/application.yml` and `src/test/resources/application-test.yml` - add explicit `app.execution` settings for timeout, queue, images, and sandbox limits

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Two participants on different backend instances see the same execution lifecycle | EXEC-01, EXEC-04 | The operator can most easily judge realtime room visibility by watching two live sockets on separate JVMs | Run two backend instances against shared PostgreSQL and Redis, connect one websocket client to each instance for the same room, enqueue one execution, and confirm both sockets receive `execution_updated` events for `QUEUED`, `RUNNING`, and the same final terminal result. |
| No-network sandbox behavior fails predictably | EXEC-03 | Live network-denial behavior is clearest when observed against a real container, not only mocked host config | Execute code that attempts outbound HTTP access and confirm the result is a controlled failure while the backend remains healthy and the execution finishes with a terminal status. |
| Java contract is explicit and user-visible | EXEC-02 | The clearest proof is seeing an invalid Java document fail with a compile/contract message rather than an internal exception | Run a room whose language is `JAVA` with source missing a package-less `Main` entrypoint and confirm the final execution result surfaces a clear contract/compile failure. |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 240s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-03-29
