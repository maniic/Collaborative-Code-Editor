# Project Research Summary

**Project:** Collaborative Code Editor Backend
**Domain:** Real-time collaborative editing with server-authoritative OT
**Researched:** 2026-03-26
**Confidence:** MEDIUM

## Executive Summary

A collaborative code editor backend is a well-understood but technically demanding system. The core challenge is implementing Operational Transformation (OT) correctly -- a pure algorithm problem where subtle bugs cause silent document divergence across users. Experts build this as a server-authoritative system where the server maintains a single linear operation history, clients transform against it, and all conflict resolution happens on the server side. This eliminates the need for TP2 (the much harder peer-to-peer transformation property) and keeps the architecture debuggable. The recommended stack is Java 21 with Spring Boot 3.x, PostgreSQL for durable storage, Redis for cross-instance coordination, and Docker for sandboxed code execution.

The recommended approach is to build the OT engine first as an isolated, pure-algorithm module with exhaustive convergence testing before wiring it to any I/O. This is the highest-risk component (estimated at 40% of total effort) and the one where bugs are hardest to detect -- documents diverge silently without errors. Everything else (WebSocket layer, persistence, execution sandbox, horizontal scaling) layers on top of a correct OT core and follows well-documented Spring Boot patterns.

The key risks are: (1) incorrect OT transform functions causing silent divergence -- mitigated by property-based fuzz testing with 3+ simulated users, (2) race conditions in concurrent operation processing -- mitigated by per-session single-threaded executors, and (3) Docker container escape or resource exhaustion from user-submitted code -- mitigated by comprehensive container hardening flags from day one. The architecture decision between STOMP and raw WebSocket should be made upfront during Phase 2 planning, as migrating mid-project requires a full rewrite of the message handling layer.

## Key Findings

### Recommended Stack

Java 21 on Spring Boot 3.x is the clear choice. Java 21's virtual threads handle concurrent WebSocket connections without thread pool exhaustion, while records, sealed interfaces, and pattern matching make the OT type hierarchy clean and exhaustive. Spring Boot manages the entire dependency chain (Web, WebSocket, Security, Data JPA, Data Redis) with minimal configuration.

**Core technologies:**
- **Java 21 (LTS):** Runtime -- virtual threads for WebSocket concurrency, sealed interfaces for OT operation hierarchy
- **Spring Boot 3.3.x:** Framework -- manages Web, WebSocket, Security, JPA, Redis dependencies cohesively
- **PostgreSQL 16.x:** Primary database -- operation log with B-tree indexing on (session_id, revision), user/session CRUD
- **Redis 7.x:** Coordination layer -- pub/sub for cross-instance relay, atomic INCR for revision counters, session state cache
- **docker-java 3.3.x:** Code execution -- typed Docker API for sandboxed container lifecycle
- **Gradle (Kotlin DSL):** Build tool -- faster incremental builds, type-safe configuration

**Critical version note:** Exact patch versions of jjwt, docker-java, and springdoc should be verified against Maven Central at project initialization. Spring WebFlux, Kafka, MongoDB, and any OT/CRDT library are explicitly excluded.

### Expected Features

**Must have (table stakes):**
- Real-time text synchronization via OT engine with conflict resolution
- Cursor presence and user join/leave awareness
- Session management (create/join/leave with shareable identifiers)
- JWT authentication for identity and edit attribution
- Document persistence surviving disconnections and restarts
- Reconnection handling with state resync
- Basic code execution with Docker sandboxing
- Execution output broadcasting to all session participants

**Should have (differentiators):**
- Operation history replay (timeline scrubbing -- portfolio value, and the op log already exists)
- Document snapshots for fast recovery (every 50 ops)
- Multi-language execution (Python + Java demonstrates extensibility)
- Horizontal scaling via Redis pub/sub (most portfolio projects are single-instance)
- Rate-limited execution queue with resource constraints
- Comprehensive API documentation (OpenAPI/Swagger -- for a backend-only project, the API IS the product)

**Defer (v2+):**
- Full IDE features (autocomplete, LSP), multi-file projects, syntax highlighting on backend, video/audio, OAuth, admin dashboard, persistent chat, AI code completion

### Architecture Approach

The system follows a server-authoritative OT model with a clear split between the hot path (operations flowing through the OT engine at high frequency) and cold path (session management, auth, execution). Six components with well-defined boundaries: Auth Module, Session Manager, WebSocket Handler, OT Engine, Presence Manager, Execution Engine, supported by Redis Layer and Snapshot Manager. The OT engine is the intellectual core -- a pure algorithm with no I/O dependencies, ideal for isolated development and testing.

