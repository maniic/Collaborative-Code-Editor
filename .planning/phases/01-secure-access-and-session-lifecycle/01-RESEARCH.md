# Phase 1: Secure Access and Session Lifecycle - Research

**Researched:** 2026-03-26
**Domain:** Spring Boot 3 authentication, session lifecycle APIs, and initial Flyway-backed persistence
**Confidence:** MEDIUM

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Access tokens should be short-lived JWTs sent as bearer tokens in the `Authorization` header.
- Refresh tokens should be stored in an httpOnly cookie rather than returned as a normal bearer token in JSON responses.
- Refresh tokens should rotate on every successful refresh request.
- Refresh sessions should be tracked per device or browser so multiple devices can stay signed in simultaneously.
- The session list should show only sessions the authenticated user created or has already joined, not a global public directory of joinable rooms.
- Each session should have both a canonical UUID and a separate invite code; the invite code is the human-facing identifier used for sharing and joins.
- The session language is chosen at room creation and remains immutable afterward.
- Each session should have a special owner role, with the creator as the initial owner.
- If the owner leaves while other participants remain, ownership should transfer instead of closing the room.
- When the last participant leaves, the room should remain recoverable for about one hour before cleanup.
- Phase 1 should enforce a modest participant cap, roughly in the 8-16 range, to keep room behavior predictable in the first milestone.

### the agent's Discretion
- Exact access-token expiry value within the short-lived range.
- Exact refresh-cookie attributes and naming, as long as they match the chosen bearer-plus-cookie model.
- Invite-code format, length, and generation strategy.
- Owner-transfer selection rule when multiple participants remain.
- Concrete cleanup mechanism for the one-hour empty-room TTL.
- Exact participant-cap number within the chosen soft-cap range.

### Deferred Ideas (OUT OF SCOPE)
- None — discussion stayed within phase scope.
</user_constraints>

<research_summary>
## Summary

Phase 1 should use a conventional Spring Boot MVC + Spring Security + JPA structure, but with two deliberate choices that shape the model early: refresh tokens are persisted and rotated, and collaborative sessions are private-ish rooms with explicit ownership and invite codes. The safest planning shape is to treat this phase as the foundation for all later work: project bootstrap, schema, auth model, and session REST contracts should all be established here rather than deferred.

For authentication, the standard approach is a stateless bearer access token backed by a stateful refresh-session table. That allows short access-token TTLs, refresh-token rotation, multi-device sessions, and server-side invalidation when a refresh token is reused or revoked. For session management, use a normalized model: `users`, `refresh_sessions`, `coding_sessions`, and `session_participants`, with owner identity represented on the session and membership modeled separately.

**Primary recommendation:** plan Phase 1 in three waves: bootstrap + migrations, auth lifecycle, then session lifecycle APIs and ownership/cleanup semantics, with verification built into each wave.
</research_summary>

<standard_stack>
## Standard Stack

The established libraries and tools for this phase:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot Web | 3.3.x managed | REST controllers for auth and session lifecycle | Standard MVC layer for JSON APIs in Spring Boot |
| Spring Security | 3.3.x managed | Authentication filter chain, password hashing, auth entry points | Standard for JWT-backed REST auth in Boot apps |
| Spring Data JPA | 3.3.x managed | Persistence for users, refresh sessions, rooms, and participants | Repository/service pattern fits this domain well |
| Flyway | 10.x managed | Schema versioning for initial database setup | Standard migration tool and already mandated by roadmap |
| PostgreSQL JDBC Driver | managed | PostgreSQL runtime connectivity | Required for production-aligned persistence |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| JJWT | 0.12.x | JWT creation and verification | Use for self-issued access and refresh token flows |
| Spring Validation | managed | Request DTO validation | Use for register/login/session DTO constraints |
| SpringDoc OpenAPI | 2.x | API docs for auth and session endpoints | Useful once controllers exist |
| Lombok | current | Reduce boilerplate in entities/config/DTOs | Use if the codebase opts into it during bootstrap |
| Spring Security Test | managed | Auth-aware test helpers | Use for controller/service verification |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JJWT | Spring Security OAuth2 JWT support | Better when validating external IdP tokens; heavier for self-issued auth |
| BCryptPasswordEncoder | Argon2PasswordEncoder | Argon2 is stronger on paper, but BCrypt is simpler and more common in Spring defaults |
| Invite code table column | Separate invite entity | Separate entity is more flexible later, but a column on `coding_sessions` is enough for Phase 1 |

