# Phase 4: Sandboxed Code Execution - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 4 delivers safe execution of the current shared room document for `JAVA` and `PYTHON` inside constrained Docker containers. It covers the execution request path, sandbox isolation limits, bounded queueing, per-user cooldown enforcement, and delivery of execution results back to active session participants. It does not add new languages, project/file management, richer IDE tooling, or the Phase 5 integration-docs work.

</domain>

<decisions>
## Implementation Decisions

### Execution Request and Result Flow
- **D-01:** Execution should be triggered through an authenticated session-scoped REST endpoint that enqueues a run against the current canonical document snapshot and returns an execution identifier plus initial status.
- **D-02:** Execution lifecycle updates and results should be delivered to active room participants through additive typed WebSocket events on the existing session socket, rather than polling-only APIs or a separate realtime channel.
- **D-03:** Phase 4 should send structured final execution results (`status`, `stdout`, `stderr`, `exitCode`, timing) rather than line-by-line live stream chunks; short lifecycle events like queued/running/finished are sufficient for realtime feedback.
- **D-04:** The executed source of truth is the current canonical persisted room document and revision at enqueue time, and that revision should be recorded in execution history.

### Language Runtime Contract
- **D-05:** Python execution should run the document as a plain script with no wrapper layer.
- **D-06:** Java execution should require a single-file, package-less `Main` entrypoint with `public static void main(String[] args)` so the sandbox can compile `Main.java` and run `java Main` predictably.
- **D-07:** Phase 4 should prefer explicit, predictable source contracts over snippet rewriting or automatic Java wrapper generation.

### Queueing and Failure Semantics
- **D-08:** Accepted executions should move through explicit durable statuses such as `QUEUED`, `RUNNING`, and terminal states like `COMPLETED`, `FAILED`, or `TIMED_OUT`.
- **D-09:** The per-user five-second cooldown should fail fast with an explicit rejection response that tells the caller why the run was refused, rather than silently delaying or coalescing requests.
- **D-10:** The execution worker pool should use a bounded FIFO queue; once the queue is full, new requests should be rejected immediately with a clear capacity error instead of waiting unboundedly.
- **D-11:** Execution completion and rejection semantics should be visible to all active room participants, matching the collaborative nature of the product rather than treating runs as requester-private.

### Sandbox Isolation Baseline
- **D-12:** Every execution must run in Docker with the Phase 4 limits treated as non-negotiable baseline guards: `256MB` memory, `0.5` CPU, `10s` timeout, no network, read-only filesystem, and non-root execution.
- **D-13:** Sandbox setup should optimize for deterministic, easily-auditable isolation over clever runtime flexibility.

### the agent's Discretion
- Exact REST path naming, DTO field names, and WebSocket event names, as long as the contract remains explicit and typed.
- Exact execution status vocabulary beyond the locked queued/running/terminal lifecycle model.
- Exact bounded-queue size, worker-pool size, and Docker image tags, as long as they preserve predictable local development ergonomics.
- Exact temporary writable mount strategy needed to support compilation/runtime artifacts while keeping the broader container filesystem read-only.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` — Phase 4 goal, dependency on Phase 3, and success criteria for Docker execution, language support, isolation, and queueing.
- `.planning/REQUIREMENTS.md` — `EXEC-01`, `EXEC-02`, `EXEC-03`, and `EXEC-04`, which define the execution, sandbox, and rate-limit contract.
- `.planning/PROJECT.md` — Docker-only sandboxing, supported-language scope, package-structure expectations, and the broader collaborative-code-execution product intent.

### Prior phase decisions
- `.planning/phases/01-secure-access-and-session-lifecycle/01-CONTEXT.md` — immutable session language, session membership rules, and authenticated REST patterns that execution must honor.
- `.planning/phases/02-real-time-ot-collaboration/02-CONTEXT.md` — explicit typed protocol design, session-scoped WebSocket contract, and active-participant semantics that execution events should extend rather than replace.
- `.planning/phases/03-durable-persistence-and-multi-instance-coordination/03-CONTEXT.md` — durable execution-history groundwork, canonical room revision model, and Redis-backed multi-instance coordination expectations.

### Existing implementation seams
- `src/main/resources/db/migration/V2__phase3_collaboration_persistence.sql` — existing `execution_history` schema foundation that Phase 4 should build on instead of redesigning.
- `src/main/java/com/collabeditor/execution/persistence/entity/ExecutionHistoryEntity.java` — durable execution record shape for source revision, status, stdout, stderr, exit code, and timing.
- `src/main/java/com/collabeditor/execution/persistence/ExecutionHistoryRepository.java` — existing repository seam for storing and listing execution records.
- `src/main/java/com/collabeditor/session/service/SessionService.java` — current authoritative language validation and participant/session semantics execution should reuse.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` — existing authenticated session socket contract that Phase 4 should extend with additive execution events.
- `src/main/java/com/collabeditor/websocket/protocol/ClientMessageType.java` — current client WebSocket vocabulary, useful when deciding whether execution control belongs on the socket or stays REST-triggered.
- `src/main/java/com/collabeditor/websocket/protocol/ServerMessageType.java` — current server event vocabulary that Phase 4 may extend with execution lifecycle/result events.

