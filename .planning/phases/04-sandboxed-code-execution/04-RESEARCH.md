# Phase 4: Sandboxed Code Execution - Research

**Researched:** 2026-03-29
**Domain:** Docker-sandboxed code execution, language runtime contracts, bounded async orchestration, and room-wide execution result delivery
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Execution is triggered through an authenticated session-scoped REST endpoint that enqueues a run against the current canonical document snapshot and returns an execution identifier plus initial status.
- Active room participants receive execution lifecycle updates and final results through additive typed WebSocket events on the existing session socket.
- Phase 4 sends structured final results (`status`, `stdout`, `stderr`, `exitCode`, timing) rather than raw live stream chunking.
- The executed source of truth is the current canonical persisted room document and revision at enqueue time.
- Python runs as a plain script with no wrapper layer.
- Java requires a single-file, package-less `Main` entrypoint with `public static void main(String[] args)`.
- Accepted executions move through explicit durable statuses such as `QUEUED`, `RUNNING`, and terminal states.
- The per-user five-second cooldown fails fast with an explicit rejection response.
- The execution worker pool uses a bounded FIFO queue, and full queues reject immediately.
- Execution completion and rejection semantics are visible to all active room participants, not just the requester.
- Every execution runs in Docker with `256MB` memory, `0.5` CPU, `10s` timeout, no network, read-only filesystem, and non-root execution.

### the agent's Discretion
- Exact REST path naming, DTO field names, and WebSocket event names.
- Exact execution status vocabulary beyond the locked queued/running/terminal model.
- Exact queue capacity, worker count, and Docker image tags.
- Exact writable-temp strategy needed to compile or run within an otherwise read-only container.

### Deferred Ideas (OUT OF SCOPE)
- Live stdout/stderr chunk streaming
- Java snippet wrapping or inferred entrypoint detection
- Multi-file project execution
- Languages beyond Java and Python
</user_constraints>

<research_summary>
## Summary

Phase 4 should be built as an asynchronous execution subsystem layered onto the existing room model, durable execution-history table, and Redis-backed multi-instance collaboration architecture. The safest design is:

1. Snapshot the current canonical room document and revision when the requester hits a new authenticated execution endpoint.
2. Enforce a distributed per-user cooldown in Redis so multiple backend instances cannot accept overlapping runs from the same user inside the five-second window.
3. Persist a `QUEUED` execution-history row immediately, then submit work into a bounded local `ThreadPoolTaskExecutor`.
4. Execute the code in Docker using docker-java with strict host config limits, a non-root UID/GID, `networkMode=none`, `readonlyRootfs=true`, and a tmpfs-backed writable workspace.
5. Persist lifecycle transitions (`QUEUED` -> `RUNNING` -> terminal) and relay execution updates across nodes so every room participant connected to any backend instance sees the same execution state on the existing session socket.

The key architectural point is that the queue can remain instance-local while still being correct at the product level, as long as:
- cooldown enforcement is distributed through Redis, and
- execution lifecycle/result events relay across instances through Redis-backed room event fan-out.

This avoids the cost and complexity of introducing a distributed job queue while still satisfying the portfolio target of `2-3` backend instances.

**Primary recommendation:** plan Phase 4 in four waves: execution contract and persistence foundation, Docker sandbox runner, async orchestration plus cross-instance result relay, then Docker-backed verification and final hardening.
</research_summary>

<standard_stack>
## Standard Stack

