# Phase 5: Integration Hardening and Developer Docs - Research

**Researched:** 2026-03-30
**Domain:** Testcontainers-backed integration verification, containerized local reproduction, and backend onboarding documentation
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Phase 5 must expose one canonical automated verification path, but that path should be composed of focused Testcontainers-backed suites rather than one monolithic mega-test.
- The canonical verification run must prove Flyway/PostgreSQL bootstrapping, durable collaboration persistence and recovery, Redis-backed coordination/relay behavior, and Docker-backed execution behavior.
- `docker-compose` is the canonical local reproduction path and must start `app`, `postgres`, and `redis` together.
- The README should prioritize first-run success, environment variables, setup commands, verification commands, and expected behavior before narrative material.
- The README should document representative backend contracts rather than a generated full reference manual.
- The README should make the current runtime contract explicit: `PYTHON`, single-file package-less `JAVA Main`, room-visible execution updates, and fixed sandbox constraints.
- Documentation should include one high-signal system architecture diagram and explicit rationale for the already-shipped design decisions.

### the agent's Discretion
- Exact Gradle task structure used to expose the canonical integration run.
- Exact Dockerfile, Compose healthcheck, and service naming choices.
- Exact README organization and example payload selection.

### Deferred Ideas (OUT OF SCOPE)
- Generated OpenAPI or Swagger UI as a separate documentation system
- CI/CD pipeline automation
- Kubernetes or production deployment infrastructure
- Additional runtime languages or frontend work
</user_constraints>

<research_summary>
## Summary

Phase 5 should harden the repo around one developer story:

1. Set a small set of required environment variables, especially a non-empty `APP_JWT_SECRET` and a Docker socket path.
2. Start the backend stack via `docker compose up --build`.
3. Validate the shipped backend with one obvious Testcontainers-backed Gradle command.
4. Read one README that explains the real contracts already implemented in code.

The existing codebase already has most of the hard parts:
- `FlywayMigrationTest` proves PostgreSQL bootstrapping.
- `CollaborationPersistenceIntegrationTest` proves persistence, snapshot cadence, and recovery.
- `DistributedCollaborationWebSocketHandlerTest` proves PostgreSQL plus Redis multi-instance collaboration behavior.
- `ExecutionServiceTest`, `ExecutionEventRelayServiceTest`, and `ExecutionIntegrationTest` already cover execution persistence, Redis event relay, and real Docker sandbox behavior.

The main Phase 5 gaps are packaging and reproducibility:
- there is no canonical Gradle entrypoint that curates the existing integration suites into one run,
- there is no Dockerfile or Compose stack,
- the README is effectively empty,
- `SecurityConfig` already permits `/actuator/health`, but `spring-boot-starter-actuator` is not in `build.gradle.kts`, so the health surface implied by security config does not exist yet.

**Primary recommendation:** plan Phase 5 in four waves:
- Wave 1: curate the canonical Testcontainers verification task and mark the exact suites that belong in it,
- Wave 2: add app container packaging and an actual health endpoint contract for Compose,
- Wave 3: add `.env.example` plus `docker-compose.yml` with explicit Docker socket wiring for execution,
- Wave 4: replace the placeholder README with quickstart, verification, API/WebSocket contract, architecture diagram, and design rationale.
</research_summary>

<standard_stack>
## Standard Stack

The established libraries and tools for this phase:

### Core
| Tool | Version/Source | Purpose | Why Standard |
|------|----------------|---------|--------------|
| Gradle Test task | repo standard | Canonical verification entrypoint | The repo already runs all tests through Gradle and forwards Docker/Testcontainers env correctly in `build.gradle.kts`. |
| JUnit 5 tags plus a dedicated `Test` task | repo standard pattern | Curate focused integration suites without moving test sources | Lowest-churn way to expose `integrationTest` while keeping existing tests under `src/test/java`. |
| Testcontainers `1.21.4` | already in repo | Real PostgreSQL, Redis, and Docker-backed verification | Already adopted and validated in the current test suite. |
| Spring Boot Actuator | additive Boot starter | Health endpoint for Compose and local diagnostics | `SecurityConfig` already permits `/actuator/health`; adding Actuator makes that contract real. |
| Docker multi-stage build | Dockerfile in repo | Reproducible app container image | Standard way to package a Spring Boot app for Compose without adding external build tooling. |
| Docker Compose v2 | roadmap target | Local orchestration for app, PostgreSQL, and Redis | Explicit project requirement for DOCS-01. |
| Mermaid in `README.md` | Markdown-native | Architecture diagram | Portable, readable in GitHub, and avoids image-asset churn. |

