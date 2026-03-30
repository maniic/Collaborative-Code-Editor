# Phase 5: Integration Hardening and Developer Docs - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 5 proves that the backend can be reproduced, validated, and understood from a clean local environment. It packages the existing authentication, session, OT, persistence, Redis, and Docker-execution capabilities into a canonical verification path, a reproducible local stack, and onboarding documentation. It does not add new end-user features or change the Phase 1-4 external contracts.

</domain>

<decisions>
## Implementation Decisions

### Integration Verification Shape
- **D-01:** Phase 5 should expose one canonical automated verification path for developers, but that path should be composed of a small curated set of focused Testcontainers-backed integration tests rather than one brittle mega-test class.
- **D-02:** The canonical verification run must exercise the real infrastructure boundaries that matter for the shipped backend: Flyway/PostgreSQL bootstrapping, durable collaboration persistence and recovery, Redis-backed cross-instance coordination/relay behavior, and Docker-backed execution behavior.
- **D-03:** Existing focused integration tests are reusable seams for this work and should be consolidated behind a clear Gradle invocation or dedicated suite entrypoint so a developer does not need to know individual test class names to prove the stack.

### Local Reproduction Workflow
- **D-04:** `docker-compose` should be the canonical reproducible local-start path and must boot `app`, `postgres`, and `redis` together because that is the Phase 5 success criterion developers need to be able to follow verbatim.
- **D-05:** The README should also document an optional faster inner-loop workflow where developers start only infrastructure with Compose and run the Spring app locally with Gradle, but that is secondary to the full-stack Compose path.
- **D-06:** The documented local workflow should surface the real required environment contract already in the app: PostgreSQL URL/credentials, Redis host/port, and a non-empty JWT secret, plus the need for local Docker availability for execution and Testcontainers.

### README Contract
- **D-07:** The README should optimize for first-run success: prerequisites, environment variables, start commands, verification commands, and expected local behavior come before narrative material.
- **D-08:** The README should document representative backend contracts rather than attempt an exhaustive generated API manual. Include the core REST flows (`/api/auth`, `/api/sessions`, `/api/sessions/{sessionId}/executions`) and the collaboration WebSocket endpoint plus message vocabulary that frontend work depends on.
- **D-09:** The README should make the current execution contract explicit: only `PYTHON` and single-file package-less `JAVA` `Main` are supported, execution is room-visible, and sandbox constraints are fixed rather than user-configurable.

### Architecture and Decision Rationale
- **D-10:** Documentation should include one high-signal system architecture diagram that shows how the Spring Boot app interacts with PostgreSQL, Redis, WebSocket clients, and Docker execution, instead of multiple deep sequence diagrams.
- **D-11:** The architecture section should explicitly explain the milestone’s key design decisions already locked in earlier phases: server-authoritative OT, snapshot-plus-replay recovery, Redis coordination/pub-sub for 2-3 instances, and Docker-only sandboxing.

