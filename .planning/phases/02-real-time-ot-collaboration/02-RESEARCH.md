# Phase 2: Real-Time OT Collaboration - Research

**Researched:** 2026-03-27
**Domain:** Server-authoritative OT, raw Spring WebSocket collaboration, and in-memory session coordination
**Confidence:** HIGH

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Use raw JSON over a Spring WebSocket endpoint rather than STOMP destinations or broker-style messaging.
- Bind each socket to a single session-scoped endpoint keyed by canonical `sessionId`, not invite code.
- Authenticate the WebSocket connection during the handshake using the existing JWT bearer model.
- Use typed message envelopes with explicit `type` and `payload` fields for all collaboration events.
- On connect, send the full current document, the current canonical revision, and the active participant snapshot.
- Clients submit operations against a base revision and receive an explicit server acknowledgement containing the assigned canonical revision.
- After accepting an operation, the server broadcasts the canonical transformed operation to all participants, including the sender.
- New collaboration sessions start with an empty document at revision `0`.
- If a client submits an operation against an older revision, the server transforms it against the missed canonical operations and applies it if still valid.
- If an operation is malformed or invalid after validation or transform, reject only that operation with an explicit error event and keep the socket open.
- If an authenticated socket no longer belongs to an active participant in the room, reject collaboration traffic as unauthorized and close the session socket.
- If the server detects a desync it cannot safely reconcile, emit a resync-required event containing the latest full document and canonical revision.
- Cursor presence supports selection ranges; a caret is represented as a zero-length range.
- Presence payloads include both `userId` and `email` for participant identity.
- Join and leave updates are explicit `participant_joined` and `participant_left` events rather than inferred full-list diffs.
- Cursor and selection updates are real-time events; the exact throttling interval remains implementation discretion.

### the agent's Discretion
- Exact endpoint path naming and handshake header/query transport, as long as the connection stays session-scoped and JWT-authenticated.
- Exact JSON field names inside each payload beyond the locked `type` + `payload` envelope pattern.
- Exact WebSocket error codes and close-status mapping for unauthorized, invalid-operation, and resync cases.
- Exact cursor-update throttling interval and debounce strategy.

### Deferred Ideas (OUT OF SCOPE)
- None — discussion stayed within phase scope.
</user_constraints>

<research_summary>
## Summary

Phase 2 should be implemented as a single-instance, in-memory collaboration layer that sits on top of the Phase 1 auth and session lifecycle baseline. The safest shape for this repo is to build the OT engine first as pure Java logic, then wrap it in a per-session runtime that owns canonical document text, revision history, and operation serialization, and only then expose that runtime through a raw Spring WebSocket endpoint. This order matches the roadmap, the project’s core value, and the existing codebase state: there is no `websocket` or `ot` package yet, while `auth` and `session` already provide the identity and room-membership source of truth.

The most important architectural conclusion is that Phase 2 should remain deliberately local and deterministic. Document state, revision history, active sockets, and presence state should all stay in memory for this phase; PostgreSQL snapshots, Redis counters, Redis pub/sub, and cross-instance recovery belong to later phases. For correctness, every room needs serialized operation processing, deterministic transform rules for same-position and overlapping edits, and a test suite that proves pairwise transform behavior plus three-user convergence before the handler layer is trusted.

**Primary recommendation:** plan Phase 2 in three waves: OT core + convergence tests first, raw WebSocket handshake/sync/ack protocol second, then presence broadcasting and final phase verification.
</research_summary>

<standard_stack>
## Standard Stack

