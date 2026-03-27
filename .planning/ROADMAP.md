# Roadmap: Collaborative Code Editor

## Overview

This roadmap delivers a backend-only collaborative code editor in coherent capability slices: secure access, convergent real-time collaboration, durable multi-instance coordination, sandboxed execution, and end-to-end validation with developer onboarding documentation.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Secure Access and Session Lifecycle** - Users can authenticate and manage collaboration sessions through protected APIs.
- [ ] **Phase 2: Real-Time OT Collaboration** - Participants can edit the same document concurrently with guaranteed convergence.
- [ ] **Phase 3: Durable Persistence and Multi-Instance Coordination** - Session data survives restarts and stays consistent across 2-3 backend instances.
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
- [ ] 01-02-PLAN.md - Implement JWT auth lifecycle with rotating refresh sessions
- [ ] 01-03-PLAN.md - Implement session lifecycle APIs with owner transfer and one-hour cleanup

### Phase 2: Real-Time OT Collaboration
**Goal**: Session participants can collaborate on a shared document in real time with server-authoritative OT convergence guarantees.
**Depends on**: Phase 1
**Requirements**: COLL-01, COLL-02, COLL-03, COLL-04, COLL-05, COLL-06, QUAL-02
**Success Criteria** (what must be TRUE):
  1. Joined session participant can connect over WebSocket and submit insert/delete operations.
  2. Concurrent edits from multiple participants, including multi-character operations, converge to the same final document for everyone.
  3. Participants can see other users' cursor positions update in real time.
  4. Participants receive join and leave presence events for other users in real time.
  5. JUnit 5 OT test suite passes for transform edge cases and three-user convergence scenarios.
**Plans**: TBD

### Phase 3: Durable Persistence and Multi-Instance Coordination
**Goal**: Collaboration state is durable and remains consistent when traffic is distributed across multiple backend instances.
**Depends on**: Phase 2
**Requirements**: DATA-01, DATA-02, DATA-03, DATA-04
**Success Criteria** (what must be TRUE):
  1. User, session, operation log, snapshot, and execution history records are persisted in PostgreSQL and survive service restarts.
  2. The system creates document snapshots at least every 50 operations and can recover document state from snapshot plus replay.
  3. Active session state and revision counters are coordinated through Redis for low-latency updates and atomic revision changes.
  4. Collaboration events relay across 2-3 backend instances via Redis pub/sub without document divergence.
**Plans**: TBD

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
| 1. Secure Access and Session Lifecycle | 1/3 | In Progress | - |
| 2. Real-Time OT Collaboration | 0/TBD | Not started | - |
| 3. Durable Persistence and Multi-Instance Coordination | 0/TBD | Not started | - |
| 4. Sandboxed Code Execution | 0/TBD | Not started | - |
| 5. Integration Hardening and Developer Docs | 0/TBD | Not started | - |
