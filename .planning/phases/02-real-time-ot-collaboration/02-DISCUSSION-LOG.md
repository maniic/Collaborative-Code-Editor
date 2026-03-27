# Phase 2: Real-Time OT Collaboration - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-03-27
**Phase:** 02-real-time-ot-collaboration
**Areas discussed:** Protocol, Sync, Recovery, Presence

---

## Protocol

### Q1. Channel framing

| Option | Description | Selected |
|--------|-------------|----------|
| Raw JSON over a Spring WebSocket endpoint | Keeps the OT contract fully under project control; no existing WebSocket or STOMP pattern exists in the repo. | ✓ |
| STOMP destinations/topics | Conventional pub/sub shape, but adds broker-style concepts this phase does not otherwise need. | |
| Leave it to the planner | Defer the transport framing decision. | |

**User's choice:** Raw JSON over a Spring WebSocket endpoint
**Notes:** Chosen to keep the collaboration protocol explicit and lightweight.

### Q2. Session attachment

| Option | Description | Selected |
|--------|-------------|----------|
| Session-scoped endpoint | Connect to a room-specific endpoint keyed by canonical `sessionId`; aligns with the existing session model. | ✓ |
| Generic endpoint plus join message | Connect once, then send a join message naming the session; adds another protocol step immediately. | |
| Leave it to the planner | Defer the room-binding choice. | |

**User's choice:** Session-scoped endpoint
**Notes:** Keeps each socket bound to one room and preserves `sessionId` as the canonical room identifier.

### Q3. WebSocket authentication

| Option | Description | Selected |
|--------|-------------|----------|
| JWT during the WebSocket handshake | Reuses the existing bearer-token model and rejects unauthenticated sockets before collaboration starts. | ✓ |
| Open handshake, then authenticate in the first message | Adds a second auth path and more protocol edge cases. | |
| Leave it to the planner | Defer the auth timing choice. | |

**User's choice:** JWT during the WebSocket handshake
**Notes:** Keeps collaboration aligned with the existing fail-closed security model.

### Q4. Envelope shape

| Option | Description | Selected |
|--------|-------------|----------|
| Typed envelopes with event names and payload objects | Example shape: `{ "type": "...", "payload": { ... } }`; clearer for frontend integration and testing. | ✓ |
| Looser ad hoc JSON per message kind | Slightly faster to start, but harder to validate and document. | |
| Leave it to the planner | Defer the message-shape choice. | |

**User's choice:** Typed envelopes with event names and payload objects
**Notes:** Explicit contract preferred over implicit JSON variants.

---

## Sync

### Q1. Connect bootstrap

| Option | Description | Selected |
|--------|-------------|----------|
| Full current document + current revision + active participants | Deterministic bootstrap for new or reconnecting clients; does not depend on persistence yet. | ✓ |
| Revision + recent operations only | Requires clients to reconstruct state from an assumed baseline. | |
| Leave it to the planner | Defer the bootstrap model. | |

**User's choice:** Full current document + current revision + active participants
**Notes:** Chosen as the default room bootstrap and the likely resync model.

### Q2. Operation acknowledgement

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit server ack with assigned canonical revision | The client submits a base revision and receives the canonical accepted revision back. | ✓ |
| No dedicated ack; infer success from broadcast | Fewer message types, but weaker sender-side certainty. | |
| Leave it to the planner | Defer the ack behavior. | |

**User's choice:** Explicit server ack with assigned canonical revision
**Notes:** Keeps the client/server OT loop explicit.

### Q3. Successful op fan-out

| Option | Description | Selected |
|--------|-------------|----------|
| Broadcast the canonical transformed operation to all participants, including the sender | Everyone consumes the same server-approved operation stream. | ✓ |
| Ack only to sender, broadcast only to other participants | Slightly less traffic, but introduces separate sender and receiver code paths. | |
| Leave it to the planner | Defer the broadcast behavior. | |

**User's choice:** Broadcast the canonical transformed operation to all participants, including the sender
**Notes:** Selected to keep every client on the same canonical stream.

### Q4. Initial document state

| Option | Description | Selected |
|--------|-------------|----------|
| Empty document at revision 0 | Cleanest OT baseline for Phase 2. | ✓ |
| Language-specific starter template | Adds product behavior not otherwise defined in this phase. | |
| Leave it to the planner | Defer the starting-state choice. | |

