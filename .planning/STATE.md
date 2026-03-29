---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Completed 03-01-PLAN.md
last_updated: "2026-03-29T06:56:14.440Z"
last_activity: 2026-03-29 -- Completed 03-01 database schema and repository foundation
progress:
  total_phases: 6
  completed_phases: 3
  total_plans: 14
  completed_plans: 11
  percent: 50
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-26)

**Core value:** The OT engine guarantees convergence so all participants end with the same document after concurrent edits.
**Current focus:** Phase 3 context gathered, ready for planning

## Current Position

Phase: 3 (durable-persistence-and-multi-instance-coordination) -- IN PROGRESS
Plan: 1 of 4
Status: Executing Phase 3 plans -- Plan 01 complete (schema and repository foundation)
Last activity: 2026-03-29 -- Completed 03-01 database schema and repository foundation

Progress: [█████░░░░░] 50%

## Performance Metrics

**Velocity:**

- Total plans completed: 10
- Average duration: 11 min
- Total execution time: 1.75 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Secure Access and Session Lifecycle | 3 | 35 min | 12 min |
| 01.1. Fix Phase 1 auth, session, and verification gaps | 4 | 40 min | 10 min |
| 2. Real-Time OT Collaboration | 3 | 30 min | 10 min |
| 3. Durable Persistence and Multi-Instance Coordination | 0 | 0 min | 0 min |
| 4. Sandboxed Code Execution | 0 | 0 min | 0 min |
| 5. Integration Hardening and Developer Docs | 0 | 0 min | 0 min |

**Recent Trend:**

- Last 5 plans: 01.1-03 (11 min), 01.1-04 (4 min), 02-01 (11 min), 02-02 (9 min), 02-03 (10 min)
- Trend: Stable
| Phase 03 P01 | 6min | 3 tasks | 9 files |

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

### Pending Todos

None yet.

### Roadmap Evolution

- Phase 01.1 inserted after Phase 1: Fix Phase 1 auth, session, and verification gaps (URGENT)
- Phase 01.1 expanded to 4 sequential plans after UAT exposed the protected validation 401 regression

### Blockers/Concerns

None currently. Phase 3 context is captured and planning may begin.

## Session Continuity

Last session: 2026-03-29T06:56:14.438Z
Stopped at: Completed 03-01-PLAN.md
Resume file: None
