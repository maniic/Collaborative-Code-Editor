# Requirements: Collaborative Code Editor

**Defined:** 2026-03-26
**Core Value:** The OT engine must guarantee document convergence — when multiple users edit simultaneously, every participant ends up with the same document, every time, with zero conflicts.

## v1 Requirements

Requirements for the initial backend-only milestone. Each maps to roadmap phases.

### Authentication

- [ ] **AUTH-01**: User can register with email and password
- [ ] **AUTH-02**: User can log in and receive a JWT access token
- [ ] **AUTH-03**: User can refresh an expired access token without re-entering credentials

### Sessions

- [ ] **SESS-01**: Authenticated user can create a collaborative coding session with a selected programming language
- [ ] **SESS-02**: Authenticated user can view the list of available coding sessions
- [ ] **SESS-03**: Authenticated user can join an existing coding session
- [ ] **SESS-04**: Session participant can leave a coding session cleanly

### Collaboration

- [ ] **COLL-01**: Session participant can connect to a joined session over WebSocket
- [ ] **COLL-02**: Session participant can submit insert and delete operations against the shared document
- [ ] **COLL-03**: Concurrent edits from multiple participants converge to the same final document for every participant
- [ ] **COLL-04**: The collaboration engine supports multi-character insert and delete operations
- [ ] **COLL-05**: Session participant can see cursor positions from other active participants in real time
- [ ] **COLL-06**: Session participant receives join and leave presence events for other participants

### Persistence & Scaling

- [ ] **DATA-01**: User, session, operation log, snapshot, and execution history data are persisted in PostgreSQL
- [ ] **DATA-02**: The system creates document snapshots at least every 50 operations and can recover document state from snapshot plus replay
- [ ] **DATA-03**: Active session state and revision counters are stored in Redis for low-latency coordination
- [ ] **DATA-04**: Collaboration events are relayed across 2-3 backend instances through Redis pub/sub without document divergence

### Code Execution

- [ ] **EXEC-01**: Session participant can execute the current code document inside a Docker sandbox
- [ ] **EXEC-02**: Code execution supports both Python and Java
- [ ] **EXEC-03**: Each execution enforces memory, CPU, timeout, no-network, read-only filesystem, and non-root isolation limits
- [ ] **EXEC-04**: Code execution requests are rate limited to one run per user every five seconds and are processed through a bounded queue

### Quality & Docs

- [x] **QUAL-01**: Database schema changes are managed through Flyway migrations
- [ ] **QUAL-02**: The OT engine has comprehensive JUnit 5 tests covering transform edge cases and three-user convergence
- [ ] **QUAL-03**: Integration tests use Testcontainers to validate persistence, Redis coordination, and execution flows
- [ ] **DOCS-01**: Developer can start the backend, PostgreSQL, and Redis locally with docker-compose using documented steps
- [ ] **DOCS-02**: Developer can reference a README with setup instructions, API documentation, architecture notes, and design decisions

## v2 Requirements

Deferred to future milestones. Tracked but not in the current roadmap.

### Frontend

- **FE-01**: User can collaborate through a browser-based editor frontend
- **FE-02**: User can see editor niceties such as syntax highlighting and richer workspace affordances

### History & Extensions

- **HIST-01**: User can replay session history for debugging or demos
- **EXEC-05**: User can execute languages beyond Python and Java

## Out of Scope

Explicitly excluded from the current milestone to prevent scope creep.

| Feature | Reason |
|---------|--------|
| CRDT-based conflict resolution | OT is the deliberate technical centerpiece of this project |
| Kafka or another message broker | Redis pub/sub is sufficient for the 2-3 instance scale target |
| OAuth or social login | Email/password JWT auth is enough for v1 |
| Multi-file or project management | Current scope is one shared document per coding session |
| Production deployment infrastructure | The milestone focuses on the backend product, not full ops automation |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| AUTH-01 | Phase 1 | Pending |
| AUTH-02 | Phase 1 | Pending |
| AUTH-03 | Phase 1 | Pending |
| SESS-01 | Phase 1 | Pending |
| SESS-02 | Phase 1 | Pending |
| SESS-03 | Phase 1 | Pending |
| SESS-04 | Phase 1 | Pending |
| COLL-01 | Phase 2 | Pending |
| COLL-02 | Phase 2 | Pending |
| COLL-03 | Phase 2 | Pending |
| COLL-04 | Phase 2 | Pending |
| COLL-05 | Phase 2 | Pending |
| COLL-06 | Phase 2 | Pending |
| DATA-01 | Phase 3 | Pending |
| DATA-02 | Phase 3 | Pending |
| DATA-03 | Phase 3 | Pending |
| DATA-04 | Phase 3 | Pending |
| EXEC-01 | Phase 4 | Pending |
| EXEC-02 | Phase 4 | Pending |
| EXEC-03 | Phase 4 | Pending |
| EXEC-04 | Phase 4 | Pending |
| QUAL-01 | Phase 1 | Complete |
| QUAL-02 | Phase 2 | Pending |
| QUAL-03 | Phase 5 | Pending |
| DOCS-01 | Phase 5 | Pending |
| DOCS-02 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 26 total
- Mapped to phases: 26
- Unmapped: 0

---
*Requirements defined: 2026-03-26*
*Last updated: 2026-03-26 after roadmap creation*