**Installation:**
```bash
# Spring starters and libs should be added through build.gradle.kts
# spring-boot-starter-web
# spring-boot-starter-security
# spring-boot-starter-data-jpa
# spring-boot-starter-validation
# flyway-core
# flyway-database-postgresql
# postgresql
# jjwt-api / jjwt-impl / jjwt-jackson
# springdoc-openapi-starter-webmvc-ui
```
</standard_stack>

<architecture_patterns>
## Architecture Patterns

### Recommended Project Structure
```text
src/main/java/.../
├── auth/         # controllers, DTOs, token service, password policy, refresh-session service
├── session/      # controllers, DTOs, room service, participant service, invite-code logic
├── config/       # security config, OpenAPI config, persistence config
├── common/       # shared error handling, base response contracts, clock/ID utilities
└── db/migration/ # Flyway SQL migrations
```

### Pattern 1: Stateless access token + persisted refresh session
**What:** Use JWT bearer access tokens for request auth, but persist refresh sessions in PostgreSQL so rotation, reuse detection, logout, and multi-device behavior are possible.
**When to use:** Any time access tokens are intentionally short-lived and refresh behavior is part of the product contract.
**Example:** `POST /auth/login` returns access token JSON + sets refresh cookie; `POST /auth/refresh` rotates refresh session and returns a new access token.

### Pattern 2: Session owner on room, membership in join table
**What:** Model room ownership on the session record, but track active membership separately.
**When to use:** Collaborative rooms where the creator has special lifecycle semantics and membership is mutable.
**Example:** `coding_sessions(owner_user_id, language, invite_code, expires_at)` plus `session_participants(session_id, user_id, joined_at, left_at, status)`.

### Pattern 3: Service-layer policy for lifecycle transitions
**What:** Keep owner-transfer, participant-cap checks, invite-code resolution, and empty-room TTL rules in services rather than controllers or database triggers.
**When to use:** Domain rules that will likely evolve in later phases when collaboration/WebSocket behavior arrives.
**Example:** `SessionService.leaveSession(...)` handles transfer ownership, participant removal, and room expiry scheduling.

### Anti-Patterns to Avoid
- **Long-lived JWT as the only auth mechanism:** blocks rotation, revocation, and device-level session tracking.
- **Global public room listing:** contradicts the chosen “mine only” visibility model and makes invite-code flow ambiguous.
- **Embedding participants directly inside a room JSON blob:** makes ownership transfer, join history, and cleanup logic harder to query and test.
</architecture_patterns>

<dont_hand_roll>
## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Password hashing | Custom crypto or manual salting | Spring Security `PasswordEncoder` | Avoids broken hashing choices and upgrade pain |
| Schema evolution | Ad hoc SQL files run manually | Flyway migrations | Gives ordered, repeatable schema history from day one |
| Bean validation | Manual null/length checks in controllers | Jakarta validation annotations + exception handler | Keeps request validation consistent and testable |
| JWT parsing | String splitting or homegrown signature code | JJWT or Spring’s JWT support | Prevents subtle security bugs in parsing and claims handling |

**Key insight:** Phase 1 should establish safe platform primitives, not prove originality in authentication plumbing.
</dont_hand_roll>

<common_pitfalls>
## Common Pitfalls

### Pitfall 1: Refresh rotation without persisted session state
**What goes wrong:** The system issues new refresh tokens but has no durable record of which token chain is valid, making rotation meaningless.
**Why it happens:** Teams treat refresh as “just another JWT” instead of a stateful server-side session.
**How to avoid:** Create a `refresh_sessions` table in Phase 1 with user, token identifier or hash, device label, expiry, revoked status, and last-used metadata.
**Warning signs:** Refresh endpoint can mint tokens but cannot revoke, rotate, or detect reuse.

### Pitfall 2: Session lifecycle rules split across controllers and later phases
**What goes wrong:** Ownership transfer, participant cap, and empty-room TTL rules become scattered and inconsistent once WebSocket behavior is added later.
**Why it happens:** Early REST endpoints only model “happy path” CRUD.
**How to avoid:** Centralize lifecycle policy in `SessionService` now, even before realtime features exist.
**Warning signs:** `create`, `join`, and `leave` all re-implement participant/owner logic separately.

