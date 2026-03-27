# Phase 2: Real-Time OT Collaboration - Context

**Gathered:** 2026-03-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 2 delivers authenticated, session-scoped WebSocket collaboration for a single shared document per coding session. Joined participants can connect in real time, submit multi-character insert/delete operations to a server-authoritative OT engine, receive canonical operation broadcasts, see cursor and selection presence, and receive participant join/leave events. This phase does not yet persist document state or operation history, create snapshots, coordinate through Redis, or execute code.

</domain>

<decisions>
## Implementation Decisions

### Real-Time Protocol
- **D-01:** Use raw JSON over a Spring WebSocket endpoint rather than STOMP destinations or broker-style messaging.
- **D-02:** Bind each socket to a single session-scoped endpoint keyed by canonical `sessionId`, not invite code.
- **D-03:** Authenticate the WebSocket connection during the handshake using the existing JWT bearer model.
- **D-04:** Use typed message envelopes with explicit `type` and `payload` fields for all collaboration events.

### Document Sync and Revisions
- **D-05:** On connect, send the full current document, the current canonical revision, and the active participant snapshot.
- **D-06:** Clients submit operations against a base revision and receive an explicit server acknowledgement containing the assigned canonical revision.
- **D-07:** After accepting an operation, the server broadcasts the canonical transformed operation to all participants, including the sender.
- **D-08:** New collaboration sessions start with an empty document at revision `0`.

### Operation Recovery
- **D-09:** If a client submits an operation against an older revision, the server transforms it against the missed canonical operations and applies it if still valid.
- **D-10:** If an operation is malformed or invalid after validation or transform, reject only that operation with an explicit error event and keep the socket open.
- **D-11:** If an authenticated socket no longer belongs to an active participant in the room, reject collaboration traffic as unauthorized and close the session socket.
- **D-12:** If the server detects a desync it cannot safely reconcile, emit a resync-required event containing the latest full document and canonical revision.

### Presence Semantics
- **D-13:** Cursor presence supports selection ranges; a caret is represented as a zero-length range.
- **D-14:** Presence payloads include both `userId` and `email` for participant identity.
- **D-15:** Join and leave updates are explicit `participant_joined` and `participant_left` events rather than inferred full-list diffs.
- **D-16:** Cursor and selection updates are real-time events; the exact throttling interval remains implementation discretion.

### the agent's Discretion
- Exact endpoint path naming and handshake header/query transport, as long as the connection stays session-scoped and JWT-authenticated.
- Exact JSON field names inside each payload beyond the locked `type` + `payload` envelope pattern.
- Exact WebSocket error codes and close-status mapping for unauthorized, invalid-operation, and resync cases.
- Exact cursor-update throttling interval and debounce strategy.

</decisions>

<specifics>
## Specific Ideas

- Keep the collaboration contract clean and explicit for future frontend integration: typed envelopes, explicit acknowledgements, and explicit error/resync events.
- Use canonical `sessionId` as the room identity for WebSocket collaboration; invite codes remain the human-facing join mechanism from Phase 1.

</specifics>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope and requirements
- `.planning/ROADMAP.md` — Phase 2 goal, dependency on Phase 01.1, and success criteria for collaboration, convergence, presence, and OT testing.
- `.planning/REQUIREMENTS.md` — `COLL-01` through `COLL-06` and `QUAL-02`, plus the single-document scope boundary.
- `.planning/PROJECT.md` — server-authoritative OT, multi-character operations, single shared document per session, future frontend contract quality, and package structure expectations.

### Prior phase decisions
- `.planning/phases/01-secure-access-and-session-lifecycle/01-CONTEXT.md` — canonical session identity, invite-code semantics, immutable language, and session membership lifecycle rules that collaboration must honor.

### Project instructions
- `AGENTS.md` — GSD workflow guardrails, stack constraints, and required package structure for new collaboration modules.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `src/main/java/com/collabeditor/session/service/SessionService.java` — existing canonical rules for session ownership, join/leave lifecycle, participant caps, and invite-code normalization should be reused by collaboration authorization.
- `src/main/java/com/collabeditor/session/persistence/CodingSessionRepository.java` and `src/main/java/com/collabeditor/session/persistence/SessionParticipantRepository.java` — existing session and active-participant lookups can back room membership checks and presence bootstrap.
- `src/main/java/com/collabeditor/auth/security/JwtAuthenticationFilter.java` and `src/main/java/com/collabeditor/auth/security/SecurityConfig.java` — established bearer-token parsing and stateless auth model can inform WebSocket handshake authentication.
- `src/main/java/com/collabeditor/common/api/ApiExceptionHandler.java` — current backend favors explicit error semantics, which aligns with the chosen error/resync event pattern for collaboration.

### Established Patterns
- The current backend uses explicit typed DTOs and narrow controller/service boundaries; the WebSocket protocol should mirror that with typed event envelopes and payload DTOs.
- Session identity is canonicalized around `sessionId`; invite codes are human-facing join tokens only.
- Security is fail-closed and JWT-based; collaboration should not introduce a second looser auth path.
- `build.gradle.kts` does not yet include a WebSocket or broker-specific stack, so Phase 2 starts from a clean slate and can adopt a raw Spring WebSocket approach directly.

### Integration Points
- New `websocket` package code should authenticate the handshake through the existing auth module and validate active room membership through the existing session repositories.
- New `ot` package code should own the in-memory document text, revision counter, and per-session operation history for Phase 2, with persistence/snapshot hooks deferred to Phase 3.
- New tests should follow the existing focused JUnit style in `src/test/java`, adding OT transform edge-case coverage and three-user convergence scenarios to satisfy `QUAL-02`.

</code_context>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 02-real-time-ot-collaboration*
*Context gathered: 2026-03-27*
