# Architecture Patterns

**Domain:** Real-time collaborative code editor backend (OT-based, server-authoritative)
**Researched:** 2026-03-26
**Confidence:** HIGH (well-established domain with proven patterns)

## Recommended Architecture

The system follows a **server-authoritative OT model** with six distinct components connected through WebSocket (real-time path) and REST (management path). The architecture splits cleanly into a **hot path** (operations flowing through OT at high frequency) and a **cold path** (session management, auth, execution).

```
                           +-------------------+
                           |   API Gateway /    |
                           |   Auth Filter      |
                           +--------+----------+
                                    |
                  +-----------------+-----------------+
                  |                                   |
          [REST Controllers]                [WebSocket Handler]
          Session CRUD, Auth                 STOMP /topic/session.{id}
                  |                                   |
                  |                          +--------+--------+
                  |                          |                 |
                  |                   [OT Engine]      [Presence Manager]
                  |                   Transform &      Cursor positions,
                  |                   Apply ops        user join/leave
                  |                          |
                  |                   [Session State]
                  |                   In-memory doc +
                  |                   revision counter
                  |                          |
          +-------+-------+          +------+------+
          |               |          |             |
     [PostgreSQL]    [Execution]  [Redis]     [Snapshot
      Users,         Engine       Cache,       Manager]
      Sessions,      Docker       Pub/Sub,     Periodic
      Op Log         sandbox      Counters     persistence
```

### Component Boundaries

| Component | Responsibility | Communicates With | Package |
|-----------|---------------|-------------------|---------|
| **Auth Module** | JWT token lifecycle: register, login, refresh, validate. Spring Security filter chain. | All inbound requests (filter), PostgreSQL (user store) | `auth` |
| **Session Manager** | CRUD for coding sessions. Room lifecycle (create, join, leave, close). Participant tracking. | REST controllers, WebSocket handler, PostgreSQL, Redis (session cache) | `session` |
| **WebSocket Handler** | Connection lifecycle, STOMP message routing, authentication handshake, message dispatch to OT engine or presence manager. | Auth module (token validation), OT engine, Presence manager, Redis pub/sub | `websocket` |
| **OT Engine** | The intellectual core. Accepts client operations, transforms against concurrent ops using server history, applies to canonical document, broadcasts transformed ops. | WebSocket handler (input/output), Session state (document + history), PostgreSQL (op log persistence) | `ot` |
| **Presence Manager** | Tracks cursor positions and selections per user per session. Transforms cursor positions when OT ops are applied. Broadcasts presence updates. | WebSocket handler, OT engine (cursor transform on op apply), Redis pub/sub (cross-instance) | `websocket` (sub-concern) |
| **Execution Engine** | Receives code execution requests, spins up sandboxed Docker containers, streams output back. Rate-limited, resource-constrained. | REST or WebSocket (trigger), Docker daemon (container lifecycle), PostgreSQL (execution history) | `execution` |
| **Redis Layer** | Three roles: (1) pub/sub for cross-instance operation relay, (2) cache for active session state, (3) atomic revision counters. | WebSocket handler, OT engine, Session manager | `redis` |
| **Snapshot Manager** | Periodic document snapshots (every N operations) for fast session recovery. Truncates replayable op history. | OT engine (trigger on op count), PostgreSQL (snapshot store) | `snapshot` |

### Data Flow

#### Hot Path: Operation Flow (the critical real-time path)

```
1. Client sends operation {type: INSERT/DELETE, position, text, revision}
       |
2. WebSocket Handler receives, authenticates, routes to OT Engine
       |
3. OT Engine checks client revision vs server revision
       |
   3a. If client revision == server revision:
       Apply operation directly to document
       |
   3b. If client revision < server revision (stale client):
       Fetch ops from server history since client's revision
       Transform client op against each concurrent server op sequentially
       Apply transformed operation to document
       |
4. Increment server revision counter (atomic via Redis)
       |
5. Persist operation to PostgreSQL op log (async, buffered)
       |
6. Broadcast transformed operation to ALL clients in session
   (including sender, so sender can confirm/adjust)
       |
   6a. Same instance: direct WebSocket send
   6b. Other instances: Redis pub/sub -> their WebSocket handlers
       |
7. Every 50 operations: trigger Snapshot Manager
       |
8. Presence Manager transforms all cursor positions in session
   based on the applied operation, broadcasts updated positions
```

#### Cold Path: Session Lifecycle

```
1. Client POST /api/sessions (create) -> Session Manager -> PostgreSQL
2. Client POST /api/sessions/{id}/join -> Session Manager
       -> Load latest snapshot from PostgreSQL
       -> Replay ops since snapshot
       -> Reconstruct document state
       -> Return session metadata + current document
3. Client opens WebSocket to /ws -> STOMP CONNECT with JWT
       -> Auth filter validates
       -> Subscribe to /topic/session.{id}.ops
       -> Subscribe to /topic/session.{id}.presence
       -> Subscribe to /topic/session.{id}.execution
4. Client leaves -> unsubscribe, update participant list, broadcast leave event
```