The established libraries and tools for this phase:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java | 17 toolchain (repo standard) | Runtime for execution orchestration and Docker integration | `build.gradle.kts` already targets Java 17 and the repo avoids preview-only language features. |
| Spring Boot | 3.3.4 (repo standard) | App framework, REST, security, configuration, and async orchestration | Already pinned and validated in the repo. |
| docker-java | 3.7.1 | Official Java Docker API client for create/start/wait/log/cleanup flows | The upstream `docker-java/docker-java` repo lists `3.7.1` as the latest release and exposes the HostConfig knobs Phase 4 needs. |
| Spring Data JPA | managed by Boot | Execution-history persistence and lifecycle updates | The repo already uses JPA repositories and entities throughout auth/session/collaboration code. |
| Spring Data Redis | managed by Boot | Distributed cooldown keys and cross-instance execution event relay | Redis is already a locked dependency and coordination layer from Phase 3. |
| Spring WebSocket | managed by Boot | Delivery of additive execution events on the existing session socket | Preserves the product’s single realtime room channel instead of introducing another socket. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| ThreadPoolTaskExecutor | Spring Framework current | Bounded FIFO worker queue with rejection semantics | Use for a fixed local execution queue with explicit `queueCapacity` and `TaskRejectedException` on saturation. |
| JUnit 5 + AssertJ | managed by Boot | Unit and service regression tests | Use for cooldown, status transitions, and contract tests. |
| Spring Boot Test | managed by Boot | Controller/service integration tests | Use for REST enqueue flow, auth, and websocket event delivery. |
| Testcontainers / live Docker-backed tests | repo standard | Real sandbox verification | Use in the final verification wave to prove actual container execution, limits, and language behavior. |

### Concrete defaults recommended for planning
| Setting | Recommended Value | Why |
|---------|-------------------|-----|
| Worker pool size | `2` | Matches the portfolio-scale target and avoids overwhelming typical laptop Docker setups. |
| Queue capacity | `8` | Small enough to stay bounded, large enough for manual demos with multiple users. |
| Cooldown key TTL | `5s` | Direct match to `EXEC-04`. |
| Python image | `python:3.12-slim` | Current stable Python runtime with predictable tool availability. |
| Java image | `eclipse-temurin:17-jdk-jammy` | Matches the Java 17 project baseline with a full `javac` toolchain. |
| Non-root user | `65534:65534` | Numeric UID/GID works even when image-specific usernames vary. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Local bounded worker pool + Redis cooldown | A fully distributed job queue in Redis or PostgreSQL | More globally fair, but substantially more machinery than the roadmap or scale target requires. |
| REST enqueue + websocket updates | WebSocket-only execution commands | Less API surface, but worse fit for queue admission, authz errors, and explicit HTTP rejection semantics. |
| Strict Java `Main` contract | Snippet wrapping or auto-detecting the main class | More convenient in demos, but much harder to reason about, document, and secure. |
| Final structured output | Live chunk streaming | Better UX later, but more complexity in relay, buffering, and ordering. |
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### Recommended Project Structure
```text
src/main/java/com/collabeditor/
├── execution/
│   ├── api/                     # REST execution controller + DTOs
│   ├── config/                  # ExecutionProperties, executor beans, Docker config
│   ├── model/                   # ExecutionStatus, language/runtime value objects
│   ├── persistence/             # Existing execution_history entity/repository
│   └── service/                 # enqueue orchestration, sandbox runner, rate limit, relay bridge
├── redis/
│   ├── config/                  # Existing Redis beans reused for cooldown + relay
│   └── service/                 # existing coordination services plus execution event relay if split here
├── websocket/
│   ├── protocol/                # additive execution_updated payload(s)
│   └── service/                 # local socket fan-out from relayed execution events
└── snapshot/ / ot/              # reused to read canonical source revision/document at enqueue time
```

### Pattern 1: Enqueue via REST, observe via room WebSocket
**What:** Accept execution requests on an authenticated REST endpoint, return `202 Accepted` with an execution identifier, then publish lifecycle updates over the existing session WebSocket.
**When to use:** Every run request in Phase 4.
**Concrete guidance:**
- Use `POST /api/sessions/{sessionId}/executions`.
- Return exact fields like `executionId`, `sessionId`, `language`, `sourceRevision`, and `status`.
- Add one additive websocket event type such as `execution_updated` with a payload that includes `executionId`, `status`, requester identity, timestamps, and optional `stdout`/`stderr`/`exitCode` on terminal updates.
- Keep the HTTP response for immediate admission/rejection and the socket for async lifecycle visibility.