### Project instructions
- `AGENTS.md` — required GSD workflow, stack constraints, and package-structure expectations for new `execution` work.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `src/main/java/com/collabeditor/execution/persistence/entity/ExecutionHistoryEntity.java` — already captures the durable metadata and final output fields Phase 4 needs.
- `src/main/java/com/collabeditor/execution/persistence/ExecutionHistoryRepository.java` — ready-made persistence seam for execution history reads and writes.
- `src/main/java/com/collabeditor/session/service/SessionService.java` — already enforces `JAVA`/`PYTHON` as the only valid session languages and owns authoritative membership semantics.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` plus the `websocket/protocol` package — established typed-envelope and session-socket patterns suitable for additive execution events.
- `src/main/java/com/collabeditor/ot/service/CollaborationPersistenceService.java` and `src/main/java/com/collabeditor/snapshot/service/SnapshotRecoveryService.java` — existing seams for reading the canonical persisted document state and revision before a run.

### Established Patterns
- The backend favors explicit DTOs, typed protocols, and fail-closed auth rather than implicit or magical behavior.
- Canonical session identity is `sessionId`; invite codes remain human-facing join tokens only.
- Durable state lives in PostgreSQL while Redis coordinates cross-instance runtime behavior; execution should follow that same split.
- Phase 3 already established that user-visible collaborative effects should flow through canonical paths rather than instance-local shortcuts.
- No Docker integration or execution-service layer exists yet, so Phase 4 can define these seams cleanly without fighting legacy runtime code.

### Integration Points
- Add a new `execution` service layer that validates requester membership, snapshots the current canonical source, enforces cooldown/queue policy, and persists lifecycle transitions.
- Extend the authenticated session REST surface with an execution enqueue endpoint.
- Extend the collaboration WebSocket event model with additive execution lifecycle/result events for active participants.
- Introduce Docker client and sandbox orchestration code that maps room language to the appropriate runtime image and compile/run command.
- Reuse the existing execution-history table/repository for durable auditability and later Phase 5 integration tests.

</code_context>

<specifics>
## Specific Ideas

- User delegated the Phase 4 decision points to the agent, so recommended defaults were selected throughout.
- Prefer predictable execution contracts over convenience magic: REST enqueue, typed lifecycle events, explicit rejection reasons, and a strict Java `Main` contract.
- Treat collaborative execution results as room-visible state, not requester-private output, because the product is a shared coding session rather than a personal scratchpad.

</specifics>

<deferred>
## Deferred Ideas

- Live stdout/stderr chunk streaming can be a later enhancement; Phase 4 only locks final structured output plus lightweight lifecycle events.
- Supporting Java snippet wrapping, inferred entrypoint class names, or multi-file project execution is future work outside this single-document milestone.
- Additional languages beyond `JAVA` and `PYTHON` remain deferred to future milestones.

</deferred>

---

*Phase: 04-sandboxed-code-execution*
*Context gathered: 2026-03-29*