The established libraries and tools for this phase:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Java | 17 toolchain (repo standard) | Runtime for OT engine, handlers, and tests | The repository already targets Java 17 in `build.gradle.kts`; planning should align to the shipped build, not the earlier exploratory Java 21 research. |
| Spring Boot | 3.3.4 (repo standard) | Application framework | Already pinned in the repo and proven by Phase 1 execution. |
| Spring WebSocket | managed by Boot | Raw WebSocket endpoint and handler infrastructure | Needed for `/ws/sessions/{sessionId}` while keeping the app on the existing MVC stack. |
| Spring Security | managed by Boot | Handshake-time JWT validation and protected session access | Existing auth/session code already relies on Spring Security plus app-issued bearer tokens. |
| Jackson | managed by Boot | Typed JSON envelopes and payload serialization | Fits the repo’s existing explicit DTO pattern and avoids map-based payload parsing. |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JUnit 5 + AssertJ | managed by Boot | OT transform edge tests, convergence tests, handler tests | Use for every OT rule and protocol contract in this phase. |
| Spring Boot Test | managed by Boot | WebSocket endpoint and handler integration tests | Use when verifying connect/bootstrap/ack/error flows against the configured endpoint. |
| StandardWebSocketClient | from Spring WebSocket | Test client for raw WebSocket integration tests | Use for end-to-end socket contract tests without introducing STOMP. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Raw WebSocket + custom envelopes | STOMP + broker-style destinations | Earlier project research leaned toward STOMP, but the locked Phase 2 context explicitly prefers raw JSON so per-operation acknowledgements and resync payloads stay fully under project control. |
| Per-session lock / single-writer runtime | Shared mutable state on handler threads | Simpler to start, but unsafe for revision ordering and stale-op transforms. |
| In-memory document/history state | Immediate PostgreSQL or Redis-backed state | Durable storage matters later, but Phase 2 needs single-instance correctness first. |

**Installation:**
```bash
# Add the Spring WebSocket starter to the existing build
# implementation("org.springframework.boot:spring-boot-starter-websocket")
```
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### Recommended Project Structure
```text
src/main/java/com/collabeditor/
├── ot/
│   ├── model/      # Insert/delete operations, canonical history entries, snapshots
│   └── service/    # Transform rules and in-memory collaboration runtime
├── websocket/
│   ├── config/     # WebSocket endpoint registration
│   ├── handler/    # Raw text WebSocket handler
│   ├── protocol/   # Typed client/server envelopes and payload DTOs
│   ├── security/   # Handshake interceptor using existing JWT semantics
│   └── service/    # Socket registry and presence management
├── auth/           # Existing JWT parsing and security config
└── session/        # Existing room membership and participant lookups
```

### Pattern 1: Pure OT transform service with deterministic tie-breaking
**What:** Keep the OT rules in a package that has no WebSocket, JPA, or Spring transport dependencies.
**When to use:** Always for the first implementation of this project’s OT core, because transform correctness is the highest-risk part and should be unit-testable in isolation.
**Concrete guidance:**
- Model insert and delete as explicit multi-character operations, not single-character diffs.
- Encode author identity or another stable value on the operation so same-position insert ties are deterministic.
- Provide one pure function for pairwise transforms and another for applying a transformed operation to a document string or `StringBuilder`.

### Pattern 2: Per-session collaboration runtime with serialized mutation
**What:** Store a room’s canonical document text, revision, and applied operation history in one in-memory runtime object guarded by a per-session lock or single-writer discipline.
**When to use:** All operation submission paths in this phase.
**Concrete guidance:**
- Keep one runtime per canonical `sessionId`.
- Serialize submit/apply/broadcast decisions per room; never let two WebSocket threads mutate the same room state concurrently.
- Return a structured result from the runtime: ack revision, canonical transformed operation, and full snapshot when resync is required.

### Pattern 3: Handshake-authenticated raw WebSocket endpoint with typed envelopes
**What:** Use `WebSocketConfigurer` + `TextWebSocketHandler` with a handshake interceptor that validates the bearer token once and attaches `userId`, `email`, and `sessionId` to the socket.
**When to use:** All Phase 2 collaboration traffic, because the user locked raw JSON plus handshake auth.
**Concrete guidance:**
- Register a room endpoint shaped like `/ws/sessions/{sessionId}`.
- Reuse `JwtTokenService` for token validation rather than duplicating parsing logic.
- Keep explicit server message types such as `document_sync`, `operation_ack`, `operation_applied`, `operation_error`, `resync_required`, `participant_joined`, `participant_left`, and `presence_updated`.

