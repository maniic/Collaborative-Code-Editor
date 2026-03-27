---
phase: 01-secure-access-and-session-lifecycle
plan: 02
subsystem: auth
tags: [jwt, spring-security, jjwt, bcrypt, refresh-token, cookie, rest-api]

# Dependency graph
requires:
  - phase: 01-secure-access-and-session-lifecycle/01
    provides: "Gradle build, Spring Boot skeleton, Flyway V1 schema with users and refresh_sessions tables"
provides:
  - "JWT access token issuance and validation (HS256, 15min TTL, issuer/subject/email claims)"
  - "Refresh session persistence with SHA-256 hashed tokens and per-device rotation"
  - "Spring Security stateless filter chain with bearer token authentication"
  - "Register, login, and refresh REST endpoints with httpOnly cookie refresh delivery"
  - "Exception handler mapping auth errors to deterministic 409/401 responses"
affects: [01-03-PLAN, all-later-phases-needing-auth]

# Tech tracking
tech-stack:
  added: [jjwt-hs256, spring-security-stateless, bcrypt-password-encoder]
  patterns: [bearer-access-plus-cookie-refresh, sha256-hashed-refresh-tokens, tdd-red-green-commit-cycle]

key-files:
  created:
    - src/main/java/com/collabeditor/auth/service/JwtTokenService.java
    - src/main/java/com/collabeditor/auth/service/RefreshSessionService.java
    - src/main/java/com/collabeditor/auth/service/AuthService.java
    - src/main/java/com/collabeditor/auth/security/SecurityConfig.java
    - src/main/java/com/collabeditor/auth/security/JwtAuthenticationFilter.java
    - src/main/java/com/collabeditor/auth/security/SecurityProperties.java
    - src/main/java/com/collabeditor/auth/api/AuthController.java
    - src/main/java/com/collabeditor/auth/api/dto/RegisterRequest.java
    - src/main/java/com/collabeditor/auth/api/dto/LoginRequest.java
    - src/main/java/com/collabeditor/auth/api/dto/AuthTokenResponse.java
    - src/main/java/com/collabeditor/auth/persistence/entity/UserEntity.java
    - src/main/java/com/collabeditor/auth/persistence/entity/RefreshSessionEntity.java
    - src/main/java/com/collabeditor/auth/persistence/UserRepository.java
    - src/main/java/com/collabeditor/auth/persistence/RefreshSessionRepository.java
    - src/main/java/com/collabeditor/common/api/ApiExceptionHandler.java
    - src/test/java/com/collabeditor/auth/JwtTokenServiceTest.java
    - src/test/java/com/collabeditor/auth/RefreshSessionServiceTest.java
    - src/test/java/com/collabeditor/auth/AuthServiceTest.java
    - src/test/java/com/collabeditor/auth/AuthControllerTest.java
    - src/test/java/com/collabeditor/auth/common/TestSecurityConfig.java
  modified:
    - src/main/resources/application.yml
    - src/main/java/com/collabeditor/CollaborativeCodeEditorApplication.java

key-decisions:
  - "SecurityProperties as a record with @ConfigurationProperties for type-safe auth config"
  - "Refresh token is 32-byte SecureRandom Base64URL; only SHA-256 hex digest persisted"
  - "Device ID is UUID.randomUUID per login; preserved across rotations for reuse detection scope"
  - "TestSecurityConfig permits all requests in @WebMvcTest slices to isolate controller logic"

patterns-established:
  - "TDD red-green commit cycle: failing test commit then implementation commit"
  - "@WebMvcTest with TestSecurityConfig + @MockBean for controller-layer testing"
  - "Service-layer unit tests with Mockito for auth workflow verification"
  - "ResponseCookie.from() for httpOnly/Secure/SameSite cookie construction"

requirements-completed: [AUTH-01, AUTH-02, AUTH-03]

# Metrics
duration: 7min
completed: 2026-03-27
---