### Pattern 2: Durable execution-history state machine
**What:** Persist execution lifecycle state transitions in `execution_history` rather than treating runs as ephemeral in-memory jobs.
**When to use:** At enqueue, at worker start, at completion, at timeout, and on hard rejection after admission.
**Concrete guidance:**
- Insert a row with `status='QUEUED'` before submitting to the executor.
- Update the same row to `RUNNING` when the worker begins, setting `started_at`.
- Set terminal `status`, `stdout`, `stderr`, `exit_code`, and `finished_at` when the container completes or times out.
- Use the already-locked `source_revision` field to tie the run to the canonical document snapshot used at enqueue time.

### Pattern 3: Read-only rootfs plus tmpfs workspace
**What:** Keep the container root filesystem read-only while mounting a small writable tmpfs workspace for compilation/runtime artifacts.
**When to use:** All Java and Python sandbox runs.
**Concrete guidance:**
- Configure Docker `HostConfig` with `withReadonlyRootfs(true)`, `withNetworkMode("none")`, `withMemory(268435456L)`, `withMemorySwap(268435456L)`, and `withNanoCPUs(500_000_000L)`.
- Run as `65534:65534` using the container user override instead of relying on image defaults.
- Mount the source input read-only and mount `/workspace` and `/tmp` as tmpfs for ephemeral writes.
- For Python, set `PYTHONDONTWRITEBYTECODE=1` to avoid `__pycache__` writes into the source mount.
- For Java, compile inside tmpfs (`javac -d /workspace/out /workspace/Main.java`) and run from there.

### Pattern 4: Distributed cooldown, local queue, relayed room updates
**What:** Enforce `1 execution/user/5s` with Redis keys while keeping the actual bounded work queue local to each instance.
**When to use:** On every enqueue attempt and every lifecycle broadcast.
**Concrete guidance:**
- Use a Redis key like `collab:execution:user:{userId}:cooldown` with `SET NX EX 5`.
- Reject immediately if the key already exists.
- Submit accepted work to a `ThreadPoolTaskExecutor` with fixed `corePoolSize=maxPoolSize=2` and `queueCapacity=8`.
- Translate `TaskRejectedException` into a durable rejection response rather than blocking the caller.
- Relay `execution_updated` events through Redis so all room participants receive updates regardless of which backend instance accepted the request.

### Pattern 5: Canonical source capture from durable room state
**What:** Build the runnable source from the persisted canonical room snapshot/revision, not from ad hoc client payloads.
**When to use:** Immediately before writing the `QUEUED` execution-history row.
**Concrete guidance:**
- Validate the requester is an active participant of the room, reusing existing session membership checks.
- Read the session language from `coding_sessions.language`.
- Load the current canonical document and revision via the existing Phase 3 snapshot/recovery services.
- Persist the exact `source_revision` used for the run so later viewers can understand which document revision produced a result.

### Anti-Patterns to Avoid
- **Shelling out to `docker run` directly:** Phase 4 explicitly chose Docker containers, but the code should use docker-java rather than brittle process execution.
- **Using a writable bind mount as the main workspace:** this weakens the read-only filesystem guarantee and makes cleanup/error handling harder.
- **Local-only cooldown maps:** they fail immediately once requests hit multiple backend instances.
- **Waiting synchronously for container completion in the controller:** this defeats the queue requirement and makes timeout handling much uglier.
- **Broadcasting execution results only on the accepting node:** room participants connected elsewhere will miss the lifecycle.
</architecture_patterns>

<dont_hand_roll>
## Don't Hand-Roll

Problems that already have a safe project-local or upstream-standard solution:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Docker orchestration | `ProcessBuilder` wrappers around `docker run` / `docker logs` | docker-java client + typed `HostConfig` and create/start/wait/log commands | More robust, testable, and aligned with the roadmap’s explicit library choice. |
| Bounded work queue | Custom thread + blocking queue plumbing | `ThreadPoolTaskExecutor` with explicit pool and queue capacity | Spring already gives predictable queueing and a standard rejection exception. |
| Multi-instance cooldown | In-memory `Map<UUID, Instant>` | Redis `SET NX EX` key with exact five-second TTL | Correct across `2-3` backend instances. |
| Room-wide execution visibility | Polling endpoint only | Existing session WebSocket plus relayed execution events | Matches the collaborative UX and existing realtime model. |
| Language detection | Infer from source text | Reuse `coding_sessions.language` | The language is already immutable and validated at room creation. |