### Concrete defaults recommended for planning
| Setting | Recommended Value | Why |
|---------|-------------------|-----|
| Canonical integration command | `./gradlew integrationTest` | Clear, memorable, and keeps the curated proof separate from the full suite. |
| Tagged suites | `FlywayMigrationTest`, `CollaborationPersistenceIntegrationTest`, `DistributedCollaborationWebSocketHandlerTest`, `ExecutionServiceTest`, `ExecutionEventRelayServiceTest`, `ExecutionIntegrationTest` | Collectively prove the persistence, Redis coordination, and execution boundaries already shipped. |
| App image build | multi-stage Dockerfile using `./gradlew bootJar` and Java 17 runtime image | Matches the repo's wrapper-based build and Spring Boot packaging model. |
| Compose env contract | `.env.example` with `APP_DB_*`, `APP_REDIS_*`, `APP_JWT_SECRET`, `DOCKER_SOCKET_PATH` | Captures the real runtime knobs already present in `application.yml` plus the Docker socket requirement Phase 4 implies. |
| App healthcheck path | `/actuator/health` | Already referenced in security config and conventional for Spring Boot Compose setups. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JUnit tags plus `integrationTest` | dedicated `src/integrationTest` source set | Cleaner long-term separation, but requires moving or duplicating existing suites with no Phase 5 product benefit. |
| Manual endpoint docs in README | SpringDoc/OpenAPI generation | Nice later, but Phase 5 only requires representative API docs and the repo has no OpenAPI setup today. |
| App container talking to mounted Docker socket | Running app locally while Compose starts only infra | Simpler for the execution runtime, but conflicts with the locked choice that full-stack Compose is the canonical path. |
| Mermaid architecture diagram | external SVG/PNG asset | More polished visuals possible, but worse maintenance and more repo overhead. |
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### Pattern 1: Curated integration verification through JUnit tags
**What:** Keep the existing focused integration tests where they are, annotate the canonical ones with a shared integration tag, and expose one Gradle `integrationTest` task that runs those suites only.
**When to use:** Phase 5 automated verification and README verification instructions.
**Concrete guidance:**
- Add `@Tag("integration")` to the curated proof suites instead of inventing a mega-test.
- Register `integrationTest` in `build.gradle.kts` as a `Test` task that uses JUnit Platform tags and inherits the same Docker/Testcontainers env forwarding as the default `test` task.
- Keep `./gradlew test` as the full regression command; treat `./gradlew integrationTest` as the canonical Phase 5 proof run.

### Pattern 2: Make the Compose health surface explicit
**What:** Align runtime healthchecks with the contract already implied by security configuration.
**When to use:** App container healthchecks and documented local startup.
**Concrete guidance:**
- Add `spring-boot-starter-actuator` and expose health.
- Keep `/actuator/health` unauthenticated as already allowed in `SecurityConfig`.
- Use the same path in Compose healthchecks and README smoke steps.

### Pattern 3: Treat Docker socket access as part of the local stack contract
**What:** A Compose-hosted app must reach a Docker daemon to launch Phase 4 sandbox containers.
**When to use:** `docker-compose.yml`, `.env.example`, and README setup instructions.
**Concrete guidance:**
- Mount a host socket path into the app container, with env substitution such as `${DOCKER_SOCKET_PATH:-/var/run/docker.sock}:/var/run/docker.sock`.
- Set `DOCKER_HOST=unix:///var/run/docker.sock` inside the app container so `DefaultDockerClientConfig` resolves the mounted socket predictably.
- Document that Docker Desktop/Linux users can keep the default path, while Colima or alternative runtimes may need `DOCKER_SOCKET_PATH` overridden.

### Pattern 4: README sourced from live contracts, not invented examples
**What:** Derive docs from controllers, protocol enums, and `application.yml`.
**When to use:** All API, WebSocket, and environment documentation.
**Concrete guidance:**
- Use `AuthController`, `SessionController`, `ExecutionController`, `WebSocketConfig`, `ClientMessageType`, and `ServerMessageType` as the authoritative contract inputs.
- Document only the representative flows the backend actually supports: register, login, refresh, create/list/join/leave session, enqueue execution, connect to `/ws/sessions/{sessionId}`, submit operations, update presence, observe execution updates.
- Explicitly call out immutable session language, invite-code join flow, execution room visibility, and the strict Java `Main` contract.

### Pattern 5: Use the README as both onboarding guide and architecture note
**What:** One high-signal README replaces the current placeholder and serves both operator and reviewer needs.
**When to use:** Phase 5 documentation output.
**Concrete guidance:**
- Start with prerequisites, env setup, and exact commands.
- Follow with verification commands and expected outcomes.
- Then add REST/WebSocket reference tables and an architecture section with one Mermaid diagram.
- Close with key design decisions already locked by prior phases: server-authoritative OT, snapshot-plus-replay, Redis relay for 2-3 instances, Docker-only sandboxing.

### Anti-Patterns to Avoid
- **Do not create one "do everything" integration test class:** it makes failures hard to localize and contradicts the locked context decision.
- **Do not document a full-stack Compose flow without Docker daemon access:** the app container would boot but execution would silently fail later.
- **Do not keep `/actuator/health` permitted in security while leaving the endpoint absent:** Compose healthchecks and docs would drift from reality.
- **Do not write README examples that bypass the implemented controllers or websocket enums:** documentation drift is the exact Phase 5 problem being fixed.
</architecture_patterns>

<dont_hand_roll>
## Don't Hand-Roll

