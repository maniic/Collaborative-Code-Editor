# Phase 3: Durable Persistence and Multi-Instance Coordination - Research

**Researched:** 2026-03-29
**Domain:** Durable collaboration state, snapshot-plus-replay recovery, Redis session coordination, and canonical cross-instance relay
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Accepted canonical collaboration operations must be durably written to PostgreSQL before `operation_ack` or any participant broadcast.
- PostgreSQL is the durable source of truth for canonical operation history, document snapshots, session metadata, and execution-history records.
- The full canonical operation log stays retained after snapshots are created; snapshots accelerate recovery but do not replace replayable history.
- Phase 3 introduces the execution-history persistence schema even though execution behavior itself is a later phase.
- Document snapshots are created at least every 50 accepted canonical operations.
- Recovery loads the latest stored snapshot and replays only later canonical operations.
- Session runtimes rebuild lazily on first reconnect or first operation after a cold start rather than being preloaded for all rooms at boot.
- Presence and socket membership stay ephemeral runtime state; reconnect restores them through the existing Phase 2 flows.
- Redis handles active-session coordination plus atomic per-session revision and apply sequencing.
- Redis is coordination/cache infrastructure, not the long-term record of document text or operation history.
- Instance-local socket tracking stays separate from shared canonical collaboration state.
- Only canonical collaboration events relay through Redis pub/sub: accepted operations, `participant_joined`, `participant_left`, and `presence_updated`.
- Every backend instance should fan out to its local sockets from the same canonical relay path.
- Revision gaps or relay inconsistencies must trigger rebuild or resync instead of allowing document drift.
- The external Phase 2 WebSocket contract should remain stable unless an additive compatibility-safe field is absolutely necessary.

### the agent's Discretion
- Exact PostgreSQL table names, indexes, and column layout for operation-log, snapshot, and execution-history storage.
- Exact Redis key naming, TTL values, and channel naming.
- Exact per-session serialization mechanism in Redis, whether lock-based, script-based, or another atomic strategy.
- Exact runtime-eviction policy and any additional snapshots beyond the guaranteed every-50-operation cadence.

### Deferred Ideas (OUT OF SCOPE)
- User-facing history replay APIs or UI.
- Durable restoration of cursor presence across restarts.
</user_constraints>

<research_summary>
## Summary

Phase 3 should be implemented as a persistence-and-coordination refactor around the already-working Phase 2 OT and WebSocket contract, not as a new collaboration protocol. The current repo already has the right seams for this: `CollaborationSessionRuntime` owns canonical OT behavior, `CollaborationSessionRegistry` owns local socket tracking, and the WebSocket handler already expresses the full room lifecycle. The missing pieces are durable state, lazy room rehydration, and a shared coordination path so multiple app instances cannot invent independent canonical revisions.

The safest build order is:

1. Extend PostgreSQL with an append-only operation log, periodic snapshot storage, and execution-history tables.
2. Introduce a persistence service that can append accepted canonical operations, create snapshots on the exact 50-operation cadence, and rebuild a room runtime from latest snapshot plus replay.
3. Add Redis as the coordination layer for per-session serialization, revision mirroring, active-room bookkeeping, and canonical pub/sub relay.
4. Rewire the WebSocket path so local sockets consume canonical events from the Redis relay and rebuild or resync when revision gaps are detected.

This ordering preserves the Phase 2 client contract while making the server resilient to restarts and safe across `2-3` instances.

**Primary recommendation:** plan Phase 3 in four waves: persistence schema first, snapshot/replay recovery second, Redis coordination and relay third, then distributed WebSocket integration plus final verification.
</research_summary>

<standard_stack>
## Standard Stack