# Phase 1 Plan 02: JWT Auth with Refresh Rotation Summary

**JWT bearer access tokens (HS256, 15min) with httpOnly cookie refresh rotation, SHA-256 hashed session persistence, and Spring Security stateless filter chain**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-27T03:33:43Z
- **Completed:** 2026-03-27T03:41:00Z
- **Tasks:** 3
- **Files modified:** 22

## Accomplishments
- JwtTokenService mints HS256 access tokens with issuer, subject, email claim, and 15-minute TTL
- RefreshSessionService persists SHA-256 hashed tokens, rotates with device ID preservation, and detects reuse
- Register/login/refresh REST endpoints with httpOnly cookie delivery and deterministic error responses
- 14 unit and controller tests covering success paths and critical failure paths

## Task Commits

Each task was committed atomically:

1. **Task 1 RED: Failing tests for JWT and refresh services** - `6f646e9` (test)
2. **Task 1 GREEN: Implement token services, security config, and filter** - `65f6c81` (feat)
3. **Task 2 RED: Failing tests for auth controller** - `711201b` (test)
4. **Task 2 GREEN: Implement register, login, and refresh endpoints** - `7f96adf` (feat)
5. **Task 3: Auth service and refresh session rotation/failure coverage** - `7044b9f` (test)

## Files Created/Modified
- `src/main/java/com/collabeditor/auth/service/JwtTokenService.java` - HS256 JWT minting and validation
- `src/main/java/com/collabeditor/auth/service/RefreshSessionService.java` - Hashed refresh token persistence and rotation
- `src/main/java/com/collabeditor/auth/service/AuthService.java` - Auth workflow orchestration
- `src/main/java/com/collabeditor/auth/security/SecurityConfig.java` - Stateless filter chain with public routes
- `src/main/java/com/collabeditor/auth/security/JwtAuthenticationFilter.java` - Bearer token parsing filter
- `src/main/java/com/collabeditor/auth/security/SecurityProperties.java` - Type-safe auth configuration
- `src/main/java/com/collabeditor/auth/api/AuthController.java` - Register, login, refresh endpoints
- `src/main/java/com/collabeditor/auth/api/dto/*.java` - Request/response DTOs
- `src/main/java/com/collabeditor/auth/persistence/entity/*.java` - UserEntity, RefreshSessionEntity JPA mappings
- `src/main/java/com/collabeditor/auth/persistence/*Repository.java` - Spring Data JPA repositories
- `src/main/java/com/collabeditor/common/api/ApiExceptionHandler.java` - Exception-to-HTTP-status mapping
- `src/main/resources/application.yml` - Added app.security config block
- `src/test/java/com/collabeditor/auth/*.java` - 4 test classes with 14 scenarios

## Decisions Made
- SecurityProperties as a Java record with @ConfigurationProperties for type-safe binding of TTLs and cookie attributes
- Refresh tokens are 32-byte SecureRandom values encoded as Base64URL; only the SHA-256 hex digest is stored in the database
- Each login creates a new random device ID; rotation preserves it so reuse detection can scope revocation per device
- TestSecurityConfig disables security in @WebMvcTest slices to isolate controller HTTP contract testing from filter chain behavior

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- MockBean import path is `org.springframework.boot.test.mock.mockito.MockBean` in Spring Boot 3.3.x (not `mock.bean`); fixed immediately during test compilation.

## User Setup Required
None - no external service configuration required. JWT secret defaults to a dev value when `APP_JWT_SECRET` env var is not set.

## Next Phase Readiness
- Auth contract is complete and tested; session lifecycle APIs (01-03) can use the authenticated principal
- JwtAuthenticationFilter populates SecurityContext with user UUID, ready for @AuthenticationPrincipal in session controllers
- All auth exceptions map to deterministic HTTP statuses for consistent frontend error handling

---
*Phase: 01-secure-access-and-session-lifecycle*
*Completed: 2026-03-27*