**Major components:**
1. **OT Engine** -- Accepts client operations, transforms against concurrent ops using server history, applies to canonical document, broadcasts results. Single-threaded-per-session processing guarantees deterministic ordering.
2. **WebSocket Handler** -- STOMP-over-WebSocket with topic-per-session routing (`/topic/session.{id}.ops`, `.presence`, `.execution`). Connection lifecycle, auth handshake, dispatch to OT engine or presence manager.
3. **Session Manager** -- REST CRUD for coding sessions. Room lifecycle, participant tracking, document state recovery via snapshot + op replay.
4. **Execution Engine** -- Sandboxed Docker containers with full hardening (memory, CPU, PID limits, no network, read-only FS). Fixed thread pool with per-user rate limiting.
5. **Redis Layer** -- Three roles: pub/sub for cross-instance relay, atomic revision counters, session state cache. Fire-and-forget; PostgreSQL op log is the durable source of truth.
6. **Snapshot Manager** -- Periodic full-document snapshots every 50 operations. Bounds recovery time regardless of session age.

### Critical Pitfalls

1. **Incorrect OT transforms cause silent divergence** -- Exhaustive testing of all 6 transform pairs with multi-character boundary cases. Property-based fuzz testing with 3 simulated users, hundreds of random seeds. This is the single most important correctness guarantee.
2. **Race conditions in concurrent operation processing** -- Per-session single-threaded executor (NOT a global lock). Redis atomic INCR for revision counter. Concurrent integration tests with multiple threads asserting convergence.
3. **Docker container escape or resource exhaustion** -- Apply ALL hardening flags (memory, swap, CPU, PID limit, no network, read-only FS, tmpfs, nobody user, no-new-privileges, drop all capabilities). Hard timeout watchdog at application level.
4. **Unbounded operation history growth** -- Snapshots every 50 ops, keep only last ~200 ops in Redis, force full resync if client is 200+ revisions behind.
5. **WebSocket silent connection death** -- Application-level heartbeat every 15-30 seconds. Grace period for reconnection (~60 seconds). Ghost user cleanup on missed heartbeats.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Foundation and OT Engine
**Rationale:** OT is the highest-risk component (40% of effort) with the most subtle bugs. Build and test it in isolation before wiring to anything. The project skeleton, auth, and database schema are prerequisites for everything else.
**Delivers:** Working OT algorithm with exhaustive convergence tests, project skeleton with Gradle/Spring Boot, PostgreSQL schema via Flyway, JWT auth module, session CRUD REST API.
**Addresses:** Real-time text synchronization, conflict-free concurrent editing, authentication, session management, document persistence.
**Avoids:** Pitfall 1 (incorrect transforms), Pitfall 7 (multi-char position arithmetic), Pitfall 9 (snapshot race condition).

### Phase 2: Real-Time WebSocket Layer
**Rationale:** With a proven OT engine, wire it to WebSocket for the first end-to-end collaboration loop. This phase has a key architectural decision (STOMP vs raw WebSocket) that must be resolved upfront.
**Delivers:** WebSocket connection with STOMP routing, OT engine wired to real-time message flow, cursor presence broadcasting, user join/leave events, reconnection handling.
**Addresses:** Cursor presence, user join/leave awareness, reconnection handling.
**Avoids:** Pitfall 2 (concurrent processing race), Pitfall 5 (silent connection death), Pitfall 8 (cursor broadcast flooding), Pitfall 10 (JWT expiry mid-session), Pitfall 12 (STOMP vs raw WebSocket mismatch).

### Phase 3: Persistence and Resilience
**Rationale:** With real-time collaboration working, add durability. Async operation persistence, snapshots, and session recovery complete the reliability story.
**Delivers:** Async batched operation log persistence, periodic document snapshots, session recovery via snapshot + op replay.
**Addresses:** Document persistence (durable), reconnection with state recovery, operation history replay (differentiator).
**Avoids:** Pitfall 4 (unbounded history growth).

### Phase 4: Code Execution
**Rationale:** Independent of the collaboration layer. Can be built in parallel by a second developer if available, but sequenced here because it requires the session infrastructure from Phases 1-2.
**Delivers:** Docker-sandboxed code execution, resource limits and rate limiting, execution result broadcasting, multi-language support (Python + Java).
**Addresses:** Basic code execution, execution output visibility, resource limits, rate-limited execution queue, multi-language execution.
**Avoids:** Pitfall 3 (container escape/resource exhaustion), Pitfall 11 (Testcontainers vs execution Docker conflicts).

### Phase 5: Horizontal Scaling
**Rationale:** Everything works on a single instance. Now prove it scales. Redis pub/sub relay, atomic revision counters, and session state caching enable multi-instance deployment.
**Delivers:** Cross-instance operation relay via Redis pub/sub, Redis-backed atomic revision counters, session state caching, docker-compose with 2 app instances behind a load balancer.
**Addresses:** Horizontal scaling (differentiator), Redis caching.
**Avoids:** Pitfall 6 (Redis pub/sub message loss).