#### Execution Path

```
1. Client sends execution request (via WebSocket or REST)
       |
2. Rate limiter checks: 1 execution per user per 5 seconds
       |
3. Execution Engine pulls current document content from Session State
       |
4. Creates Docker container:
   - Language-specific image (python:3-slim, eclipse-temurin:17)
   - Resource limits: 256MB RAM, 0.5 CPU
   - No network, read-only FS, non-root user
   - 10-second timeout
       |
5. Writes code to container, executes, captures stdout/stderr
       |
6. Container destroyed, result persisted to PostgreSQL
       |
7. Result broadcast to session via WebSocket
```

## Patterns to Follow

### Pattern 1: Server-Authoritative OT with Linear History

The server maintains a single linear sequence of operations. Each operation gets a monotonically increasing revision number. Clients always transform against the server's canonical history. Only requires TP1 (not TP2), significantly simpler than peer-to-peer OT.

### Pattern 2: STOMP Over WebSocket with Topic-Per-Session

Use Spring's STOMP support with destination patterns like `/topic/session.{sessionId}.ops` and `/topic/session.{sessionId}.presence`. Spring's `@MessageMapping` and `SimpMessagingTemplate` handle the plumbing.

### Pattern 3: Redis Pub/Sub for Cross-Instance Relay

When an operation is applied on instance A, publish to a Redis channel. Instance B subscribes and forwards to local WebSocket clients. PostgreSQL op log is the durable store — Redis pub/sub is fire-and-forget.

### Pattern 4: Snapshot + Replay for Session Recovery

Every 50 operations, save full document text as a snapshot. Recovery loads latest snapshot + replays operations since. Bounds recovery time regardless of session age.

### Pattern 5: Bounded Thread Pool for Code Execution

Fixed-size thread pool processes execution requests with bounded queue. Combined with per-user rate limiting for backpressure.

## Anti-Patterns to Avoid

| Anti-Pattern | Why Bad | Instead |
|-------------|---------|---------|
| Processing OT on WebSocket thread | Blocks other connections, latency spikes | Dedicated OT processing queue per session |
| Shared mutable document state without ordering | OT correctness depends on deterministic order | Single-writer pattern: `ConcurrentHashMap<String, SingleThreadExecutor>` per session |
| Synchronous operation persistence | DB write latency impacts real-time feel | Async batch-write to PostgreSQL (every 100ms or 10 ops) |
| Transforming cursors separately from operations | Cursor positions become stale/wrong | Transform cursors through same OT transform functions |
| Docker containers without cleanup | Zombie containers accumulate | Always set timeout, register cleanup hook, periodic sweep |

## Scalability Considerations

| Concern | At 1-5 users | At 50 users | At 500+ users |
|---------|-------------|-------------|---------------|
| OT processing | Single thread, trivial | Single-thread-per-session holds | Needs session affinity |
| WebSocket connections | Single instance | 2-3 instances, Redis relay | Load balancer with sticky sessions |
| Document state | All in memory | All in memory | LRU eviction of inactive sessions |
| Operation persistence | Sync writes fine | Async batching recommended | Async required, partitioned tables |
| Code execution | Thread pool of 2 | Thread pool of 4-8 | Dedicated execution workers |

## Suggested Build Order

```
Phase 1: Foundation
  Auth Module + PostgreSQL schema + Flyway + Project skeleton

Phase 2: Core Collaboration
  OT Engine (pure algorithm, no I/O) — hardest part, build and test in isolation
  Session Manager (REST CRUD)

Phase 3: Real-Time Layer
  WebSocket Handler + STOMP config
  Wire OT Engine to WebSocket
  Presence Manager

Phase 4: Persistence and Resilience
  Operation log persistence (async)
  Snapshot Manager
  Session recovery (snapshot + replay)

Phase 5: Horizontal Scaling
  Redis caching, atomic revision counters, pub/sub relay

Phase 6: Code Execution
  Docker sandbox, execution thread pool + rate limiting, result broadcasting

Phase 7: Integration and Polish
  Integration tests (Testcontainers), docker-compose.yml, API documentation
```

**Key insight:** OT engine is the riskiest component — build it first and test exhaustively before wiring to anything. It's a pure algorithm with no I/O dependencies, ideal for isolated development and unit testing.

## Key Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| STOMP over raw WebSocket | Topic routing, subscription management, Spring Security integration for free |
| In-memory document state with Redis backup | Redis round-trip per keystroke would kill latency; in-memory is primary, Redis for coordination |
| Single-threaded-per-session with Redis atomic counters | Any instance can process any session; Redis INCR guarantees global ordering |
| Multi-character operations | Handles paste/bulk-delete naturally, reduces message volume |