**Key insight:** Phase 4 should extend the room’s durable canonical-state and relay architecture rather than inventing a separate per-node execution island.
</dont_hand_roll>

<common_pitfalls>
## Common Pitfalls

### Pitfall 1: Read-only rootfs breaks Java compilation immediately
**What goes wrong:** `javac` and some runtimes need writable scratch space, so a fully read-only container fails before executing user code.
**Why it happens:** developers set `readonlyRootfs=true` without any writable tmpfs or writable work directory.
**How to avoid:** mount a dedicated tmpfs workspace and `/tmp`, keep the source mount read-only, and compile or run only inside the tmpfs path.
**Warning signs:** containers exit with file-system write errors before user code starts, or Python tries to create `__pycache__` in a read-only path.

### Pitfall 2: Cooldown works on one node but fails across two nodes
**What goes wrong:** the same user can spam runs by alternating requests across backend instances.
**Why it happens:** cooldown is enforced in local memory or inside the local executor only.
**How to avoid:** acquire a Redis cooldown key before creating the `QUEUED` row, and reject if the key already exists.
**Warning signs:** multi-instance manual tests show overlapping accepted runs from the same user inside five seconds.

### Pitfall 3: HTTP enqueue path blocks until the container exits
**What goes wrong:** the API request hangs for up to ten seconds or longer, tying execution success to HTTP connection lifetime.
**Why it happens:** the controller or service waits synchronously on the Docker container instead of enqueuing background work.
**How to avoid:** insert `QUEUED`, submit to `ThreadPoolTaskExecutor`, return `202`, and let websocket updates carry progress.
**Warning signs:** API latency equals program runtime, or queue saturation never produces an immediate rejection.

### Pitfall 4: Execution results are only visible on the origin instance
**What goes wrong:** some room participants never see `RUNNING` or final outputs because they are connected to a different node.
**Why it happens:** the execution worker sends local websocket messages directly instead of relaying a room-scoped event.
**How to avoid:** publish execution lifecycle updates through Redis-backed room relay before local fan-out.
**Warning signs:** single-instance tests pass, but two-node manual tests show results only on one side.

### Pitfall 5: Timed-out containers leak resources
**What goes wrong:** timed-out or failed runs leave containers, tmp dirs, or attached callbacks behind.
**Why it happens:** timeout handling stops at the Java future level and forgets Docker cleanup.
**How to avoid:** treat timeout as a terminal execution path that force-kills the container, captures whatever logs exist, marks `TIMED_OUT`, and removes the container in `finally`.
**Warning signs:** `docker ps -a` shows orphaned execution containers, or repeated runs gradually degrade the host.
</common_pitfalls>

## Validation Architecture

Phase 4 should validate correctness in four layers:

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Redis-backed tests + Docker-backed execution integration tests |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*ExecutionControllerTest" --tests "*ExecutionServiceTest" --tests "*DockerSandboxRunnerTest" --tests "*ExecutionEventRelayServiceTest" --tests "*ExecutionIntegrationTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~120-240 seconds |

**Recommended verification shape:**
- Wave 1 proves request validation, membership/authz, durable `QUEUED` writes, and explicit rejection semantics.
- Wave 2 proves Python and Java sandbox execution against real Docker containers, including compile/run success and structured output capture.
- Wave 3 proves Redis cooldown and cross-instance execution event relay, plus websocket fan-out to local sockets.
- Wave 4 proves timeout, read-only filesystem, no-network behavior, queue saturation, and final room-visible execution lifecycle.

**Manual-only checks to keep visible during planning:**
- Open two room participants on different backend instances, enqueue one run, and confirm both clients observe queued/running/finished lifecycle on the same room socket.
- Run Python that attempts outbound network access and confirm the result is a controlled failure under `networkMode=none`.
- Run Java code without a valid `Main` entrypoint and confirm the user receives a clear compile-time failure rather than an internal server error.