The established libraries and tools for this phase:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java | 17 toolchain (repo standard) | Runtime for persistence, Redis coordination, and tests | `build.gradle.kts` already targets Java 17 and Phase 2 explicitly avoided preview-only features. |
| Spring Boot | 3.3.4 (repo standard) | App framework and dependency management | Already pinned and validated in the repo. |
| Spring Data JPA | managed by Boot | PostgreSQL entities and repositories | Existing auth/session code already uses JPA entity and repository patterns. |
| Flyway + PostgreSQL | managed by Boot / runtime driver | Durable schema evolution and canonical storage | The repo already uses SQL migrations plus Hibernate `validate`. |
| Spring Data Redis | managed by Boot | RedisTemplate, pub/sub listener container, and Lettuce client | Fits the project decision that Redis owns coordination and pub/sub at the 2-3 instance scale target. |
| Spring WebSocket | managed by Boot | Existing collaboration transport | Phase 3 preserves the current handler contract instead of replacing the transport. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit 5 + AssertJ | managed by Boot | Service, repository, and relay regression tests | Use for persistence, recovery, and distributed-collaboration correctness checks. |
| Spring Boot Test | managed by Boot | Container-backed repository/service tests and handler tests | Use when verifying migration, recovery, and handler integration behavior. |
| Testcontainers PostgreSQL | 1.21.4 (repo standard) | Real PostgreSQL verification for migrations and durable replay | Already present in the repo and used by `FlywayMigrationTest`. |
| Testcontainers GenericContainer | 1.21.4 (already transitively available) | Real Redis verification for coordination and relay behavior | Sufficient for Redis without adding a second Redis-specific testing stack. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| PostgreSQL as the only durable source of truth | Redis as the primary canonical state store | Faster in the short term, but contradicts the locked Phase 3 durability decision and makes recovery less trustworthy. |
| Snapshot plus replay | Replay the full operation log every time | Simpler conceptually, but room recovery time grows without bound. |
| Lazy room rebuild | Warm all rooms at startup | Predictable connect time, but creates unnecessary boot cost for inactive rooms. |
| One canonical Redis relay path | Local-origin broadcast plus a second remote relay path | Slightly less relay latency, but creates split-brain fan-out logic and harder gap handling. |

**Installation:**
```bash
# Add the Redis starter to the existing build
# implementation("org.springframework.boot:spring-boot-starter-data-redis")
```
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### Recommended Project Structure
```text
src/main/java/com/collabeditor/
├── ot/
│   ├── model/                  # Existing TextOperation, DocumentSnapshot, AppliedOperation
│   ├── persistence/            # Operation-log entities and repositories
│   └── service/                # Runtime hydration, durable append/replay services
├── snapshot/
│   ├── persistence/            # Snapshot entities and repositories
│   └── service/                # Snapshot cadence and recovery helpers
├── execution/
│   └── persistence/            # Execution history schema foundation for Phase 4
├── redis/
│   ├── config/                 # RedisTemplate, listener container, key/channel properties
│   ├── model/                  # Canonical collaboration relay event DTOs
│   └── service/                # Session coordination and pub/sub relay services
├── websocket/
│   ├── handler/                # Existing raw socket contract
│   └── service/                # Local sockets + distributed fan-out bridge
└── session/                    # Existing canonical room and membership source of truth
```

### Pattern 1: Append-only canonical operation log plus periodic full-document snapshots
**What:** Persist every accepted canonical operation in revision order and write a full-document snapshot whenever the canonical revision reaches a multiple of 50.
**When to use:** Every successful apply path after Phase 3.
**Concrete guidance:**
- Keep the operation log append-only and unique on `(session_id, revision)`.
- Persist the canonical transformed operation, not the original client payload.
- Store the full document text in snapshot rows so replay cost stays bounded.
- Add execution-history tables now, but keep execution writes deferred to Phase 4.

### Pattern 2: Lazy runtime hydration from latest snapshot plus replay
**What:** Build a `CollaborationSessionRuntime` from durable state only when an instance actually needs the room.
**When to use:** On first connect, first operation, or local gap recovery for a session not already cached on that instance.
**Concrete guidance:**
- Load the latest snapshot at or below the latest revision.
- Replay later operations in ascending revision order through the existing runtime.
- Keep room caches instance-local and evictable; PostgreSQL remains the rebuild path.
- Presence is restored by reconnect traffic, not by durable replay.

### Pattern 3: Redis-coordinated single writer per session
**What:** Use Redis to serialize the accept path for one session and mirror the latest revision for low-latency coordination between instances.
**When to use:** Around every accepted collaboration operation.
**Concrete guidance:**
- Use a deterministic per-session lock key and bounded TTL.
- Initialize the Redis revision mirror from PostgreSQL on first active use.
- Persist first, then move the Redis mirror forward, then publish the canonical event.
- If Redis and PostgreSQL disagree, rebuild from PostgreSQL instead of trusting stale cache state.

### Pattern 4: Canonical pub/sub relay feeding instance-local socket fan-out
**What:** Publish accepted operations and presence events once to Redis pub/sub, then have every instance translate the same canonical event stream into local socket sends.
**When to use:** For `operation_applied`, `participant_joined`, `participant_left`, and `presence_updated`.
**Concrete guidance:**
- The originating instance should consume its own published event instead of bypassing the relay.
- Keep the external WebSocket payloads unchanged unless a strictly additive field is needed.
- Detect revision gaps while consuming relay events; on a gap, evict local runtime state and rebuild or resync before accepting more edits.

