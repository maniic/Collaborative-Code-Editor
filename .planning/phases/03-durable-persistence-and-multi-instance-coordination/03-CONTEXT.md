# Phase 3: Durable Persistence and Multi-Instance Coordination - Context

**Gathered:** 2026-03-29
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 3 makes the Phase 2 collaboration runtime durable and safe across `2-3` backend instances. It adds PostgreSQL-backed operation and snapshot persistence, Redis-backed active-session coordination and pub/sub relay, and restart recovery from snapshot plus replay. It should preserve the existing collaboration feature contract rather than introducing new user-facing capabilities. Execution itself is still a later phase, but the persistence foundation for execution history belongs here.

</domain>

<decisions>
## Implementation Decisions

### Durable Write Model
- **D-01:** A canonical collaboration operation must be durably written to PostgreSQL before the server sends `operation_ack` or broadcasts it to participants.
- **D-02:** PostgreSQL is the durable source of truth for canonical operation history, document snapshots, session metadata, and execution-history records.
- **D-03:** The full canonical operation log is retained even after snapshots are created; snapshots accelerate recovery but do not replace replayable history.
- **D-04:** Phase 3 should introduce the execution-history persistence schema now so Phase 4 can append execution records onto an already-stable data model.

### Snapshot and Recovery Flow
- **D-05:** Create document snapshots at least every 50 accepted canonical operations, with revisions `50`, `100`, `150`, and so on as the minimum guaranteed cadence.
- **D-06:** Recover document state by loading the latest stored snapshot and replaying only the canonical operations after that snapshot revision.
- **D-07:** Rebuild an active collaboration runtime lazily on first reconnect or first operation for that session after a cold start, instead of preloading all rooms during application boot.
- **D-08:** Live presence and socket membership remain ephemeral runtime state; after a restart, clients reconnect and restore presence through the existing Phase 2 bootstrap and presence flows.

### Redis Coordination Role
- **D-09:** Redis coordinates active session state plus atomic per-session revision and apply sequencing so multiple backend instances cannot assign competing canonical revisions.
- **D-10:** Redis is coordination and cache infrastructure, not the long-term record of document text or operation history; if Redis state is missing or suspect, the server must rebuild from PostgreSQL snapshot plus replay.
- **D-11:** Instance-local WebSocket socket tracking should stay separate from the shared canonical collaboration state so any backend instance can recover and serve the same session.

### Cross-Instance Relay Semantics
- **D-12:** Publish only canonical collaboration events across Redis pub/sub: accepted operations, `participant_joined`, `participant_left`, and `presence_updated`.
- **D-13:** Each backend instance should fan out collaboration events to its local sockets from the same canonical relay path, rather than maintaining a special direct-broadcast path only on the originating instance.
- **D-14:** If an instance detects a revision gap, missed relay event, or otherwise inconsistent local runtime state, it must rebuild or resync from durable state before continuing instead of risking divergence.
- **D-15:** The external Phase 2 WebSocket contract should remain stable; durability and multi-instance coordination are internal changes unless an additive compatibility-safe field is strictly necessary.

### the agent's Discretion
- Exact PostgreSQL table names, indexes, and column details for operation-log, snapshot, and execution-history storage, as long as they support replay, recovery, and future execution auditing.
- Exact Redis key naming, TTL choices, and pub/sub channel naming.
- Exact mechanism for per-session apply serialization in Redis, whether implemented with locks, scripts, or another atomic strategy.
- Exact runtime-eviction policy and any extra opportunistic snapshots beyond the guaranteed every-50-operation cadence.

</decisions>

<specifics>
## Specific Ideas

- Keep the Phase 2 client-facing collaboration protocol stable; this phase should mostly improve durability and coordination without changing how clients edit.
- Treat snapshot plus replay as the single recovery path for both service restarts and cross-instance relay gap healing.
- Use lazy runtime rebuild instead of startup-wide warm loading so the app does not pay boot-time cost for inactive sessions.
- The user delegated the decision points for this phase to the agent, so recommended defaults were selected throughout.

