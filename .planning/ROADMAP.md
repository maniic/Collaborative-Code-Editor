# Roadmap: Collaborative Code Editor

## Overview

This roadmap delivers a backend-only collaborative code editor in coherent capability slices: secure access, convergent real-time collaboration, durable multi-instance coordination, sandboxed execution, and end-to-end validation with developer onboarding documentation.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: Secure Access and Session Lifecycle** - Users can authenticate and manage collaboration sessions through protected APIs.
- [x] **Phase 01.1: Fix Phase 1 auth, session, and verification gaps (INSERTED)** - Remediate security, concurrency, and verification issues discovered during Phase 1 review before Phase 2 begins. (completed 2026-03-27)
- [x] **Phase 2: Real-Time OT Collaboration** - Participants can edit the same document concurrently with guaranteed convergence. (completed 2026-03-27)
- [x] **Phase 3: Durable Persistence and Multi-Instance Coordination** - Session data survives restarts and stays consistent across 2-3 backend instances. (completed 2026-03-29)
- [ ] **Phase 4: Sandboxed Code Execution** - Participants can execute shared code safely in constrained Docker environments.
- [ ] **Phase 5: Integration Hardening and Developer Docs** - The full stack is verifiable via integration tests and reproducible local setup docs.

## Phase Details

### Phase 1: Secure Access and Session Lifecycle
**Goal**: Users can securely access the system and complete the session create/join/leave workflow via REST.
**Depends on**: Nothing (first phase)
**Requirements**: AUTH-01, AUTH-02, AUTH-03, SESS-01, SESS-02, SESS-03, SESS-04, QUAL-01
**Success Criteria** (what must be TRUE):
  1. New user can register with email/password and then log in to receive a JWT access token.
  2. User with an expired access token can obtain a fresh token without re-entering credentials.
  3. Authenticated user can create a session with a chosen language, list available sessions, join one, and leave cleanly.
  4. Database schema is versioned and applied through Flyway migrations at application startup.
**Plans**: 3 plans
Plans:
- [x] 01-01-PLAN.md - Bootstrap backend foundation and Flyway schema baseline
- [x] 01-02-PLAN.md - Implement JWT auth lifecycle with rotating refresh sessions
- [x] 01-03-PLAN.md - Implement session lifecycle APIs with owner transfer and one-hour cleanup

### Phase 01.1: Fix Phase 1 auth, session, and verification gaps (INSERTED)

**Goal:** Phase 1 auth and session behavior is secure, concurrency-safe, and honestly verifiable before real-time collaboration work begins.
**Requirements**: AUTH-01, AUTH-02, AUTH-03, SESS-01, SESS-03, SESS-04, QUAL-01
**Depends on:** Phase 1
**Plans:** 4/4 plans complete

**Success Criteria** (what must be TRUE):
  1. JWT auth fails fast on insecure configuration, accepts only app-issued tokens, and refresh rotation is single-use under concurrent requests.
  2. Session join, leave, ownership transfer, and cleanup logic maintain a single coherent owner and do not violate participant-cap or cleanup guarantees under concurrent mutations.
  3. Session creation and join paths handle malformed invite codes and invite-code collisions deterministically.
  4. The Flyway/JPA contract and full Phase 1 verification path are green on a supported runtime and Docker setup.

Plans:
- [x] 01.1-01-PLAN.md - Repair schema parity and honest verification baseline
- [x] 01.1-02-PLAN.md - Harden JWT validation, refresh rotation, and security test coverage
- [x] 01.1-03-PLAN.md - Fix session concurrency, owner transfer, invite-code, and cleanup races
- [x] 01.1-04-PLAN.md - Close the protected validation 401 gap and add bearer-path regression coverage