### Anti-Patterns to Avoid
- **Acknowledging before durable append:** this violates the locked guarantee that acknowledged edits survive restart.
- **Treating Redis as the durable system of record:** Redis is coordination and relay infrastructure only.
- **Replaying every session at boot:** Phase 3 explicitly chose lazy hydration.
- **Writing live cursor presence into durable history:** presence is transient collaboration state, not replayable canonical state.
- **Keeping separate local and remote broadcast paths:** it becomes too easy for one instance to skip a transform, revision check, or rebuild step.
</architecture_patterns>

<dont_hand_roll>
## Don't Hand-Roll

Problems that already have a safe project-local solution:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Schema evolution | Ad hoc SQL boot scripts or Hibernate auto-DDL | Flyway SQL migrations under `src/main/resources/db/migration/` | Phase 1 already locked migration-first schema evolution. |
| Room membership for reconnect and presence relay | A second participant directory in Redis | Existing `SessionParticipantRepository` and `CodingSessionRepository` | Membership rules already live in the session module. |
| Durable room reconstruction | Custom string patching outside the OT runtime | `CollaborationSessionRuntime` replay through canonical operations | Reuse the server-authoritative OT core instead of inventing a second document mutation path. |
| JSON relay envelopes | Map-based Redis payloads with ad hoc parsing | Typed relay DTOs plus Jackson serialization | Keeps Redis relay semantics as explicit as the WebSocket contract. |

**Key insight:** Phase 3 should extend the Phase 2 canonical runtime and handler flow, not create a second shadow collaboration engine just for durability.
</dont_hand_roll>

<common_pitfalls>
## Common Pitfalls

### Pitfall 1: Redis revision counters drift from PostgreSQL revisions
**What goes wrong:** one instance increments Redis first or loses a durable write, and later instances believe a revision exists that PostgreSQL cannot replay.
**Why it happens:** the accept path updates cache and durable state in the wrong order.
**How to avoid:** under the per-session coordination guard, persist the canonical operation first, then move the Redis mirror to that durable revision, then publish the canonical event.
**Warning signs:** reconnects report higher revisions than the operation table contains, or rebuild code sees missing rows for a claimed revision.

### Pitfall 2: Snapshot cadence silently skips revision 50 boundaries
**What goes wrong:** snapshot creation runs on history size, zero-based indexes, or stale cached counters and misses the required `50`, `100`, `150` revisions.
**Why it happens:** developers gate snapshots on “every 50 operations since startup” instead of the canonical revision number.
**How to avoid:** compute snapshot eligibility from the accepted canonical revision and assert exact snapshot rows in tests.
**Warning signs:** a 50-operation room has no snapshot row, or snapshot revisions do not match multiples of 50.

### Pitfall 3: Pub/sub delivery hides local revision gaps until users diverge
**What goes wrong:** an instance misses one relay event, keeps its stale runtime, and then applies the next operation to the wrong document.
**Why it happens:** relay consumers assume fire-and-forget pub/sub is gap-free.
**How to avoid:** check every consumed revision against the local runtime’s expected next revision and trigger rebuild or `resync_required` on any gap.
**Warning signs:** one instance receives revision `12` while its local runtime is still at `10`, or rebuild code is never exercised in tests.

### Pitfall 4: Restart recovery recreates document text but loses websocket semantics
**What goes wrong:** a rebuilt runtime has the right document but no stable revision mirror, causing duplicate revision assignment or stale broadcast payloads.
**Why it happens:** runtime hydration is treated as “document only” instead of canonical state restoration.
**How to avoid:** restore document, revision, and canonical history needed for future transforms, then sync Redis revision state before the room accepts new operations.
**Warning signs:** post-restart connects show the correct document but new submissions reuse old revision numbers or skip transforms.
</common_pitfalls>

## Validation Architecture

Phase 3 should validate correctness in four layers:

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers PostgreSQL + Testcontainers GenericContainer Redis |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*FlywayMigrationTest" --tests "*CollaborationPersistenceRepositoryTest" --tests "*CollaborationPersistenceServiceTest" --tests "*SessionCoordinationServiceTest" --tests "*CollaborationRelayServiceTest" --tests "*CollaborationWebSocketHandlerTest" --tests "*DistributedCollaborationWebSocketHandlerTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~90-180 seconds |

