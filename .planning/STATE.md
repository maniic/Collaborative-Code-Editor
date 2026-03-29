---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: planning
stopped_at: Phase 4 complete
last_updated: "2026-03-29T21:27:03.512Z"
last_activity: 2026-03-29 -- Completed Phase 4 sandboxed code execution
progress:
  total_phases: 6
  completed_phases: 5
  total_plans: 18
  completed_plans: 18
  percent: 83
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-29)

**Core value:** The OT engine guarantees convergence so all participants end with the same document after concurrent edits.
**Current focus:** Phase 5 planning and verification preparation

## Current Position

Phase: 5
Plan: Not started
Status: Phase 4 complete -- ready to plan Phase 5
Last activity: 2026-03-29 -- Completed Phase 4 sandboxed code execution

Progress: [████████░░] 83%

## Performance Metrics

**Velocity:**

- Total plans completed: 18
- Average duration: 14 min
- Total execution time: 4.20 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Secure Access and Session Lifecycle | 3 | 35 min | 12 min |
| 01.1. Fix Phase 1 auth, session, and verification gaps | 4 | 40 min | 10 min |
| 2. Real-Time OT Collaboration | 3 | 30 min | 10 min |
| 3. Durable Persistence and Multi-Instance Coordination | 4 | 47 min | 12 min |
| 4. Sandboxed Code Execution | 4 | 100 min | 25 min |
| 5. Integration Hardening and Developer Docs | 0 | 0 min | 0 min |

**Recent Trend:**

- Last 5 plans: 03-04 (28 min), 04-01 (18 min), 04-02 (24 min), 04-03 (28 min), 04-04 (30 min)
- Trend: Rising complexity

