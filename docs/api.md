# REST API

All endpoints require a bearer token (`Authorization: Bearer <access_token>`)
except `/api/auth/register` and `/api/auth/login`. Tokens are issued at login
and rotated at refresh.

## Auth

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/register` | None | Register a new user |
| `POST` | `/api/auth/login` | None | Login; returns access token + sets refresh cookie |
| `POST` | `/api/auth/refresh` | Refresh cookie | Rotate refresh token; returns new access token |

**Register**

```http
POST /api/auth/register
Content-Type: application/json

{"email": "user@example.com", "password": "secret123"}
```

Response: `201 Created` (no body)

**Login**

```http
POST /api/auth/login
Content-Type: application/json

{"email": "user@example.com", "password": "secret123"}
```

Response: `200 OK`

```json
{
  "accessToken": "<jwt>",
  "expiresInSeconds": 900,
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com"
}
```

A `ccd_refresh_token` HttpOnly secure cookie is also set. The refresh token is
valid for 30 days and rotates on each use; reuse of a rotated token is detected
and rejected.

**Refresh**

```http
POST /api/auth/refresh
Cookie: ccd_refresh_token=<token>
```

Response: same shape as login. A new `ccd_refresh_token` cookie is set.

---

## Sessions

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/sessions` | Bearer | Create a session; returns invite code |
| `GET` | `/api/sessions` | Bearer | List sessions the authenticated user participates in |
| `POST` | `/api/sessions/join` | Bearer | Join a session by invite code |
| `POST` | `/api/sessions/{sessionId}/leave` | Bearer | Leave a session |

**Create session**

```http
POST /api/sessions
Authorization: Bearer <token>
Content-Type: application/json

{"language": "PYTHON"}
```

Response: `201 Created`

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "inviteCode": "AB3CDEF7",
  "language": "PYTHON",
  "ownerUserId": "550e8400-e29b-41d4-a716-446655440000",
  "participantCap": 12,
  "activeParticipants": 1,
  "createdAt": "2026-03-30T03:00:00Z"
}
```

Session language is **immutable** after creation. Supported values: `PYTHON`, `JAVA`.

**Join session**

```http
POST /api/sessions/join
Authorization: Bearer <token>
Content-Type: application/json

{"inviteCode": "AB3CDEF7"}
```

Invite codes are case-normalized and use the charset `[A-Z2-9]` (excludes 0, 1,
I, O). Join is idempotent for already-active participants.

---

## Execution

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/sessions/{sessionId}/executions` | Bearer | Enqueue code execution for the session |

```http
POST /api/sessions/550e8400-e29b-41d4-a716-446655440000/executions
Authorization: Bearer <token>
```

Response: `202 Accepted`

```json
{
  "executionId": "...",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "language": "PYTHON",
  "sourceRevision": 42,
  "status": "QUEUED"
}
```

The execution captures the current canonical room document and language at
enqueue time, not the client's local state. Results are delivered
asynchronously via the WebSocket `execution_updated` event. A per-session
cooldown of 5 seconds applies between executions.

**Java execution constraint:** source must be a single-file package-less `Main`
entrypoint (`class Main { public static void main(String[] args) {...} }`) with
no package declaration.

---

## Error responses

Errors carry a consistent JSON body:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Session not found for invite code: ZZZZZZZZ",
  "timestamp": "2026-03-30T03:00:00Z"
}
```

Bean-validation failures add a `fieldErrors` map so a client can attach messages
to the offending inputs:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "email must be a well-formed email address; password size must be between 8 and 128",
  "fieldErrors": {
    "email": "must be a well-formed email address",
    "password": "size must be between 8 and 128"
  },
  "timestamp": "2026-03-30T03:00:00Z"
}
```

| Status | When |
|--------|------|
| `400` | Validation failure, or an unsupported language |
| `401` | Missing, malformed, or expired bearer token; bad credentials |
| `403` | Authenticated but not a participant of the target session |
| `404` | Unknown session or invite code |
| `409` | Duplicate email on register; session at participant cap |
| `429` | Execution requested inside the cooldown window |
| `503` | Execution queue full |