**Recommended verification shape:**
- Wave 1 proves migration, entity mapping, ordered replay queries, and durable schema constraints against PostgreSQL.
- Wave 2 proves snapshot cadence, append-before-ack persistence semantics, and lazy rebuild from latest snapshot plus replay.
- Wave 3 proves Redis lock/counter behavior and canonical pub/sub relay with a real Redis container.
- Wave 4 proves the WebSocket layer bootstraps from durable state, publishes through the canonical relay path, and rebuilds or resyncs on revision gaps.

**Manual-only checks to keep visible during planning:**
- Restart the service after creating edits, reconnect to an existing room, and confirm `document_sync` returns the persisted document and canonical revision.
- Run two backend instances against the same PostgreSQL and Redis stack, connect one client to each instance, and confirm operations and presence relay without divergence.

<open_questions>
## Open Questions

1. **Exact Redis locking primitive**
   - What we know: Phase 3 needs per-session apply serialization with a bounded TTL and explicit rebuild on inconsistency.
   - What's unclear: whether the cleanest implementation is `SET NX PX` + token-checked release, a Lua wrapper, or another Redis atomic pattern.
   - Recommendation: let planning lock a concrete implementation, but keep the external service interface centered on `withSessionLock(sessionId, ...)`.

2. **How much canonical history the in-memory runtime should cache after hydration**
   - What we know: stale-op transforms still need canonical history newer than a client's base revision.
   - What's unclear: whether the hydrated runtime should keep all post-snapshot operations in memory or trim history once another snapshot is written.
   - Recommendation: keep all operations after the latest snapshot in memory for Phase 3; trimming can wait until a later optimization phase if needed.
</open_questions>

<sources>
## Sources

### Primary (HIGH confidence)
- `.planning/phases/03-durable-persistence-and-multi-instance-coordination/03-CONTEXT.md` — locked Phase 3 decisions.
- `.planning/ROADMAP.md` — Phase 3 goal, requirement mapping, and success criteria.
- `.planning/REQUIREMENTS.md` — `DATA-01` through `DATA-04`.
- `.planning/STATE.md` — current project decisions and prior phase constraints.
- `build.gradle.kts` — current dependency baseline and test stack.
- `src/main/resources/db/migration/V1__phase1_baseline.sql` — existing schema conventions that Phase 3 must extend.
- `src/main/java/com/collabeditor/ot/service/CollaborationSessionRuntime.java` — current canonical runtime seam for hydration and replay.
- `src/main/java/com/collabeditor/websocket/service/CollaborationSessionRegistry.java` — current local room runtime/socket registry split.
- `src/main/java/com/collabeditor/websocket/handler/CollaborationWebSocketHandler.java` — existing collaboration protocol implementation that Phase 3 should preserve externally.
- `src/test/java/com/collabeditor/migration/FlywayMigrationTest.java` — current Testcontainers PostgreSQL verification pattern.

### Secondary (HIGH confidence)
- `.planning/phases/02-real-time-ot-collaboration/02-RESEARCH.md` — prior single-instance collaboration architecture and validation shape that Phase 3 builds on.
- `.planning/phases/02-real-time-ot-collaboration/02-VALIDATION.md` — prior Nyquist validation pattern for planning execution and verification loops.
- `.planning/research/FEATURES.md` — project-level rationale for persistence, snapshots, reconnection, and horizontal scaling.

### Tertiary (MEDIUM confidence)
- `.planning/research/STACK.md` — useful for Redis/Lettuce/Testcontainers choices, but some exploratory recommendations (Java 21, STOMP) are superseded by the actual repo state and Phase 2 decisions.
</sources>

<metadata>
## Metadata

**Research scope:**
- PostgreSQL collaboration durability
- Snapshot-plus-replay recovery
- Redis coordination and relay
- Distributed WebSocket integration
- Verification strategy for restart and multi-instance behavior

**Confidence breakdown:**
- Standard stack: HIGH - based on the current build plus prior stack research where still applicable.
- Architecture: HIGH - aligns with locked Phase 3 context and the existing Phase 2 implementation seams.
- Pitfalls: HIGH - derived from the actual repo split between in-memory runtime, handler, and registry plus the new coordination constraints.
- Validation approach: HIGH - extends the existing Postgres Testcontainers pattern and the project’s JUnit-first workflow.

**Research date:** 2026-03-29
**Valid until:** 2026-04-28
</metadata>

---

*Phase: 03-durable-persistence-and-multi-instance-coordination*
*Research completed: 2026-03-29*
*Ready for planning: yes*
