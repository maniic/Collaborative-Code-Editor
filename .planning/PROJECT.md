# Collaborative Code Editor

## What This Is

A real-time collaborative code editor backend built in Java with Spring Boot 3. Multiple users join coding sessions via WebSocket, edit the same document simultaneously with conflict-free resolution using Operational Transform, and execute code together in Docker-sandboxed containers. Designed as a portfolio piece showcasing distributed systems, real-time algorithms, and containerized execution — with a full frontend planned for a future milestone.

## Core Value

The OT engine must guarantee document convergence — when multiple users edit simultaneously, every participant ends up with the same document, every time, with zero conflicts.

## Current State

Phase 01.1 is complete. The auth and session baseline now fails closed on insecure JWT configuration, keeps refresh/session mutations deterministic under concurrency, and has a green Docker-backed verification path. Phase 2 is unblocked.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

(None yet — ship to validate)

### Active

<!-- Current scope. Building toward these. -->

- [ ] JWT authentication (register, login, token refresh)
- [ ] REST API for session CRUD (create, join, leave, list)
- [ ] WebSocket connection for real-time operation broadcasting
- [ ] OT engine implemented from scratch — insert/insert, insert/delete, delete/delete transforms
- [ ] Multi-character operations (position + string, supports paste and bulk delete)
- [ ] Server-authoritative model with revision counter and operation history transform
- [ ] Cursor presence broadcasting (show where each user is typing)
- [ ] User join/leave events over WebSocket
- [ ] PostgreSQL persistence for users, sessions, operation log, snapshots, execution history
- [ ] Document snapshots every 50 operations for fast recovery
- [ ] Redis pub/sub for cross-instance WebSocket relay (2-3 instance horizontal scaling)
- [ ] Redis caching for active session state and atomic revision counter
- [ ] Sandboxed code execution via Docker containers (docker-java library)
- [ ] Execution resource limits: 256MB memory, 0.5 CPU, no network, read-only FS, non-root, 10s timeout
- [ ] Python and Java language support for execution
- [ ] Bounded thread pool execution queue with rate limiting (1 execution/user/5 seconds)
- [ ] Flyway database migrations
- [ ] Comprehensive JUnit 5 tests — OT transform edge cases + 3-user convergence test
- [ ] Integration tests with Testcontainers
- [ ] docker-compose.yml for full stack (app, PostgreSQL, Redis)
- [ ] README with architecture diagram, setup instructions, API docs, and design decisions

### Out of Scope

- Frontend application — backend-only for this milestone, frontend planned later
- CRDT-based conflict resolution — OT chosen deliberately (see Key Decisions)
- Kafka/message broker — Redis pub/sub sufficient for 2-3 instance scale
- Languages beyond Python and Java for execution — extensible later
- OAuth/social login — JWT email/password sufficient for v1
- File/project management — single document per session
- Syntax highlighting or language server features — frontend concern
- Production deployment infrastructure (Kubernetes, CI/CD pipelines)

## Context

This is a greenfield portfolio project intended to demonstrate deep understanding of:
- Distributed systems concepts (consistency, conflict resolution, horizontal scaling)
- Real-time communication (WebSocket, pub/sub)
- Algorithm implementation (OT from scratch, not using a library)
- Security-conscious design (sandboxed execution, JWT auth, resource limits)

The backend is designed with a future React/Vue frontend in mind — API contracts and WebSocket message formats should be clean and well-documented for frontend integration.

Session model: each session is a room identified by UUID, with a programming language setting and a participant list. The full operation log is persisted (session ID, revision number, operation type, position, text) enabling complete history replay.

## Constraints

- **Tech stack**: Java 17+, Spring Boot 3, PostgreSQL, Redis, Docker — no negotiation
- **OT implementation**: From scratch, no OT libraries — this is the intellectual centerpiece
- **Execution sandbox**: Docker containers only — no WASM, no in-process execution
- **Scale target**: 2-3 server instances (prove architecture, not stress-test)
- **Package structure**: auth, session, websocket, ot, execution, redis, snapshot packages

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| OT over CRDT | OT is more intuitive for linear document editing, server-authoritative model simplifies consistency, and implementing it from scratch demonstrates deeper algorithmic understanding | — Pending |
| Redis over Kafka | Kafka is overkill for 2-3 instances; Redis pub/sub is lightweight, already needed for caching/counters, and reduces infrastructure complexity | — Pending |
| Docker for sandboxing | Strongest isolation model, proven security boundary, supports any language runtime, resource limits are first-class | — Pending |
| Server-authoritative OT | Server maintains canonical revision history and transforms stale client ops — simpler than peer-to-peer OT, eliminates divergence risk | — Pending |
| Multi-character operations | Operations carry position + string (not single-char) — reduces message volume, handles paste/bulk-delete naturally, more realistic for a real editor | — Pending |

---
*Last updated: 2026-03-27 after Phase 01.1 completion*
