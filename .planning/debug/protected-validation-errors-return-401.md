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
- `SecurityConfig` permits `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, and `/actuator/health`, but not `/error` or error dispatches.
- `SessionControllerTest.shouldRejectMalformedInviteCodeWith400` uses `authentication(authToken(userId))`, which bypasses the real bearer-filter plus framework error-dispatch path that the live app uses.

## Root Cause

Protected validation failures are being redispatched to Spring Boot's `/error` endpoint, but `/error` is still protected by the stateless security chain. That causes invalid join/create requests to surface as `401 Unauthorized` instead of the expected `400 Bad Request`.

## Files Involved

- `src/main/java/com/collabeditor/auth/security/SecurityConfig.java`
- `src/test/java/com/collabeditor/session/SessionControllerTest.java`

## Suggested Fix Direction

1. Permit `/error` or `DispatcherType.ERROR` in the security chain so validation failures can preserve their original status.
2. Add a regression test that exercises malformed invite and invalid language requests through the real bearer filter, not only a mocked authenticated principal.
