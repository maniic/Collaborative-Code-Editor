# Phase 1: Secure Access and Session Lifecycle - Context

**Gathered:** 2026-03-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 1 delivers JWT-based authentication plus REST endpoints for session create, list, join, and leave flows, along with the initial Flyway-backed schema needed to support those capabilities. It does not include WebSocket collaboration, OT behavior, snapshots, Redis relay, or code execution.

</domain>

<decisions>
## Implementation Decisions

### Authentication Contract
- **D-01:** Access tokens should be short-lived JWTs sent as bearer tokens in the `Authorization` header.
- **D-02:** Refresh tokens should be stored in an httpOnly cookie rather than returned as a normal bearer token in JSON responses.
- **D-03:** Refresh tokens should rotate on every successful refresh request.
- **D-04:** Refresh sessions should be tracked per device or browser so multiple devices can stay signed in simultaneously.

### Session Discovery and Join Flow
- **D-05:** The session list should show only sessions the authenticated user created or has already joined, not a global public directory of joinable rooms.
- **D-06:** Each session should have both a canonical UUID and a separate invite code; the invite code is the human-facing identifier used for sharing and joins.
- **D-07:** The session language is chosen at room creation and remains immutable afterward.

### Ownership and Lifecycle
- **D-08:** Each session should have a special owner role, with the creator as the initial owner.
- **D-09:** If the owner leaves while other participants remain, ownership should transfer instead of closing the room.
- **D-10:** When the last participant leaves, the room should remain recoverable for about one hour before cleanup.
- **D-11:** Phase 1 should enforce a modest participant cap, roughly in the 8-16 range, to keep room behavior predictable in the first milestone.

### the agent's Discretion
- Exact access-token expiry value within the short-lived range.
- Exact refresh-cookie attributes and naming, as long as they match the chosen bearer-plus-cookie model.
- Invite-code format, length, and generation strategy.
- Owner-transfer selection rule when multiple participants remain.
- Concrete cleanup mechanism for the one-hour empty-room TTL.
- Exact participant-cap number within the chosen soft-cap range.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` — Phase 1 goal, mapped requirements, and success criteria.
- `.planning/REQUIREMENTS.md` — `AUTH-01`, `AUTH-02`, `AUTH-03`, `SESS-01`, `SESS-02`, `SESS-03`, `SESS-04`, and `QUAL-01`.
- `.planning/PROJECT.md` — core value, room model, stack constraints, and out-of-scope boundaries for the milestone.

### Project instructions
- `AGENTS.md` — project-specific GSD workflow guardrails that downstream agents should follow.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None yet — this is still a greenfield repository with planning artifacts only.

### Established Patterns
- The project is locked to Java 17+ with Spring Boot 3, PostgreSQL, Redis, and Docker.
- Package boundaries are expected to center around `auth`, `session`, `websocket`, `ot`, `execution`, `redis`, and `snapshot`.
- The room model is already defined at the planning level as a UUID-backed coding session with a selected language and participant list.

### Integration Points
- Phase 1 establishes the initial `auth` and `session` modules, the first Flyway migrations, and the REST API contracts that later WebSocket and execution phases will build on.

</code_context>

<specifics>
## Specific Ideas

- Use the invite code as the user-facing share path even though the backend keeps a UUID as the canonical identifier.
- Treat this phase as the place to lock API and persistence semantics for authentication and room lifecycle, since later phases depend on them.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 01-secure-access-and-session-lifecycle*
*Context gathered: 2026-03-26*