### the agent's Discretion
- Exact Gradle task and source-set structure used to expose the canonical integration run, as long as one obvious developer entrypoint exists.
- Exact `docker-compose.yml` service names, healthcheck strategy, and image/build details.
- Exact README section ordering, example payload selection, and diagram format, as long as the documented contract remains practical and accurate.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` — Phase 5 goal, dependency on Phase 4, and success criteria for integration tests, `docker-compose`, and onboarding docs.
- `.planning/REQUIREMENTS.md` — `QUAL-03`, `DOCS-01`, and `DOCS-02`, which define the verification and documentation contract for this phase.
- `.planning/PROJECT.md` — stack constraints, Docker-only execution requirement, 2-3 instance scale target, and the portfolio-quality architecture goals the docs should communicate.

### Prior phase decisions that docs and tests must preserve
- `.planning/phases/01-secure-access-and-session-lifecycle/01-CONTEXT.md` — bearer-plus-refresh-cookie auth model, invite-code semantics, immutable room language, and session lifecycle rules that README examples must reflect.
- `.planning/phases/02-real-time-ot-collaboration/02-CONTEXT.md` — WebSocket endpoint expectations, typed message-envelope model, document bootstrap, ack/resync behavior, and presence semantics that docs must describe accurately.
- `.planning/phases/03-durable-persistence-and-multi-instance-coordination/03-CONTEXT.md` — PostgreSQL durability, snapshot cadence, Redis coordination, and canonical relay-path decisions that Phase 5 verification must prove rather than re-interpret.
- `.planning/phases/04-sandboxed-code-execution/04-CONTEXT.md` — REST execution enqueue path, room-visible execution events, Java/Python runtime contract, queueing, cooldown, and fixed sandbox limits that docs and integration tests must preserve.

### Existing implementation seams
- `build.gradle.kts` — current dependency graph, Testcontainers setup, and existing test-task behavior that Phase 5 should extend into a clearer verification entrypoint.
- `src/main/resources/application.yml` — canonical runtime environment variables and fixed operational defaults for database, Redis, auth, collaboration, and execution.
- `src/test/resources/application-test.yml` — test-profile assumptions and test-time execution defaults used by existing integration coverage.
- `src/main/java/com/collabeditor/auth/api/AuthController.java` — authoritative REST auth contract for register, login, refresh, and refresh-cookie behavior.
- `src/main/java/com/collabeditor/session/api/SessionController.java` — authoritative session create/list/join/leave REST contract.
- `src/main/java/com/collabeditor/execution/api/ExecutionController.java` — authoritative execution enqueue REST contract.
- `src/main/java/com/collabeditor/websocket/config/WebSocketConfig.java` — canonical WebSocket endpoint path and handshake model.
- `src/main/java/com/collabeditor/websocket/protocol/ClientMessageType.java` — client-to-server collaboration message vocabulary the README should describe.
- `src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java` — server-to-client collaboration and execution event vocabulary the README should describe.
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java` — existing PostgreSQL/Flyway integration proof seam.
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java` — existing persistence, snapshot, and recovery integration proof seam.
- `src/test/java/com/collabeditor/websocket/DistributedCollaborationWebSocketHandlerTest.java` — existing multi-component PostgreSQL-plus-Redis collaboration relay proof seam.
- `src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java` — existing real Docker execution proof seam.
- `src/test/java/com/collabeditor/execution/ExecutionServiceTest.java` — existing execution admission and persistence verification seam that can inform a curated Phase 5 proof suite.

### Project instructions
- `AGENTS.md` — required GSD workflow, stack constraints, and package-structure expectations that still apply while adding docs and verification assets.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java`: already proves Flyway and schema validation against real PostgreSQL.
- `src/test/java/com/collabeditor/ot/CollaborationPersistenceIntegrationTest.java`: already proves snapshot cadence and rebuild from snapshot-plus-replay.
- `src/test/java/com/collabeditor/websocket/DistributedCollaborationWebSocketHandlerTest.java`: already exercises PostgreSQL durability plus Redis relay behavior in a multi-node style test.
- `src/test/java/com/collabeditor/execution/ExecutionIntegrationTest.java`: already proves real Docker-backed Python and Java execution plus key sandbox restrictions.
- `src/test/java/com/collabeditor/execution/ExecutionServiceTest.java`: already verifies canonical source capture and durable execution-history lifecycle behavior.
- `src/main/resources/application.yml` and `src/test/resources/application-test.yml`: already define the environment contract that docs can expose directly instead of re-deriving.
- Controller classes and WebSocket protocol enums already define a clean public contract that the README can document directly.

### Established Patterns
- The codebase prefers explicit typed contracts and fail-closed configuration over implicit magic; Phase 5 docs should mirror that style.
- Integration coverage already exists as focused JUnit/Testcontainers classes instead of one giant scenario, so the canonical verification path should package that pattern rather than replace it.
- Runtime configuration is environment-driven, with fixed non-negotiable execution limits enforced in code; docs should state those fixed values clearly.
- The backend already separates durable PostgreSQL state, Redis coordination/relay behavior, and Docker execution responsibilities; the architecture section should explain that split plainly.

### Integration Points
- `build.gradle.kts` is the natural place to add a dedicated integration verification task or suite entrypoint.
- Root-level `docker-compose.yml` should provide the reproducible local stack expected by the roadmap.
- Root-level `README.md` should become the canonical onboarding and architecture document.
- Existing integration tests under `src/test/java/com/collabeditor/...` are the main seams to regroup, extend, or selectively harden for the Phase 5 proof path.

</code_context>

<specifics>
## Specific Ideas

- User delegated the Phase 5 decision points to the agent, so recommended defaults were selected without further questioning.
- Prefer one obvious developer story: set env, run Compose, verify with one command, then consult a README that explains the actual shipped contracts.
- Keep the documentation portfolio-friendly but operationally honest: describe the real endpoint paths, websocket message types, and fixed execution constraints already enforced in code.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 05-integration-hardening-and-developer-docs*
*Context gathered: 2026-03-29*
