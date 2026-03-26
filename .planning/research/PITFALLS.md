# Domain Pitfalls

**Domain:** Real-time collaborative code editor backend (OT-based, Java/Spring Boot)
**Researched:** 2026-03-26
**Confidence:** MEDIUM (based on training knowledge; web verification unavailable)

## Critical Pitfalls

Mistakes that cause rewrites, data corruption, or fundamental architectural failure.

---

### Pitfall 1: Incorrect OT Transform Functions Break Document Convergence

**What goes wrong:** The OT transform function has subtle off-by-one errors or fails to handle all operation type combinations correctly. Two users editing simultaneously end up with different documents. The divergence is silent -- no errors, no crashes -- just different text on different clients.

**Why it happens:** OT transform correctness depends on satisfying the TP1 (Transformation Property 1) condition: applying op1 then transformed op2 must yield the same state as applying op2 then transformed op1. With multi-character operations (position + string), the combinatorics expand: insert-before-insert, insert-at-same-position, insert-within-delete-range, delete-overlapping-delete, delete-containing-insert-point. Each has edge cases at range boundaries.

**Consequences:**
- Documents silently diverge across users (no error thrown, just wrong text)
- Divergence compounds over time -- once documents differ, all subsequent transforms apply to different base states
- Users lose trust immediately; this is the one thing that cannot be wrong

**Warning signs:**
- Tests pass for simple cases but fail when 3+ operations interact
- "It works most of the time" -- OT must work ALL of the time
- Fuzzy or random operation tests occasionally fail and get dismissed as flaky

**Prevention:**
1. Implement and test ALL six transform pairs exhaustively: (ins,ins), (ins,del), (del,ins), (del,del), plus same-position tiebreaking
2. Write a dedicated convergence test: generate random operations from 3 simulated users, apply transforms, assert all three arrive at identical documents. Run hundreds of times with random seeds.
3. Handle "insert at same position" tiebreak deterministically -- use user ID as tiebreaker, be consistent everywhere
4. For multi-character deletes containing another user's insert point -- the insert must be preserved and repositioned, not swallowed
5. For overlapping deletes, overlap region deleted once, positions adjusted for non-overlapping portions

**Phase:** Phase 1 (OT Engine). Get this right before building anything on top.

---

### Pitfall 2: Race Condition Between WebSocket Message Ordering and OT Revision Counter

**What goes wrong:** The server receives operations from multiple clients concurrently. Two operations arrive with the same base revision. Without proper serialization, both get processed against the same base state, but the second needed to be transformed against the first.

**Why it happens:** Spring WebSocket handlers process messages concurrently by default. Developers assume "messages arrive one at a time."

**Prevention:**
1. Serialize all operation processing per session. Per-session lock or single-threaded executor per session ID. NOT a global lock.
2. Use Redis atomic increment (INCR) for revision counter.
3. If operation arrives with stale base revision, transform forward through all operations since that revision.
4. Write concurrent integration tests: multiple threads sending operations to same session, assert convergence.

**Phase:** Phase 2 (WebSocket layer). Correctness requirement, not optimization.

---

### Pitfall 3: Docker Container Escape or Resource Exhaustion

**What goes wrong:** User-submitted code escapes sandbox, consumes unbounded resources, or creates denial-of-service. Fork bombs, disk filling, network exfiltration.

**Prevention — apply ALL flags, not just memory/CPU:**
- `--memory=256m --memory-swap=256m` (prevent swap abuse)
- `--cpus=0.5`
- `--pids-limit=64` (prevent fork bombs)
- `--network=none`
- `--read-only` (read-only root filesystem)
- `--tmpfs /tmp:size=10m,noexec` (limited writable temp)
- `--user=nobody` (non-root)
- `--security-opt=no-new-privileges`
- `--cap-drop=ALL`

Also: hard timeout watchdog at application level, container cleanup routine, pre-pull images, hardcode allowed images (never from user input), global concurrent execution limit.

**Phase:** Phase 3 (Code Execution). Security-first from day one.

---

### Pitfall 4: Operation History Growth Causes Unbounded Memory and Slow Transforms

**What goes wrong:** Operation log grows indefinitely. Stale client reconnection requires O(n) transforms against entire history, causing latency spikes.

**Prevention:**
1. Document snapshots every 50 operations (already planned)
2. On reconnect with stale revision older than latest snapshot, send snapshot + ops since
3. Keep only last ~200 operations in Redis; older ops in PostgreSQL only
4. Maximum staleness threshold: if client is 200+ behind, force full resync
5. Index operation log by (session_id, revision_number)

**Phase:** Phase 1 (snapshot logic), Phase 4 (Redis caching, staleness threshold).

---

## Moderate Pitfalls

### Pitfall 5: WebSocket Connection Lifecycle Mismanagement

**What goes wrong:** WebSocket connections silently die (network change, laptop sleep) without triggering onClose. Ghost users remain in session.

**Prevention:** Application-level ping/pong heartbeats every 15-30 seconds. If 2 missed heartbeats, consider connection dead. Allow reconnection with grace period (~60 seconds). Test with actual network interruption, not just session.close().

**Phase:** Phase 2 (WebSocket layer).

---

### Pitfall 6: Redis Pub/Sub Message Loss Under Load

**What goes wrong:** Redis pub/sub is fire-and-forget. Slow subscribers lose messages. Connection drops lose messages during gap. Cross-instance document divergence.

