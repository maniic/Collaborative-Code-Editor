# Feature Landscape

**Domain:** Real-time collaborative code editor (backend)
**Researched:** 2026-03-26
**Confidence:** MEDIUM (well-established product category with stable feature expectations)

## Reference Products

VS Code Live Share, Replit Multiplayer, CodeSandbox Live, CoderPad, HackerRank CodePair, JetBrains Code With Me, Google Docs (collab UX reference), Firepad, Convergence.

---

## Table Stakes

Features users expect. Missing any makes the product feel broken.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Real-time text synchronization | Core promise. Edits must appear instantly for all users. | High | OT engine is the hardest piece |
| Conflict-free concurrent editing | Users expect simultaneous typing without overwrites. Google Docs set this expectation. | High | Correct OT transforms for all operation pairs |
| Cursor presence (who is where) | Spatial awareness of collaborators. Without it, users collide constantly. | Medium | Position broadcasting + colored cursor per user |
| User join/leave awareness | Must know who is in the session. Every collab tool shows participant list. | Low | WebSocket events, participant management |
| Session management (create/join/leave) | Entry point to collaboration. Rooms with shareable identifiers. | Low | REST CRUD + UUID identifiers |
| Authentication | Identity required for edit attribution, permissions, security. | Low-Medium | JWT email/password for v1 |
| Document persistence | Work must survive disconnections and restarts. | Medium | PostgreSQL persistence |
| Reconnection handling | Network drops happen. Users expect to reconnect and see current state. | Medium | Resync from server state; snapshot + op replay |
| Basic code execution | "Run" button is table stakes for a code editor with execution. | Medium-High | Docker sandboxing, resource limits, stdout/stderr |
| Execution output visibility | All participants should see execution results. | Low | Broadcast over WebSocket |

## Differentiators

Features that demonstrate engineering depth. Not universally expected but valued.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Operation history replay | Scrub through full edit history like a timeline. Portfolio gold. | Medium | Already storing full op log |
| Document snapshots for fast recovery | Load nearest snapshot + replay delta instead of replaying thousands of ops. | Medium | Periodic snapshotting every 50 ops |
| Multi-language execution | Python + Java shows extensible architecture. | Medium | Each language = Docker image + adapter |
| Horizontal scaling (multi-instance) | Proving architecture works across 2-3 instances. Most portfolio projects are single-instance. | Medium-High | Redis pub/sub relay, atomic counters |
| Rate-limited execution queue | Prevents abuse, demonstrates production-grade thinking. | Low-Medium | Bounded thread pool + per-user limits |
| Execution resource limits | Memory caps, CPU limits, no-network, timeouts. Shows security consciousness. | Medium | Docker resource constraints |
| Selection/highlight sharing | Beyond cursor position, show selected text ranges. | Low-Medium | Selection start/end broadcast |
| Typing indicators | "User X is typing..." lightweight presence signal. | Low | Debounced activity broadcast |
| Session language setting | Each session has a language determining execution runtime. | Low | Clean UX detail |
| Comprehensive API documentation | For backend-only project, the API IS the product. | Low | OpenAPI/Swagger + WebSocket docs |

## Anti-Features

Features to explicitly NOT build.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Full IDE features (autocomplete, LSP) | Enormous complexity, distracts from OT + collab story | Frontend can integrate Monaco/CodeMirror with LSP |
| File/project management (multi-file) | Multiplies OT complexity by N files | Single document per session |
| Syntax highlighting on backend | Frontend concern, wastes bandwidth | Send plain text, let frontend editor handle |
| Video/audio chat | Completely different domain (WebRTC) | Third-party integration if ever needed |
| OAuth/social login | Adds complexity without demonstrating anything new | JWT email/password |
| CRDT-based resolution | Already decided on OT | Document the rationale |
| Peer-to-peer architecture | Vastly harder, less debuggable | Server-authoritative OT |
| Kafka/heavy message brokers | Overkill for 2-3 instances | Redis pub/sub |
| Admin dashboard | Not core value | Manage via API/database |
| Persistent chat | Not collaborative editing | Skip for v1 |
| AI code completion | Orthogonal to OT problem, would dominate narrative | Out of scope |

## Feature Dependencies

```
Authentication
  └─> Session Management
        ├─> WebSocket Connection
        │     ├─> OT Engine
        │     │     ├─> Document Persistence (op log)
        │     │     │     └─> Snapshots
        │     │     └─> Conflict Resolution (core transforms)
        │     ├─> Cursor Presence
        │     ├─> User Join/Leave Events
        │     └─> Execution Result Broadcasting
        └─> Code Execution
              ├─> Docker Sandbox
              ├─> Resource Limits
              ├─> Rate Limiting
              └─> Execution Queue

Redis Pub/Sub (independent infrastructure)
  ├─> Cross-Instance WebSocket Relay
  ├─> Atomic Revision Counter
  └─> Session State Cache

Reconnection Handling
  ├─> Requires: Snapshots + Op Log
  ├─> Requires: WebSocket reconnection protocol
  └─> Requires: Revision tracking
```

## MVP Phasing Recommendation

### Phase 1 — Core Collaboration Loop:
Auth, Session CRUD, WebSocket, OT engine with conflict resolution, Document persistence

### Phase 2 — Collaboration Polish:
Cursor presence, Join/leave events, Reconnection handling, Snapshots

### Phase 3 — Code Execution:
Docker sandbox, Execution output broadcasting, Resource limits + rate limiting

### Phase 4 — Horizontal Scale:
Redis pub/sub cross-instance relay, Redis caching, Atomic revision counters

## Complexity Budget

| Feature Area | Estimated Effort | Risk Level |
|-------------|-----------------|------------|
| OT Engine (transforms + server authority) | 40% | HIGH — edge cases in transforms, convergence bugs are subtle |
| WebSocket Infrastructure | 15% | MEDIUM — connection management, reconnection, routing |
| Docker Execution Sandbox | 15% | MEDIUM — container lifecycle, security hardening |
| Redis Scaling Layer | 10% | LOW-MEDIUM — well-documented patterns |
| Auth + Session CRUD | 10% | LOW — standard Spring Security + REST |
| Persistence + Snapshots | 10% | LOW — standard JPA + periodic snapshot logic |

**The OT engine will consume 40% of development time.** Three-user convergence tests are essential — two-user tests miss critical interleaving bugs.