### Anti-Patterns to Avoid
- **Processing room operations directly on unconstrained shared state:** OT correctness depends on deterministic per-room ordering.
- **Re-validating JWTs on every WebSocket message:** the project already uses short-lived access tokens plus refresh; Phase 2 should authenticate once at connect time and rely on room membership checks thereafter.
- **Pulling in persistence or Redis semantics early:** snapshots, durable op logs, and cross-instance coordination are explicitly Phase 3 concerns.
</architecture_patterns>

<dont_hand_roll>
## Don't Hand-Roll

Problems that look simple but already have a safe project-local solution:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JWT verification for handshake auth | A second custom token parser in `websocket` | Reuse `JwtTokenService` and shared identity extraction | Prevents REST and WebSocket auth semantics from drifting apart. |
| Room authorization source | A separate in-memory participant membership list | Existing `SessionParticipantRepository` and `CodingSessionRepository` | Phase 1 already established canonical room membership rules. |
| JSON payload parsing | Manual string splitting or `Map<String, Object>` everywhere | Typed record/DTO payloads with Jackson | Keeps the protocol explicit, testable, and aligned with the repo’s current DTO style. |
| OT correctness validation | Manual socket clicking or a few hand-written happy-path tests | Exhaustive transform tests plus seeded three-user convergence tests | The core risk in this phase is silent divergence, not compile errors. |

**Key insight:** the project’s originality is the OT algorithm, not ad hoc transport parsing or duplicate auth logic.
</dont_hand_roll>

<common_pitfalls>
## Common Pitfalls

### Pitfall 1: Pairwise OT rules look correct but fail under multi-character boundary cases
**What goes wrong:** insert/delete overlaps, same-position inserts, and delete ranges that partially overlap each other produce subtle off-by-one divergence.
**Why it happens:** multi-character OT has more edge cases than single-character demos, and happy-path tests miss them.
**How to avoid:** build the OT engine first, enumerate insert/insert, insert/delete, delete/insert, and delete/delete boundaries explicitly, and add seeded three-user convergence tests before wiring the handler layer.
**Warning signs:** “works most of the time” failures, flaky random tests, or logic that cannot explain exact same-position behavior.

### Pitfall 2: WebSocket threads race the room revision counter
**What goes wrong:** two ops arrive against the same base revision and both mutate shared state before either is transformed against the other.
**Why it happens:** Spring’s WebSocket infrastructure can process messages concurrently unless the app serializes room mutation itself.
**How to avoid:** keep one runtime per room and guard `submitOperation` with a per-session lock or equivalent single-writer flow.
**Warning signs:** duplicate revision numbers, non-deterministic test failures, or logic that reads/modifies/writes room state without a room-level guard.

### Pitfall 3: JWT expiry semantics are applied incorrectly to established sockets
**What goes wrong:** a socket that was valid at connect time starts failing ordinary collaboration messages because the server re-checks expiry on every message.
**Why it happens:** teams reuse HTTP request auth patterns inside the message loop.
**How to avoid:** authenticate once at the handshake, preserve the resolved user identity on the socket, and enforce authorization by active room membership for later messages.
**Warning signs:** message handlers reparsing raw bearer headers or fresh JWT strings on every inbound frame.

### Pitfall 4: Presence traffic overwhelms the useful signal
**What goes wrong:** cursor updates flood the room with more messages than operation traffic, and selections drift after edits.
**Why it happens:** cursor broadcasts are treated like critical-path operations instead of ephemeral state.
**How to avoid:** keep presence state in memory only, throttle outbound cursor broadcasts, and transform selection ranges through the same canonical operations that modify the document.
**Warning signs:** one socket typing causes a burst of cursor frames per keystroke or selections remain anchored to stale offsets after inserts/deletes.
</common_pitfalls>