**Prevention:**
1. Canonical state lives in operation log (PostgreSQL) and revision counter (Redis INCR). Pub/sub is optimization, not source of truth.
2. Periodic verification of local session state against canonical revision.
3. Catch-up mechanism: if receiving revision N but local state is at N-3, fetch missing ops from database.
4. Consider Redis Streams instead of raw pub/sub for durability.
5. On Redis reconnection, trigger catch-up for all active sessions.

**Phase:** Phase 4 (Redis/Horizontal Scaling). Most likely source of bugs when going multi-instance.

---

### Pitfall 7: Multi-Character OT Transform Position Arithmetic Errors

**What goes wrong:** Multi-character operations introduce range arithmetic. Boundary cases: inserts within delete range, partially overlapping deletes, inserts at exact boundary of delete range.

**Prevention:**
1. Model operations as ranges: Insert(position, text), Delete(position, length)
2. Exhaustive boundary tests for every transform pair: entirely before, entirely after, exact start, exact end, partial overlap left/right, entirely within, entirely containing
3. Consistent convention for "insert at position X" -- document and enforce
4. Property-based testing (random operation generation) is essential

**Phase:** Phase 1 (OT Engine).

---

### Pitfall 8: Cursor Presence Broadcasting Creates Message Flood

**What goes wrong:** Every keystroke broadcasts cursor position. 5 users typing simultaneously = 5x message rate just for cursors.

**Prevention:**
1. Throttle cursor broadcasts to every 50-100ms per user, not per keystroke
2. Separate message type from operations (operations are critical-path, cursors are cosmetic)
3. Batch cursor updates: 5 chars in 100ms = 1 cursor update with final position
4. Do NOT persist cursor positions to database (ephemeral state only)

**Phase:** Phase 2 (WebSocket layer).

---

## Minor Pitfalls

### Pitfall 9: Snapshot Race Condition

**What goes wrong:** Snapshot triggered at revision 50. While writing, new operations arrive at 51, 52. Snapshot captures inconsistent state.

**Prevention:** Take snapshots atomically: read document state and revision under the same lock used for operation processing. Record exact revision. Take data synchronously (under lock), write to PostgreSQL asynchronously.

**Phase:** Phase 1 (Snapshot module).

---

### Pitfall 10: JWT Token Expiry During Active WebSocket Session

**What goes wrong:** JWT expires after 1 hour but WebSocket session runs for 2 hours. REST calls fail or WebSocket handler starts rejecting operations mid-session.

**Prevention:** Authenticate at connection time only. Once established, connection is trusted for its lifetime. Do NOT re-validate JWT on every WebSocket message. Implement token refresh via REST endpoint.

**Phase:** Phase 2 (Auth + WebSocket integration).

---

### Pitfall 11: Testcontainers Docker-in-Docker Conflicts with Execution Sandbox

**What goes wrong:** Integration tests (Testcontainers) and code execution (docker-java) both use Docker. In CI, container cleanup removes wrong containers.

**Prevention:** Distinct container naming prefixes: `test-infra-` for Testcontainers, `exec-` for code execution. Mock execution Docker client in infrastructure tests. Cleanup routines only remove containers matching their own prefix.

**Phase:** Testing/CI phase.

---

### Pitfall 12: STOMP vs Raw WebSocket Decision

**What goes wrong:** Starting with STOMP and discovering it doesn't fit OT's need for per-operation acknowledgment and revision tracking. Migrating mid-project requires rewriting the entire message handling layer.

**Prevention:** Evaluate upfront. STOMP provides topic routing and subscription management out of the box, which is valuable. However, if custom acknowledgment per operation is needed, raw WebSocket with a custom JSON protocol (`{type: "operation"|"ack"|"cursor"|"sync"|"join"|"leave", ...payload}`) may be cleaner. Make this decision at the start.

**Phase:** Phase 2 (WebSocket layer). Architectural decision.

---

## Phase-Specific Warnings

| Phase | Likely Pitfall | Mitigation |
|-------|---------------|------------|
| OT Engine (Phase 1) | Transform boundary errors with multi-char ops | Exhaustive boundary tests + property-based fuzz testing (Pitfalls 1, 7) |
| OT Engine (Phase 1) | Snapshot at inconsistent revision | Atomic snapshot under operation lock (Pitfall 9) |
| WebSocket (Phase 2) | Concurrent processing without per-session serialization | Per-session single-threaded executor (Pitfall 2) |
| WebSocket (Phase 2) | Silent connection death | Application-level heartbeat every 15-30s (Pitfall 5) |
| WebSocket (Phase 2) | STOMP vs raw WebSocket mismatch | Decide upfront based on OT acknowledgment needs (Pitfall 12) |
| WebSocket (Phase 2) | Cursor broadcast flooding | Throttle to 50-100ms, separate from operations (Pitfall 8) |
| Auth (Phase 2) | JWT expiry kills active sessions | Authenticate once at connection time (Pitfall 10) |
| Code Execution (Phase 3) | Container escape, fork bomb, disk fill | Full Docker hardening: all flags (Pitfall 3) |
| Scaling (Phase 4) | Redis pub/sub message loss | Canonical state in PostgreSQL, catch-up mechanism (Pitfall 6) |
| Scaling (Phase 4) | History growth causes latency spikes | Snapshots + staleness threshold (Pitfall 4) |
| Testing (Phase 5) | Testcontainers vs execution Docker conflicts | Prefix-based isolation (Pitfall 11) |
