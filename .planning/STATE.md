---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: completed
stopped_at: Phase 01.1 complete — ready for Phase 2
last_updated: "2026-03-27T20:00:36Z"
last_activity: 2026-03-27 — Completed Phase 01.1 and closed the protected validation gap
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 7
  completed_plans: 7
  percent: 33
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-26)

**Core value:** The OT engine guarantees convergence so all participants end with the same document after concurrent edits.
**Current focus:** Phase 2 — real-time-ot-collaboration

## Current Position

Phase: 2 (real-time-ot-collaboration)
Plan: Not started
Status: Phase 01.1 complete — ready for Phase 2
Last activity: 2026-03-27 — Completed Phase 01.1 and closed the protected validation gap

Progress: [███░░░░░░░] 33%

## Performance Metrics

**Velocity:**

- Total plans completed: 7
- Average duration: 11 min
- Total execution time: 1.25 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 1. Secure Access and Session Lifecycle | 3 | 35 min | 12 min |
| 01.1. Fix Phase 1 auth, session, and verification gaps | 4 | 40 min | 10 min |
| 2. Real-Time OT Collaboration | 0 | 0 min | 0 min |
| 3. Durable Persistence and Multi-Instance Coordination | 0 | 0 min | 0 min |
| 4. Sandboxed Code Execution | 0 | 0 min | 0 min |
| 5. Integration Hardening and Developer Docs | 0 | 0 min | 0 min |

**Recent Trend:**

- Last 5 plans: 01-03 (7 min), 01.1-01 (7 min), 01.1-02 (18 min), 01.1-03 (11 min), 01.1-04 (4 min)
- Trend: Stable

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

### Pending Todos

None yet.

### Roadmap Evolution

- Phase 01.1 inserted after Phase 1: Fix Phase 1 auth, session, and verification gaps (URGENT)
- Phase 01.1 expanded to 4 sequential plans after UAT exposed the protected validation 401 regression

### Blockers/Concerns

None currently. Phase 01.1 remediation is complete and Phase 2 may begin.

## Session Continuity

Last session: 2026-03-27T20:00:36Z
Stopped at: Phase 01.1 complete — ready for Phase 2
Resume file: None