## Validation Architecture

Phase 2 should validate correctness in three layers:

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + raw WebSocket integration tests |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*OperationalTransformServiceTest" --tests "*CollaborationSessionRuntimeTest" --tests "*CollaborationWebSocketHandlerTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30-90 seconds |

**Recommended verification shape:**
- Wave 1 proves transform rules, runtime revision handling, and three-user convergence without any network transport.
- Wave 2 proves handshake auth, room bootstrap, explicit acknowledgements, canonical broadcasts, and error/resync behavior through the actual WebSocket endpoint.
- Wave 3 proves presence join/leave events, selection-range updates, and final end-to-end collaboration behavior, then reruns the full suite.

**Manual-only checks to keep visible during planning:**
- Live WebSocket connect with a bearer token and an already-joined session.
- Two users observing join/leave and selection updates in real time.

<open_questions>
## Open Questions

1. **Exact same-position insert tie-break**
   - What we know: it must be deterministic and stable across every transform path.
   - What's unclear: whether `authorUserId`, `clientOperationId`, or a combined comparison should win the tie.
   - Recommendation: let planning pick a concrete deterministic ordering and encode it in both the transform service and tests.

2. **Heartbeat support in Phase 2**
   - What we know: silent disconnects can leave ghost presence state.
   - What's unclear: whether the minimal Phase 2 implementation should add explicit ping/pong or rely on ordinary socket close handling.
   - Recommendation: keep heartbeat optional unless the planned tests expose ghost-presence regressions; do not let it delay the main OT and protocol work.
</open_questions>

<sources>
## Sources

### Primary (HIGH confidence)
- `.planning/phases/02-real-time-ot-collaboration/02-CONTEXT.md` — locked Phase 2 decisions
- `.planning/ROADMAP.md` — Phase 2 goal, requirement mapping, and success criteria
- `.planning/REQUIREMENTS.md` — `COLL-01` through `COLL-06` and `QUAL-02`
- `build.gradle.kts` — current repo runtime and dependency baseline
- `src/main/java/com/collabeditor/auth/service/JwtTokenService.java` — current JWT parsing API available to the WebSocket handshake
- `src/main/java/com/collabeditor/auth/security/SecurityConfig.java` — existing security model
- `src/main/java/com/collabeditor/session/service/SessionService.java` — current session lifecycle rules that collaboration must honor
- `src/main/java/com/collabeditor/session/persistence/SessionParticipantRepository.java` — canonical active-membership lookup path

### Secondary (HIGH confidence)
- `.planning/research/ARCHITECTURE.md` — prior architecture research for OT, session state, and handler boundaries
- `.planning/research/PITFALLS.md` — prior phase-specific warnings about OT correctness, socket ordering, and presence flooding
- `.planning/research/SUMMARY.md` — build-order rationale that OT should precede transport wiring

### Tertiary (MEDIUM confidence)
- `.planning/research/STACK.md` — exploratory stack research that assumed STOMP and Java 21; useful for contrast, but superseded here by the current repo state and locked raw-WebSocket decision
</sources>

<metadata>
## Metadata

**Research scope:**
- OT transform design
- In-memory collaboration state
- Raw Spring WebSocket integration
- Handshake-time JWT auth reuse
- Presence semantics and verification strategy

**Confidence breakdown:**
- Standard stack: HIGH - derived from the current repo and existing Phase 1 implementation
- Architecture: HIGH - aligned across roadmap, context, and project research
- Pitfalls: HIGH - reinforced by existing project pitfall research and the repo’s current lack of concurrency guards in collaboration code
- Validation approach: HIGH - follows the project’s existing JUnit/Gradle testing pattern

**Research date:** 2026-03-27
**Valid until:** 2026-04-26
</metadata>

---

*Phase: 02-real-time-ot-collaboration*
*Research completed: 2026-03-27*
*Ready for planning: yes*