**User's choice:** Empty document at revision 0
**Notes:** Avoids introducing templates before later phases.

---

## Recovery

### Q1. Stale operation handling

| Option | Description | Selected |
|--------|-------------|----------|
| Server transforms stale ops against missed canonical ops and applies them if still valid | Matches the project’s server-authoritative OT decision and keeps normal concurrency on the happy path. | ✓ |
| Reject stale ops and force resync first | Simpler, but weakens the OT value for ordinary concurrent edits. | |
| Leave it to the planner | Defer stale-op behavior. | |

**User's choice:** Transform stale ops against missed canonical ops and apply if still valid
**Notes:** Chosen to preserve convergence under normal concurrent editing.

### Q2. Invalid operation handling

| Option | Description | Selected |
|--------|-------------|----------|
| Reject just that operation with an explicit error event and keep the socket open | Precise failure handling without forcing a reconnect. | ✓ |
| Close the socket on any invalid operation | Stricter, but too harsh for recoverable client bugs or race conditions. | |
| Leave it to the planner | Defer invalid-op behavior. | |

**User's choice:** Reject just that operation with an explicit error event and keep the socket open
**Notes:** Keeps collaboration debuggable and resilient.

### Q3. Non-participant behavior

| Option | Description | Selected |
|--------|-------------|----------|
| Reject collaboration messages with an authorization error and close the socket | Enforces active membership strictly once the user no longer belongs to the room. | ✓ |
| Reject messages but keep the socket open | Leaves a stale room connection alive even though edits are forbidden. | |
| Leave it to the planner | Defer non-participant behavior. | |

**User's choice:** Reject collaboration messages with an authorization error and close the socket
**Notes:** Matches the strict session-membership model from Phase 1.

### Q4. Unrecoverable desync handling

| Option | Description | Selected |
|--------|-------------|----------|
| Send a resync-required event with the latest full document and revision | Reuses the selected full-state bootstrap model and gives the client a direct recovery path. | ✓ |
| Close the socket and force manual reconnect | Simpler server behavior, but rougher client recovery. | |
| Leave it to the planner | Defer desync recovery behavior. | |

**User's choice:** Send a resync-required event with the latest full document and revision
**Notes:** Makes recovery explicit instead of forcing the client to guess.

---

## Presence

### Q1. Cursor richness

| Option | Description | Selected |
|--------|-------------|----------|
| Caret-only position | Smallest payload and enough to show where each user is typing. | |
| Selection range presence | Represents a caret as `start == end` and supports selections without changing the protocol later. | ✓ |
| Leave it to the planner | Defer cursor richness. | |

**User's choice:** Selection range presence
**Notes:** Locks in one extensible presence shape for both carets and selections.

### Q2. Participant identity in presence events

| Option | Description | Selected |
|--------|-------------|----------|
| User ID only | Minimal, but not very frontend-friendly. | |
| User ID + email | Reuses already-available identity data and gives the frontend a human-readable label. | ✓ |
| Leave it to the planner | Defer identity payload shape. | |

**User's choice:** User ID + email
**Notes:** Email is sufficient identity for Phase 2 without introducing profile work.

### Q3. Join/leave delivery

| Option | Description | Selected |
|--------|-------------|----------|
| Explicit `participant_joined` / `participant_left` events | Clear protocol and easy client testing; no list diffing required. | ✓ |
| Full participant-list snapshots only | Clients infer who changed by diffing snapshots. | |
| Leave it to the planner | Defer join/leave event shape. | |

**User's choice:** Explicit `participant_joined` / `participant_left` events
**Notes:** Explicit event names are preferred over inference.

### Q4. Cursor update delivery

| Option | Description | Selected |
|--------|-------------|----------|
| Broadcast on every received cursor update | Immediate behavior, but potentially noisy. | |
| Broadcast immediate updates, with throttling left to server implementation | Keeps real-time semantics while leaving the exact rate limit flexible. | ✓ |
| Leave it to the planner | Defer cursor update pacing. | |

**User's choice:** Broadcast immediate updates, with throttling left to server implementation
**Notes:** Locks real-time behavior while preserving implementation flexibility.

---

## the agent's Discretion

- Exact endpoint path naming and handshake header/query transport.
- Exact JSON field names inside payloads beyond the `type` + `payload` envelope.
- Exact WebSocket error-code and close-status mapping.
- Exact cursor-update throttling interval and debounce strategy.

## Deferred Ideas

None.