Problems that already have a safe project-local or standard solution:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Canonical integration harness | custom shell script that hardcodes many `--tests` invocations | Gradle `integrationTest` task with JUnit tags | One entrypoint, lives with the build, easy to document. |
| App health surface | custom controller just for Compose health | Spring Boot Actuator health endpoint | Standard Spring surface already implied by `SecurityConfig`. |
| Architecture diagram asset pipeline | checked-in generated PNG/SVG images | Mermaid in README | Simple, reviewable, and easy to update with docs. |
| API reference source | handwritten payloads from memory | controller DTOs and websocket enums already in repo | Prevents docs drift from live code. |
| Docker engine discovery in Compose | hardcoded host-specific socket path | env-substituted `DOCKER_SOCKET_PATH` plus `DOCKER_HOST` in container | Works across Docker Desktop, Linux, and alternative local runtimes. |
</dont_hand_roll>

<common_pitfalls>
## Common Pitfalls

### Pitfall 1: The canonical integration task accidentally misses important suites
**What goes wrong:** `integrationTest` exists, but it runs only `*IntegrationTest` classes and omits Testcontainers suites with different names like `FlywayMigrationTest` or `ExecutionServiceTest`.
**Why it happens:** the repo already mixes naming styles.
**How to avoid:** tag the exact curated classes rather than relying on filename suffixes.
**Warning signs:** `./gradlew integrationTest` passes too quickly or does not start all expected containers.

### Pitfall 2: Full-stack Compose boots, but execution fails from inside the app container
**What goes wrong:** auth/session APIs work, but Phase 4 execution cannot start Docker containers.
**Why it happens:** the app container cannot reach a Docker daemon.
**How to avoid:** mount a configurable Docker socket path and set `DOCKER_HOST` inside the app service.
**Warning signs:** execution requests fail with Docker client connection errors despite Docker working on the host.

### Pitfall 3: Compose healthchecks target `/actuator/health` before the dependency exists
**What goes wrong:** the app is healthy enough to serve traffic, but Compose marks it unhealthy or restart-loops.
**Why it happens:** security config allowed the path, but Boot Actuator was never added.
**How to avoid:** add the dependency and verify the endpoint before wiring Compose to it.
**Warning signs:** `404` or `401` from `/actuator/health`.

### Pitfall 4: README examples drift from the backend's real contract
**What goes wrong:** developers follow the README and hit wrong routes, payloads, or websocket event names.
**Why it happens:** docs are written from memory instead of reading controllers/enums.
**How to avoid:** derive docs directly from `AuthController`, `SessionController`, `ExecutionController`, `WebSocketConfig`, `ClientMessageType`, and `ServerMessageType`.
**Warning signs:** README references routes or events not present in source.

### Pitfall 5: The docs describe the happy path but hide the environment contract
**What goes wrong:** the stack starts inconsistently because users miss `APP_JWT_SECRET` or Docker socket setup.
**Why it happens:** docs assume defaults that are not actually optional.
**How to avoid:** make env vars explicit and show both default and override cases.
**Warning signs:** app startup fails closed on JWT config or execution works only on one developer machine.
</common_pitfalls>

## Validation Architecture

Phase 5 should validate correctness in four layers:

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers + Docker Compose config verification + README contract grep checks |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew integrationTest` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~180-360 seconds |

**Recommended verification shape:**
- Wave 1 proves one curated Gradle command runs the required Testcontainers-backed persistence, Redis, and execution suites.
- Wave 2 proves the packaged app exposes `/actuator/health` and still builds as a runnable container image.
- Wave 3 proves `docker compose config` resolves the full stack with the required env contract and Docker socket mount.
- Wave 4 proves the README documents the actual commands, endpoints, websocket messages, and design rationale already present in code.

**Manual-only checks to keep visible during planning:**
- Start the full stack with `docker compose up --build` and confirm the app becomes healthy, then register/login/create/join a session and enqueue a run.
- Override `DOCKER_SOCKET_PATH` for a non-default local runtime and confirm the app container can still launch execution containers.
- Follow the README from a clean shell and confirm the documented commands and example routes work without undocumented setup.

<open_questions>
## Open Questions

1. **Whether to add a dedicated health endpoint test or extend existing security/controller coverage**
   - What we know: `/actuator/health` is already permitted in `SecurityConfig` and is needed for Compose healthchecks.
   - What's unclear: whether the cleanest proof is a small dedicated test or extending an existing security-focused slice test.
   - Recommendation: add a small focused health-endpoint test so the Compose prerequisite stays explicit and grep-verifiable.

2. **How much of the Docker socket contract should be surfaced in Compose defaults**
   - What we know: default Docker Desktop/Linux can use `/var/run/docker.sock`, but alternative runtimes may differ.
   - What's unclear: whether to keep the Compose default simple or add explicit env indirection from day one.
   - Recommendation: use env indirection immediately via `DOCKER_SOCKET_PATH` so the README can stay honest for non-default runtimes.

3. **Whether the README should split contract sections into separate docs**
   - What we know: the current requirement only asks for a README with setup, API docs, architecture notes, and design decisions.
   - What's unclear: whether a very large README should be split.
   - Recommendation: keep everything in README for Phase 5; split later only if future milestones grow the surface area materially.
</open_questions>