### Phase 6: Integration Testing and Polish
**Rationale:** Final phase ties everything together. Comprehensive integration tests, API documentation, and docker-compose for local development.
**Delivers:** Testcontainers integration tests for all components, OpenAPI/Swagger documentation, docker-compose.yml for full local stack, performance validation.
**Addresses:** Comprehensive API documentation (differentiator).

### Phase Ordering Rationale

- **OT engine first** because it is the highest-risk, highest-effort component and everything depends on its correctness. Testing it in isolation (pure algorithm, no I/O) is dramatically easier than debugging it through WebSocket layers.
- **WebSocket before persistence** because the real-time collaboration loop needs to work in-memory before adding async persistence complexity. In-memory-only sessions are acceptable for development.
- **Code execution after collaboration** because it is architecturally independent. It shares the session infrastructure but not the OT engine. It could theoretically be built in parallel.
- **Horizontal scaling last** because single-instance correctness must be proven first. Adding Redis relay on top of a broken OT engine compounds debugging difficulty.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1 (OT Engine):** The OT transform algorithm needs careful design. Research specific transform functions for Insert(pos, text) and Delete(pos, length) with all boundary cases. Consider referencing Jupiter OT or Google Wave's published transform tables.
- **Phase 2 (WebSocket Layer):** STOMP vs raw WebSocket decision needs prototyping. STOMP provides routing but may not fit OT's per-operation acknowledgment pattern. Evaluate Spring's `TextWebSocketHandler` as alternative.
- **Phase 4 (Code Execution):** docker-java API for container lifecycle, streaming output, and cleanup. Verify exact API calls for resource limit flags.

Phases with standard patterns (skip research-phase):
- **Phase 3 (Persistence):** Standard Spring Data JPA + async writes. Well-documented patterns.
- **Phase 5 (Horizontal Scaling):** Redis pub/sub with Spring Data Redis is well-documented. Lettuce client handles pub/sub natively.
- **Phase 6 (Integration/Polish):** Testcontainers + SpringDoc are standard tooling with extensive documentation.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM | Java 21 + Spring Boot 3.x is well-established. Exact library patch versions need Maven Central verification at project init. |
| Features | MEDIUM | Feature landscape is clear (well-established product category). Complexity estimates are reasonable but OT effort could be higher. |
| Architecture | HIGH | Server-authoritative OT is a proven pattern. Component boundaries are well-defined. Hot path vs cold path separation is standard. |
| Pitfalls | MEDIUM | Based on training knowledge of common OT implementation mistakes. Real-world edge cases may surface additional pitfalls during implementation. |

**Overall confidence:** MEDIUM -- The domain is well-understood and the architecture patterns are proven, but OT implementation correctness is inherently hard to predict. The largest uncertainty is in the OT transform functions themselves, which must be validated through exhaustive testing, not research.

### Gaps to Address

- **STOMP vs raw WebSocket:** Research recommends STOMP but flags potential mismatch with OT acknowledgment needs. Resolve with a prototype spike at the start of Phase 2.
- **Exact OT transform tables:** The transform function specifications for all operation pairs (insert-insert, insert-delete, delete-delete with multi-character ranges) need to be defined precisely before implementation. Reference published OT literature (Jupiter, Google Wave).
- **docker-java API surface:** Exact API calls for container creation with all security flags need verification against current docker-java documentation.
- **Library versions:** jjwt 0.12.x, docker-java 3.3.x, springdoc 2.x patch versions should be verified against Maven Central before project initialization.
- **Redis Streams vs pub/sub:** PITFALLS.md suggests considering Redis Streams for durability over raw pub/sub. This trade-off should be evaluated during Phase 5 planning.

## Sources

All research was conducted from model training knowledge (cutoff ~May 2025). No live web verification was performed.

### Primary (HIGH confidence)
- Spring Boot 3.x official patterns -- Web, WebSocket, Security, Data JPA, Data Redis
- Java 21 LTS features -- virtual threads, records, sealed interfaces, pattern matching
- PostgreSQL indexing and ACID properties for operation log storage
- Docker container security hardening flags

### Secondary (MEDIUM confidence)
- OT algorithm properties (TP1, server-authoritative linear history)
- Spring STOMP WebSocket configuration and topic routing
- Redis pub/sub semantics and Lettuce client behavior
- docker-java library API surface
- jjwt library API for JWT creation/validation

### Tertiary (LOW confidence)
- Exact library patch versions (jjwt 0.12.6, docker-java 3.3.6, springdoc 2.6.0) -- verify against Maven Central
- Spring Boot 3.3.x specific features -- verify against start.spring.io
- Testcontainers 1.19.x `@ServiceConnection` support -- verify against Testcontainers docs

---
*Research completed: 2026-03-26*
*Ready for roadmap: yes*
