---
status: resolved
phase: 01.1-fix-phase-1-auth-session-and-verification-gaps
source:
  - 01.1-04-SUMMARY.md
started: 2026-03-27T11:29:43Z
updated: 2026-03-27T19:11:04Z
---

# Debug Session: Protected Validation Errors Return 401

## Symptoms

- UAT Test 6 expected `POST /api/sessions/join` with `inviteCode=bad-code` to return `400`.
- Live app returned `401` even with a fresh bearer token that still worked on `GET /api/sessions`.
- The same pattern reproduced for `POST /api/sessions` with invalid `language=RUBY`.

## Reproduction

1. Log in through `POST /api/auth/login` and extract a bearer token.
2. Confirm `GET /api/sessions` with that token returns `200`.
3. Call `POST /api/sessions/join` with the same token and body `{"inviteCode":"bad-code"}`.
4. Observe `401`.
5. Call `POST /api/sessions` with the same token and body `{"language":"RUBY"}`.
6. Observe `401`.

## Findings

- The bearer token is valid for neighboring protected routes, so this is not a general JWT parsing failure.
- The regression appears only when MVC validation should reject the request before controller business logic runs.
- `SecurityConfig` permitted auth routes and `/actuator/health`, but not `/error` or error dispatches.
- `SessionControllerTest.shouldRejectMalformedInviteCodeWith400` used `authentication(authToken(userId))`, which bypassed the real bearer-filter plus framework error-dispatch path that the live app uses.

## Root Cause

Protected validation failures were being redispatched to Spring Boot's `/error` endpoint, but `/error` was still protected by the stateless security chain. That caused invalid join/create requests to surface as `401 Unauthorized` instead of the expected `400 Bad Request`.

## Resolution

1. `SecurityConfig` now permits `DispatcherType.ERROR` and `/error`, so authenticated MVC validation failures keep their original `400` status.
2. `AuthControllerTest` now exercises malformed invite and invalid language payloads through signed bearer tokens, and `SessionControllerTest` keeps controller-slice validation explicit for invalid language requests.
3. `JAVA_HOME=/Users/abdullah/Library/Java/JavaVirtualMachines/corretto-24.0.2/Contents/Home ./gradlew test --tests "*AuthControllerTest" --tests "*SessionControllerTest"` passed after the fix.

## Files Involved

- `src/main/java/com/collabeditor/auth/security/SecurityConfig.java`
- `src/test/java/com/collabeditor/auth/AuthControllerTest.java`
- `src/test/java/com/collabeditor/session/SessionControllerTest.java`