</specifics>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` — Phase 3 goal, dependency on Phase 2, and success criteria for durability, snapshots, Redis coordination, and cross-instance relay.
- `.planning/REQUIREMENTS.md` — `DATA-01` through `DATA-04`, plus the persistence and scaling boundaries for this phase.
- `.planning/PROJECT.md` — persisted operation-log requirement, every-50-op snapshots, Redis pub/sub and active-session caching expectations, single-document room model, and package-structure constraints.

### Prior phase decisions
- `.planning/phases/01-secure-access-and-session-lifecycle/01-CONTEXT.md` — canonical `sessionId`, invite-code semantics, participant lifecycle rules, and room ownership behavior that persistence must continue honoring.
- `.planning/phases/02-real-time-ot-collaboration/02-CONTEXT.md` — locked WebSocket protocol, full-document bootstrap, explicit ack and canonical broadcast semantics, resync behavior, and presence identity rules that Phase 3 should preserve.

### Existing implementation seams
- `src/main/resources/db/migration/V1__phase1_baseline.sql` — existing persisted schema for users, sessions, and participants that Phase 3 migrations must extend rather than replace.
- `src/main/java/com/collabeditor/ot/service/CollaborationSessionRuntime.java` — current in-memory canonical runtime with document, revision, and operation history that now needs durable backing.
- `src/main/java/com/collabeditor/websocket/service/CollaborationSessionRegistry.java` — current instance-local runtime and socket registry that should be split into local socket tracking plus shared coordinated session state.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` — current single-instance collaboration flow whose external contract should survive the Phase 3 refactor.

### Project instructions
- `AGENTS.md` — required GSD workflow, stack constraints, and expected package structure for new `redis` and `snapshot` work.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `src/main/java/com/collabeditor/ot/service/CollaborationSessionRuntime.java` — already owns canonical OT application, revision advancement, and stale-op transforms; it is the natural core to wrap with durable loading and persistence.
- `src/main/java/com/collabeditor/websocket/service/CollaborationSessionRegistry.java` — already separates room socket tracking from the WebSocket handler; Phase 3 can preserve the local-socket part while moving canonical session state out of instance-local memory.
- `src/main/java/com/collabeditor/session/persistence/CodingSessionRepository.java` and `src/main/java/com/collabeditor/session/persistence/SessionParticipantRepository.java` — existing repositories already support canonical session lookup and active participant membership checks for reconnect and bootstrap behavior.
- `src/main/resources/db/migration/V1__phase1_baseline.sql` — the project already uses Flyway migrations and validated schema-first evolution.

### Established Patterns
- The collaboration protocol is already explicit and server-authoritative: full-document bootstrap, canonical revision ack, canonical operation broadcast, and explicit resync on unrecoverable desync.
- Canonical room identity is `sessionId`; invite codes remain the human-facing join mechanism only.
- The current runtime is explicitly documented as in-memory only, which makes the durability seam clear rather than hidden.
- Package structure expectations already reserve `redis` and `snapshot` packages, but those packages do not yet exist in the codebase.

### Integration Points
- Add new PostgreSQL migrations, entities, and repositories for operation logs, document snapshots, and execution-history records.
- Introduce a shared recovery/bootstrap service that can materialize the canonical runtime from snapshot plus replay for the WebSocket layer.
- Add a Redis coordination layer between WebSocket message handling and canonical apply so multi-instance revision assignment and relay stay serialized per session.
- Keep local socket fan-out inside the WebSocket module, but source the canonical event stream from Redis relay rather than only local memory.

</code_context>

<deferred>
## Deferred Ideas

- User-facing session-history replay remains future work under `HIST-01`; Phase 3 only preserves the durable data needed to support that later capability.
- Persisting live cursor presence across process restarts is out of scope; presence is rebuilt from reconnects rather than stored as durable history.

</deferred>

---

*Phase: 03-durable-persistence-and-multi-instance-coordination*
*Context gathered: 2026-03-29*