| Phase 04 P01 | 18min | 3 tasks | 5 files |
| Phase 04 P02 | 24min | 3 tasks | 5 files |
| Phase 04 P03 | 28min | 3 tasks | 11 files |
| Phase 04 P04 | 30min | 3 tasks | 4 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- OT is the conflict-resolution model and remains server-authoritative.
- Redis is used for both cross-instance relay and low-latency coordination.
- Docker sandboxing is mandatory for all code execution flows.
- Gradle 8.14 with Foojay toolchain resolver for Java 17 auto-provisioning.
- Testcontainers 1.21.4 with ~/.docker-java.properties for Docker API 1.44+ compat.
- Case-insensitive email uniqueness via functional unique index (not inline constraint).
- SecurityProperties record with @ConfigurationProperties for type-safe auth config binding.
- Refresh tokens are 32-byte SecureRandom Base64URL; only SHA-256 hex digest is persisted.
- Device ID is UUID.randomUUID per login; preserved across rotations for reuse detection scope.
- Controller slices now use the production SecurityConfig and bearer filter chain; blanket permitAll test bypasses were removed.
- Invite codes use [A-Z2-9]{8} charset via SecureRandom (excludes 0, 1, I, O for readability).
- Owner transfer selects earliest joined_at, lexicographically smallest user_id as tiebreaker.
- Join is idempotent for already-active participants.
- Rejoin clears empty_since/cleanup_after to cancel pending cleanup.
- SessionCleanupScheduler runs on configurable fixedDelay (PT5M default).
- [Phase 01.1]: Mapped coding_sessions.participant_cap to Java short to match PostgreSQL SMALLINT — Keeps the shipped Flyway schema unchanged while making Hibernate validate against the migrated database.
- [Phase 01.1]: Gradle tests now default to the explicit test profile — Verification no longer depends on implicit local Spring profile selection or missing auth test properties.
- [Phase 01.1]: SecurityProperties now validates jwt-secret and jwt-issuer at startup — JWT auth should fail closed when config is insecure instead of silently using a fallback secret.
- [Phase 01.1]: Refresh rotation now locks the refresh session row before issuing a successor token — Concurrent refresh attempts must yield exactly one winner and one reuse failure.
- [Phase 01.1]: Protected-route security tests now run through the production filter chain and 401 entrypoint — Controller tests should verify the real bearer-token behavior instead of a permitAll test-only chain.
- [Phase 01.1]: Invite codes are normalized to uppercase and validated before session lookup — Join requests should fail with deterministic validation instead of ambiguous repository misses.
- [Phase 01.1]: Join and leave now lock the session row while mutating membership — Participant-cap enforcement and owner transfer must be serialized per room under concurrent requests.
- [Phase 01.1]: Cleanup re-locks expired session candidates before delete — A room that rejoined and cleared cleanup_after must not be deleted from a stale scheduler snapshot.
- [Phase 01.1]: SecurityConfig now permits DispatcherType.ERROR and /error so authenticated MVC validation failures preserve their original HTTP status. — Keeps bearer auth strict on business endpoints while allowing framework validation responses to surface as 400.
- [Phase 01.1]: Protected-route validation regressions are now asserted with JwtTokenService-issued bearer tokens instead of mocked principals alone. — Real bearer-path coverage catches security-chain and error-dispatch regressions before UAT.
- [Phase 02]: Same-position insert tie-break uses lexicographic authorUserId.toString() ordering — Deterministic and stable across all transform paths.
- [Phase 02]: Insert inside delete range repositioned to delete start; delete-side expands to absorb — Consistent with server-authoritative model and single-operation transform constraints.
- [Phase 02]: Java 17 instanceof dispatch instead of sealed switch — Repo toolchain targets Java 17 without preview features enabled.
- [Phase 02]: WebSocket endpoint permitted in SecurityConfig; handshake interceptor handles auth independently — Separates HTTP filter chain from WebSocket auth path cleanly.
- [Phase 02]: JwtTokenService.extractIdentity() shared helper for handshake and HTTP filter — Avoids duplicate UUID/email parsing logic.
- [Phase 02]: CollaborationSessionRegistry uses ConcurrentHashMap + CopyOnWriteArraySet — Thread-safe socket tracking without explicit locking.
- [Phase 02]: Future base revision triggers resync_required rather than socket close — Gives client a chance to recover gracefully.
- [Phase 02]: PresenceService uses ConcurrentHashMap keyed by sessionId+userId for ephemeral presence state — No persistence needed for cursor positions in Phase 2.
- [Phase 02]: Selection range transformation applies same insert/delete logic as OT but to cursor positions — Keeps ranges aligned with canonical document after edits.
- [Phase 02]: Cursor throttle window is configurable via app.collaboration.cursor-throttle-ms (default 75ms) — Prevents cursor broadcast flooding without losing latest state.
- [Phase 02]: Email resolution uses existing UserRepository.findById rather than duplicating user data — Single source of truth for participant identity.
- [Phase 03]: Accepted canonical operations must be durably persisted before ack or broadcast — An acknowledged edit must survive a service restart.
- [Phase 03]: PostgreSQL remains the durable source of truth while Redis handles active-session coordination and relay — Avoids split durable state across systems.
- [Phase 03]: Snapshots are created at least every 50 canonical operations and recovery always uses latest snapshot plus replay — Snapshots speed recovery without replacing full history.
- [Phase 03]: Session runtimes rebuild lazily after restart or local eviction — Recovery cost is paid only for active rooms instead of at application boot.
- [Phase 03]: Cross-instance collaboration fan-out comes from a single canonical Redis relay path and revision gaps force rebuild or resync — Multi-instance delivery must fail safe rather than drift.
- [Phase 03]: DELETE constraint requires explicit length IS NOT NULL to defeat SQL three-valued logic NULL passthrough
- [Phase 03]: SET NX PX with random token for per-session distributed locking in Redis
- [Phase 03]: Deterministic Redis key patterns collab:session:{sessionId}:{lock|revision|active|events}
- [Phase 03]: Jackson-serialized typed relay payloads for canonical collaboration events via Redis pub/sub
- [Phase 03]: Local sockets consume accepted operations and presence updates only through the canonical Redis relay path, including on the origin instance. — Keeps single-path fan-out semantics across all backend nodes.
- [Phase 03]: Relay revision gaps evict the cached runtime, rebuild durable state, and emit resync_required rather than continuing on stale memory. — Prevents silent divergence after missed pub/sub events.
- [Phase 03]: Presence join preserves existing selection and throttle timestamps for already-tracked users. — Self-relayed participant events must not reset local cursor behavior.
- [Phase 04]: Execution admission snapshots the persisted canonical room document, immutable room language, and source revision before queueing. — Runs must execute the server-authoritative room state rather than client-provided source.
- [Phase 04]: Python and Java execution use fixed runtime contracts and docker-java with explicit Docker/Apache HTTP version pinning. — Phase 4 should stay predictable and avoid shelling out to docker run.
- [Phase 04]: Sandbox inputs are bind-mounted from a home-directory workspace and writable runtime paths are tmpfs mounts owned by uid/gid 65534. — Keeps the filesystem restrictive while still allowing non-root Java compilation on the local Docker/Colima setup.
- [Phase 04]: Execution lifecycle updates relay through Redis and broadcast on the existing room websocket as execution_updated. — Async execution progress must remain room-visible across backend instances.
- [Phase 04]: Redis listener callbacks run synchronously for execution events. — Preserves QUEUED → RUNNING → terminal ordering on each node.

### Pending Todos

None yet.

### Roadmap Evolution

- Phase 01.1 inserted after Phase 1: Fix Phase 1 auth, session, and verification gaps (URGENT)
- Phase 01.1 expanded to 4 sequential plans after UAT exposed the protected validation 401 regression

### Blockers/Concerns

None currently. Phase 5 planning can begin.

## Session Continuity

Last session: 2026-03-29T21:24:16Z
Stopped at: Phase 4 complete
Resume file: .planning/phases/04-sandboxed-code-execution/04-VERIFICATION.md
