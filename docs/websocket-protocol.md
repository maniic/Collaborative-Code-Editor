# WebSocket protocol

Connect to the collaboration WebSocket after joining a session via REST.

**Endpoint:** `ws://localhost:8080/ws/sessions/{sessionId}`

## Authentication

The handshake accepts the access token either as a header:

```http
Authorization: Bearer <access_token>
```

or, because browsers cannot set headers on a WebSocket handshake, as a query
parameter (RFC 6750 §2.3) — which is what the built-in web client uses:

```
ws://localhost:8080/ws/sessions/{sessionId}?access_token=<access_token>
```

The user must be an ACTIVE participant of the session (joined via
`/api/sessions/join`, or the session owner). A rejected handshake answers with
an explicit status rather than upgrading:

| Status | Reason |
|--------|--------|
| `400` | Malformed session id in the path |
| `401` | Missing, malformed, or expired token |
| `403` | Valid token, but not an active participant of that session |

Messages are JSON envelopes of the form `{"type": ..., "payload": {...}}`.

---

## Client to server

### `submit_operation`

Submit a document edit.

```json
{
  "type": "submit_operation",
  "payload": {
    "clientOperationId": "op-123",
    "baseRevision": 42,
    "operationType": "INSERT",
    "position": 10,
    "text": "hello",
    "length": null
  }
}
```

Or a delete:

```json
{
  "type": "submit_operation",
  "payload": {
    "clientOperationId": "op-124",
    "baseRevision": 42,
    "operationType": "DELETE",
    "position": 5,
    "text": null,
    "length": 3
  }
}
```

`baseRevision` is the revision the operation was composed against. The server
transforms it against every canonical operation committed after that revision.
A `baseRevision` ahead of the canonical revision is rejected with
`resync_required`.

### `update_presence`

Broadcast cursor/selection position. A caret is a zero-length range.

```json
{
  "type": "update_presence",
  "payload": {
    "selection": { "start": 10, "end": 15 }
  }
}
```

Presence broadcasts are throttled server-side (75 ms per participant); the
latest selection is always stored, even when a broadcast is suppressed.

---

## Server to client

| Event | When sent |
|-------|-----------|
| `document_sync` | On connect — current document, revision, and participant roster |
| `operation_ack` | After the server commits the submitting client's operation |
| `operation_applied` | After the server commits any operation — broadcast to every connected client in the room, including the sender |
| `operation_error` | The submitted operation was rejected (malformed envelope, missing fields, unknown type) |
| `resync_required` | The server cannot reconcile the client's state; reconnect for a fresh sync |
| `participant_joined` | A participant connected to the room |
| `participant_left` | A participant disconnected from the room |
| `presence_updated` | A participant's cursor/selection changed |
| `execution_updated` | An execution changed state: `QUEUED → RUNNING → COMPLETED / FAILED / TIMED_OUT`, or `REJECTED` |

### `document_sync`

```json
{
  "type": "document_sync",
  "payload": {
    "document": "def hello():\n    print('Hello')\n",
    "revision": 42,
    "participants": [
      { "userId": "550e8400-e29b-41d4-a716-446655440000", "email": "user@example.com" }
    ]
  }
}
```

### `operation_applied`

```json
{
  "type": "operation_applied",
  "payload": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "revision": 43,
    "operationType": "INSERT",
    "position": 10,
    "text": "hello",
    "length": null,
    "clientOperationId": "op-123"
  }
}
```

`clientOperationId` is echoed so a client can recognise its own operation.
Recognise it by that id, **not** by `userId`: one user may hold several
connections to the same room (two browser tabs), and each must apply the
other's operations rather than discard them as its own.

### `execution_updated`

```json
{
  "type": "execution_updated",
  "payload": {
    "executionId": "7da45499-805f-4d6d-b909-128457bb0261",
    "requestedByUserId": "550e8400-e29b-41d4-a716-446655440000",
    "requestedByEmail": "user@example.com",
    "language": "PYTHON",
    "sourceRevision": 42,
    "status": "COMPLETED",
    "stdout": "Hello\n",
    "stderr": "",
    "exitCode": 0,
    "createdAt": "2026-03-30T03:00:00Z",
    "startedAt": "2026-03-30T03:00:01Z",
    "finishedAt": "2026-03-30T03:00:02Z",
    "message": "Execution completed successfully."
  }
}
```
