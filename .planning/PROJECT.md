# Collaborative Code Editor

## What This Is

A real-time collaborative code editor backend built in Java with Spring Boot 3. Multiple users join coding sessions via WebSocket, edit the same document simultaneously with conflict-free resolution using Operational Transform, and execute code together in Docker-sandboxed containers. Designed as a portfolio piece showcasing distributed systems, real-time algorithms, and containerized execution — with a full frontend planned for a future milestone.

## Core Value

The OT engine must guarantee document convergence — when multiple users edit simultaneously, every participant ends up with the same document, every time, with zero conflicts.

## Current State

Phase 4 is complete. Session participants can now enqueue canonical room code through an authenticated REST endpoint, execute Python and Java in constrained Docker sandboxes, and receive room-visible `execution_updated` lifecycle events over the existing websocket. Phase 5 is now focused on integration hardening and developer onboarding docs.

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- [x] JWT authentication and refresh rotation with fail-closed configuration validation
- [x] Session lifecycle APIs with deterministic join/leave/owner-transfer behavior
- [x] Server-authoritative OT collaboration with cursor presence and join/leave events
- [x] PostgreSQL durability for operation log, snapshots, and execution-history foundation
- [x] Snapshot-plus-replay recovery for collaboration runtime rebuilds
- [x] Redis coordination and canonical pub/sub relay for 2-3 backend instances
- [x] Docker-sandboxed execution from canonical room state with durable lifecycle history
- [x] Fixed Python and Java runtime contracts with live Docker execution proof
- [x] Redis-backed execution cooldown, bounded queueing, and room-wide lifecycle relay delivery

### Active

<!-- Current scope. Building toward these. -->

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
| OT over CRDT | OT is more intuitive for linear document editing, server-authoritative model simplifies consistency, and implementing it from scratch demonstrates deeper algorithmic understanding | Implemented in Phase 2 with convergence coverage and carried forward through durable/distributed recovery in Phase 3 |
| Redis over Kafka | Kafka is overkill for 2-3 instances; Redis pub/sub is lightweight, already needed for caching/counters, and reduces infrastructure complexity | Implemented in Phases 3-4 for collaboration relay, revision coordination, execution cooldown, and execution lifecycle fan-out |
| Docker for sandboxing | Strongest isolation model, proven security boundary, supports any language runtime, resource limits are first-class | Implemented in Phase 4 with docker-java, fixed Python/Java contracts, tmpfs-only writable paths, and live Docker verification |
| Server-authoritative OT | Server maintains canonical revision history and transforms stale client ops — simpler than peer-to-peer OT, eliminates divergence risk | Implemented in Phase 2 and reinforced by durable canonical persistence and relay behavior in Phase 3 |
| Multi-character operations | Operations carry position + string (not single-char) — reduces message volume, handles paste/bulk-delete naturally, more realistic for a real editor | Implemented in Phase 2 and persisted as canonical operation history in Phase 3 |

---
*Last updated: 2026-03-29 after Phase 4 completion*