### Pitfall 3: Auth contract that conflicts with future WebSocket authentication
**What goes wrong:** REST auth works, but later WebSocket handshake/auth cannot reuse the same token semantics cleanly.
**Why it happens:** Access-token claims or refresh/session models are too ad hoc.
**How to avoid:** Keep access JWT compact and user/session oriented, and plan for token validation at connection establishment only.
**Warning signs:** Phase 1 auth design assumes browser-only cookie auth everywhere or ties room membership directly to refresh state.
</common_pitfalls>

## Validation Architecture

Phase 1 should create automated verification from the start, but it does not need the full Testcontainers integration layer yet. The validation strategy should focus on unit and slice tests that prove auth/session policy and migration correctness while keeping execution fast.

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Spring Security Test |
| **Config file** | `build.gradle.kts` and standard Spring test setup |
| **Quick run command** | `./gradlew test --tests \"*Auth*\" --tests \"*Session*\"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~20-60 seconds once the project skeleton exists |

**Recommended verification shape:**
- Wave 0 creates the Gradle/Spring test harness and baseline package structure.
- Auth tasks add unit/service tests for password hashing, token minting, refresh rotation, and refresh-session invalidation.
- Session tasks add service/controller tests for create, list, join, leave, owner transfer, participant-cap enforcement, and room expiry marking.
- Migration tasks verify Flyway can create the schema cleanly against PostgreSQL-compatible assumptions.

**Manual-only checks to keep visible during planning:**
- Cookie flags and CORS behavior for refresh flows
- API contract clarity for invite-code joins vs UUID-backed internal identity

<open_questions>
## Open Questions

1. **Exact access-token TTL**
   - What we know: It must be short-lived and work with rotated refresh sessions.
   - What's unclear: Whether the best default is closer to 10 or 15 minutes.
   - Recommendation: Let planning pin a concrete value once the auth config class and threat model are scaffolded.

2. **Owner transfer rule**
   - What we know: Ownership must transfer when the current owner leaves and others remain.
   - What's unclear: Whether transfer should go to earliest joiner, newest active participant, or explicit deterministic priority.
   - Recommendation: Pick a deterministic rule in planning and document it in the session service tests.
</open_questions>

<sources>
## Sources

### Primary (HIGH confidence)
- `.planning/phases/01-secure-access-and-session-lifecycle/01-CONTEXT.md` — locked user decisions for Phase 1
- `.planning/ROADMAP.md` — Phase 1 goal, success criteria, and requirement mapping
- `.planning/REQUIREMENTS.md` — Phase 1 requirement IDs and acceptance scope

### Secondary (MEDIUM confidence)
- `.planning/research/STACK.md` — recommended Spring/JWT/PostgreSQL stack for the project
- `.planning/research/ARCHITECTURE.md` — service boundaries and build-order implications
- `.planning/research/PITFALLS.md` — domain-specific warnings relevant to auth, lifecycle, and future WebSocket alignment

### Tertiary (LOW confidence - validate during implementation)
- Internal synthesis from the project research set and standard Spring Boot practices, without fresh external verification in this session
</sources>

<metadata>
## Metadata

**Research scope:**
- Core technology: Spring Boot auth and REST session lifecycle
- Ecosystem: Spring Security, JJWT, JPA, Flyway, validation, testing
- Patterns: token rotation, persisted refresh sessions, room ownership, join/leave lifecycle
- Pitfalls: refresh misuse, lifecycle drift, future WebSocket auth mismatch

**Confidence breakdown:**
- Standard stack: MEDIUM - aligned with existing project research, but versions were not re-verified online in this run
- Architecture: HIGH - derived directly from project roadmap and architecture research
- Pitfalls: HIGH - consistent with existing project pitfall research and Phase 1 decisions
- Validation approach: MEDIUM - strong fit for the phase, but exact commands depend on the chosen Gradle/test scaffold

**Research date:** 2026-03-26
**Valid until:** 2026-04-25
</metadata>

---

*Phase: 01-secure-access-and-session-lifecycle*
*Research completed: 2026-03-26*
*Ready for planning: yes*
