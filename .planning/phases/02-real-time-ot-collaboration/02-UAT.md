---
status: complete
phase: 02-real-time-ot-collaboration
source:
  - 02-01-SUMMARY.md
  - 02-02-SUMMARY.md
  - 02-03-SUMMARY.md
started: 2026-03-29T03:37:31Z
updated: 2026-03-29T06:14:08Z
---

## Current Test

[testing complete]

## Tests

### 1. Authorized WebSocket Bootstrap
expected: Create or reuse a session with an ACTIVE participant, then open `/ws/sessions/{sessionId}` with `Authorization: Bearer <jwt>`. The socket should connect, the first server frame should be `document_sync` with `document`, `revision`, and `participants`, and the room should emit `participant_joined` with your `userId` and `email`.
result: pass

### 2. Unauthorized Handshake Rejection
expected: Try the same WebSocket connect without a bearer token, with an invalid JWT, or as a user who is not an ACTIVE participant. The handshake should be rejected and no collaboration stream should open.
result: pass

### 3. Insert Operation Ack and Canonical Broadcast
expected: With two active sockets in the same session, send a `submit_operation` INSERT payload from one client. The sender should receive `operation_ack` with the same `clientOperationId` and new canonical `revision`, then `operation_applied`, and the peer should receive `operation_applied` with the canonical INSERT fields.
result: pass

### 4. Stale Concurrent Insert Convergence
expected: Have two sockets submit INSERT operations against the same stale base revision at the same position. The server should transform the stale operation forward and both clients should settle on the same final document text and revision instead of diverging.
result: pass

### 5. Resync Required for Future Revision
expected: Send a `submit_operation` whose `baseRevision` is ahead of the server's canonical revision. The socket should stay open and receive `resync_required` containing the full current `document`, canonical `revision`, and a rejection reason.
result: pass

### 6. Invalid Operation Error Without Disconnect
expected: Send malformed JSON, an envelope with no `type`, or a `submit_operation` missing required fields like `operationType`. The server should reply with `operation_error`, and an otherwise authorized socket should remain connected after the error.
result: pass

### 7. Non-Active Participant Messages Are Closed
expected: After a participant is no longer ACTIVE, send any collaboration message on the existing socket. The server should send an authorization error and close the socket instead of applying the message.
result: pass

### 8. Presence Updates and Join or Leave Identity
expected: Send `update_presence` with a selection such as `{start: 7, end: 7}` for a caret or `{start: 5, end: 10}` for a range. Connected clients should receive `presence_updated` with the sender's `userId`, `email`, and selection, and remaining clients should receive `participant_left` when that participant disconnects.
result: pass

### 9. Presence Survives Canonical Edits and Throttles Rapid Broadcasts
expected: Store another user's selection, then apply canonical INSERT and DELETE operations before that range. The stored selection should shift or clamp to stay aligned with the document, and rapid presence updates inside the ~75 ms throttle window should not all broadcast even though the latest selection is retained.
result: pass

### 10. Three Participant Convergence
expected: With three active sockets, have all three send INSERT operations at position 0 against revision 0. Revision should advance to 3 and all participants should converge on the same deterministic document order rather than three different outcomes.
result: pass

## Summary

total: 10
passed: 10
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