### Phase 2: Real-Time OT Collaboration
**Goal**: Session participants can collaborate on a shared document in real time with server-authoritative OT convergence guarantees.
**Depends on**: Phase 01.1
**Requirements**: COLL-01, COLL-02, COLL-03, COLL-04, COLL-05, COLL-06, QUAL-02
**Success Criteria** (what must be TRUE):
  1. Joined session participant can connect over WebSocket and submit insert/delete operations.
  2. Concurrent edits from multiple participants, including multi-character operations, converge to the same final document for everyone.
  3. Participants can see other users' cursor positions update in real time.
  4. Participants receive join and leave presence events for other users in real time.
  5. JUnit 5 OT test suite passes for transform edge cases and three-user convergence scenarios.
**Plans**: 3/3 plans complete

Plans:
- [x] 02-01-PLAN.md - Build the server-authoritative OT core and convergence test suite
- [x] 02-02-PLAN.md - Add the authenticated WebSocket collaboration contract and canonical operation flow
- [x] 02-03-PLAN.md - Implement presence tracking, cursor transformation, and participant events

### Phase 3: Durable Persistence and Multi-Instance Coordination
**Goal**: Collaboration state is durable and remains consistent when traffic is distributed across multiple backend instances.
**Depends on**: Phase 2
**Requirements**: DATA-01, DATA-02, DATA-03, DATA-04
**Success Criteria** (what must be TRUE):
  1. User, session, operation log, snapshot, and execution history records are persisted in PostgreSQL and survive service restarts.
  2. The system creates document snapshots at least every 50 operations and can recover document state from snapshot plus replay.
  3. Active session state and revision counters are coordinated through Redis for low-latency updates and atomic revision changes.
  4. Collaboration events relay across 2-3 backend instances via Redis pub/sub without document divergence.
**Plans**: 4/4 plans complete

Plans:
- [x] 03-01-PLAN.md - Establish the durable PostgreSQL schema and repository foundation for Phase 3
- [x] 03-02-PLAN.md - Implement the durable append, snapshot cadence, and lazy rebuild services for Phase 3
- [x] 03-03-PLAN.md - Introduce Redis coordination and canonical collaboration relay services for Phase 3
- [x] 03-04-PLAN.md - Rewire the WebSocket collaboration flow to use durable recovery and Redis-backed canonical relay behavior

### Phase 4: Sandboxed Code Execution
**Goal**: Participants can execute the shared document safely and predictably in isolated containers.
**Depends on**: Phase 3
**Requirements**: EXEC-01, EXEC-02, EXEC-03, EXEC-04
**Success Criteria** (what must be TRUE):
  1. Session participant can execute the current document inside a Docker sandbox and receive execution output.
  2. Execution supports both Python and Java source documents.
  3. Every execution enforces memory, CPU, timeout, no-network, read-only filesystem, and non-root isolation constraints.
  4. Execution requests are handled by a bounded queue and rejected or delayed according to one execution per user every five seconds.
**Plans**: TBD

### Phase 5: Integration Hardening and Developer Docs
**Goal**: Developers can reproduce, validate, and understand the system end to end from a clean local environment.
**Depends on**: Phase 4
**Requirements**: QUAL-03, DOCS-01, DOCS-02
**Success Criteria** (what must be TRUE):
  1. Testcontainers integration tests validate persistence, Redis coordination, and code execution flows in one automated run.
  2. Developer can start backend, PostgreSQL, and Redis using docker-compose by following documented steps.
  3. README includes setup guidance, API documentation, architecture notes, and explicit design-decision rationale.
**Plans**: TBD

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Secure Access and Session Lifecycle | 3/3 | Complete | 2026-03-27 |
| 01.1. Fix Phase 1 auth, session, and verification gaps | 4/4 | Complete    | 2026-03-27 |
| 2. Real-Time OT Collaboration | 3/3 | Complete | 2026-03-27 |
| 3. Durable Persistence and Multi-Instance Coordination | 4/4 | Complete | 2026-03-29 |
| 4. Sandboxed Code Execution | 0/TBD | Not started | - |
| 5. Integration Hardening and Developer Docs | 0/TBD | Not started | - |