<open_questions>
## Open Questions

1. **Where execution relay types should live**
   - What we know: execution lifecycle must broadcast across nodes and reach room sockets on any backend instance.
   - What's unclear: whether the cleanest implementation is extending the existing relay service with new execution event types or creating a parallel execution relay service and model.
   - Recommendation: keep the transport shared (Redis pub/sub) but use execution-specific payload/model classes rather than overloading collaboration revision events.

2. **How much Docker image management Phase 4 should own**
   - What we know: first-run ergonomics improve if the service can pull missing images automatically.
   - What's unclear: whether image pulls should happen lazily on the first request, eagerly at startup, or be left to operator documentation.
   - Recommendation: support lazy ensure-image behavior per runtime, but keep image names configurable so Phase 5 docs can still specify pre-pull steps.

3. **Whether to expose execution-history reads in Phase 4**
   - What we know: the durable schema already stores execution results.
   - What's unclear: whether Phase 4 needs a `GET /executions` API now or can rely on websocket delivery only.
   - Recommendation: keep the minimum required POST enqueue contract in scope for Phase 4; history-read APIs can wait unless planning finds they materially simplify verification.
</open_questions>

<sources>
## Sources

### Primary (HIGH confidence)
- `.planning/phases/04-sandboxed-code-execution/04-CONTEXT.md` — locked Phase 4 decisions.
- `.planning/ROADMAP.md` — Phase 4 goal, requirement mapping, and success criteria.
- `.planning/REQUIREMENTS.md` — `EXEC-01` through `EXEC-04`.
- `.planning/STATE.md` — prior project decisions, including Docker as mandatory sandbox and Redis as an existing coordination layer.
- `src/main/java/com/collabeditor/execution/persistence/entity/ExecutionHistoryEntity.java` — existing durable execution-history record shape.
- `src/main/java/com/collabeditor/execution/persistence/ExecutionHistoryRepository.java` — current repository seam for execution rows.
- `src/main/java/com/collabeditor/session/service/SessionService.java` — authoritative `JAVA`/`PYTHON` language contract and participant/session lifecycle rules.
- `src/main/java/com/collabeditor/session/api/SessionController.java` — current authenticated REST controller pattern.
- `src/main/java/com/collabeditor/common/api/ApiExceptionHandler.java` — current explicit error-response pattern.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` and `src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java` — additive typed realtime event model.
- `src/main/java/com/collabeditor/websocket/service/DistributedCollaborationGateway.java` — established Redis-backed room relay pattern for cross-instance visibility.

### External primary docs (HIGH confidence)
- `https://github.com/docker-java/docker-java` — official docker-java repository; the repo page lists `3.7.1` as the latest release and shows the maintained transport modules.
- `https://javadoc.io/static/com.github.docker-java/docker-java-core/3.2.4/com/github/dockerjava/core/DefaultDockerClientConfig.Builder.html` — docker-java builder API for Docker host/TLS configuration.
- `https://javadoc.io/static/com.github.docker-java/docker-java-api/3.2.8/com/github/dockerjava/api/model/HostConfig.html` — HostConfig methods for memory, CPU, network mode, and read-only rootfs.
- `https://docs.docker.com/engine/containers/resource_constraints/` — official Docker memory and CPU constraint semantics.
- `https://docs.docker.com/reference/cli/docker/container/run/` — official Docker run reference for `--read-only` and `--user`.
- `https://docs.docker.com/engine/storage/tmpfs/` — official Docker tmpfs behavior and mount syntax.
- `https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor.html` — official Spring `ThreadPoolTaskExecutor` behavior, queue capacity, and rejection semantics.

### Secondary (HIGH confidence)
- `.planning/phases/03-durable-persistence-and-multi-instance-coordination/03-RESEARCH.md` — prior Redis coordination and cross-instance relay guidance that Phase 4 should extend.
- `.planning/phases/03-durable-persistence-and-multi-instance-coordination/03-VALIDATION.md` — prior Nyquist validation pattern for per-wave verification.
</sources>
