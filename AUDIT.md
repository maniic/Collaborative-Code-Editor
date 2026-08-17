# Phase 0 Audit — Collaborative Code Editor

**Temporary file.** Deleted before the final commit (Phase 4).

Audit run on branch `claude/new-session-qt8qy0`, from commit `3a2794d`.
Environment: Linux, Java 21.0.10, Node 22.22.2, Docker 29.3.1, Compose v5.1.1.

---

## Environment caveats (not repo defects)

Two sandbox limitations shaped how the audit was run. Both are environmental and
change nothing about the repository:

1. **Docker Hub's blob CDN (`production.cloudfront.docker.com`) is blocked** by the
   egress policy — `docker pull` fails with 403 on every layer. Worked around by
   pointing the daemon at the `mirror.gcr.io` registry mirror. All images
   (`postgres:16-alpine`, `redis:7-alpine`, `python:3.12-slim`,
   `eclipse-temurin:17-jdk-jammy`, `testcontainers/ryuk`) then pulled fine.
2. **`docker compose --build` cannot complete here.** The build container cannot
   reach the TLS-terminating egress proxy and does not trust its CA, so Gradle
   inside the build stage dies with `PKIX path building failed`. This is the
   sandbox, not the Dockerfile. The runtime half of the image was audited
   separately by running the host-built jar inside the exact runtime base image —
   which is how defect **F1** below was proven.

Because the image build is blocked, the full stack was exercised through the
README's documented **inner-loop path** instead: Compose-hosted PostgreSQL and
Redis plus `./gradlew bootRun` on the host, with the host Docker daemon serving
the execution sandbox. Every functional finding below was reproduced against
that running stack.

---

## 1. `docker compose --env-file .env.example up --build`

**Result: the stack cannot come up healthy. Two independent blocking defects.**

### F1 — BLOCKER: the app container cannot start. Java version mismatch.

`Dockerfile` runtime stage is `eclipse-temurin:17-jre-jammy`, but
`build.gradle.kts` pins the Gradle toolchain to Java 21, so every class is
emitted at class-file version 65. Java 17 reads at most 61.

Proven directly:

```
$ ./gradlew bootJar        # succeeds, class file major version = 0x41 = 65
$ docker run --rm -v .../build/libs:/libs:ro eclipse-temurin:17-jre-jammy \
      java -jar /libs/collaborative-code-editor-0.0.1-SNAPSHOT.jar
Exception in thread "main" java.lang.UnsupportedClassVersionError:
  com/collabeditor/CollaborativeCodeEditorApplication has been compiled by a more
  recent version of the Java Runtime (class file version 65.0), this version of
  the Java Runtime only recognizes class file versions up to 61.0
```

The README calls Compose "the canonical path". As shipped it has never worked:
the `app` container exits immediately on every start, so the healthcheck can
never pass and `curl localhost:8080/actuator/health` never returns `UP`.

The build stage has the mirror-image problem: it is `eclipse-temurin:17-jdk-jammy`,
so the toolchain resolver must download a whole second JDK 21 from the network
mid-build. That works but is slow and adds a needless network dependency.

**Fix:** JDK 21 for the build stage, JRE 21 for the runtime stage.

### F2 — BLOCKER: the documented inner-loop path cannot connect.

README "Inner-loop path" says:

```bash
docker compose up postgres redis
APP_DB_URL=jdbc:postgresql://localhost:5432/collabeditor ... ./gradlew bootRun
```

`docker-compose.yml` publishes no ports for `postgres` or `redis` — only the
`app` service maps `8080:8080`. Verified:

```
$ docker compose up -d postgres redis && docker compose port postgres 5432
invalid IP:0
$ bash -c 'exec 3<>/dev/tcp/localhost/5432'
Connection refused
```

The documented command sequence fails verbatim for anyone who follows it.

**Fix:** publish `5432:5432` and `6379:6379`.

### F3 — MINOR: `.env.example` ships a JWT secret that looks fillable but is valid.

`APP_JWT_SECRET=change-this-to-at-least-32-characters` is exactly 38 characters,
so the app's length guard accepts it and boots with a public, committed secret.
The README's "fill in at minimum `APP_JWT_SECRET`" is easy to skip when nothing
complains.

**Fix:** make the placeholder obviously non-functional, or document generating
one with `openssl rand -base64 48`.

---

## 2. `./gradlew test`

**Result: FAILED — 245 tests, 2 failures.** (3m37s)

```
HealthEndpointTest > healthEndpointIsNotProtectedByBearerAuth()   FAILED
  AssertionError: Status expected:<200> but was:<503>
HealthEndpointTest > healthEndpointReturns200WithoutAuthentication() FAILED
  AssertionError: Status expected:<200> but was:<503>
```

### F4 — the health test depends on an undocumented external Redis.

`HealthEndpointTest` starts a real PostgreSQL via Testcontainers but leaves Redis
pointed at `localhost:6379` (the `application-test.yml` default). Spring Boot's
Redis health indicator therefore reports DOWN and `/actuator/health` returns 503.

Root cause confirmed by running one Redis container and re-running the same test
unchanged:

```
$ docker run -d -p 6379:6379 redis:7-alpine
$ ./gradlew test --tests '...HealthEndpointTest'
BUILD SUCCESSFUL
```

So `./gradlew test` fails from a fresh clone unless the developer happens to have
Redis already listening on 6379. Every other integration test in the suite
containerizes its own Redis correctly — this one class is the outlier.

**Fix:** add a `GenericContainer` Redis to `HealthEndpointTest` and wire
`spring.data.redis.host/port` through `@DynamicPropertySource`, matching what
`DistributedCollaborationWebSocketHandlerTest` already does. Add a regression
assertion that the health payload is `UP`.

---

## 3. `./gradlew integrationTest`

**Result: PASSED — 42 tests, 0 failures.** (49s, with no host Redis running.)

| Suite | Tests |
|---|---|
| `FlywayMigrationTest` | 14 |
| `ExecutionServiceTest` | 13 |
| `ExecutionIntegrationTest` | 6 |
| `DistributedCollaborationWebSocketHandlerTest` | 3 |
| `CollaborationPersistenceIntegrationTest` | 3 |
| `ExecutionEventRelayServiceTest` | 3 |

Genuinely self-sufficient — Testcontainers brings up its own PostgreSQL and
Redis, and the Docker-backed Python and Java sandbox tests really do run
containers. This task is in good shape.

---

## 4. `node scripts/test-ot-client.mjs`

**Result: PASSED.**

```
OK: 500 randomized scenarios (2-4 clients, 120 steps each) all converged
```

---

## 5. Functional exercise of the running stack

Backend driven through REST + WebSocket (Node harness) and through the real
client in headless Chromium (Playwright, two independent browser contexts).

### What works — verified, not assumed

- Register → 201; duplicate email → 409 with a clean JSON body; login → 200 with
  access token and `ccd_refresh_token` HttpOnly cookie; wrong password → 401;
  missing/garbage bearer → 401.
- Create session → 201, invite code matches `[A-Z2-9]{8}`; list sessions; join by
  invite code; lowercase invite codes normalize; unknown code → 404 with message.
- WebSocket handshake with `access_token` query param; `document_sync` on connect
  with document, revision and participant roster; `participant_joined` relayed.
- **Convergence holds.** Two browsers typing into the same session converged
  byte-for-byte, including a simultaneous-typing race at the same caret position.
  Concurrent same-base-revision inserts from two users both landed.
- `baseRevision` in the future → `resync_required` with a precise reason.
  Malformed JSON → `operation_error`. `update_presence` → `presence_updated`
  relayed to the other participant.
- **Execution works, both languages.** Python: `QUEUED → RUNNING → COMPLETED`,
  exit 0, stdout and stderr captured separately, and the result reaches the
  *non-requesting* participant too. Java: single-file `Main` compiled and ran,
  `stdout="java ok\n"`, exit 0.
- Infinite loop → `TIMED_OUT` after 10.6s. Second run inside 5s → 429 with the
  cooldown message.
- WS rejects an invalid token and rejects a valid token belonging to a
  non-participant.
- Server log across the whole exercise: clean. No stack traces, no unexpected
  WARNs.

### What is broken

**F5 — BLOCKER for the README's own demo: the same user in two windows does not sync.**

`app.js` `handleServerMessage` discards remote operations by author:

```js
case 'operation_applied':
  if (payload.userId === state.userId) break;   // own op, text already applied
```

Two tabs signed in as the same account share a `userId`, so each tab drops the
other's operations entirely. Reproduced: tab 1 typed `hello from tab one`,
tab 2 stayed empty and never recovered.

This is precisely the flow the README tells a reviewer to try — *"Open the same
session in two browser windows to watch edits merge in real time."* A reviewer
following the README sees a broken editor.

`OperationAppliedPayload` carries no `clientOperationId`, so the client currently
has no way to tell "my own echo" from "my other tab". The server already relays
`clientOperationId` internally (`OperationRelayPayload`), it is just dropped on
the way out.

**Fix:** add `clientOperationId` to `OperationAppliedPayload` (purely additive —
existing consumers keep working) and have the client skip echoes by operation id
rather than by author. Regression test on both sides.

**F6 — a rejected WebSocket handshake returns HTTP 200.**

`CollaborationHandshakeInterceptor.beforeHandshake` returns `false` without ever
setting a response status, so Spring leaves the default 200. The browser reports:

```
WebSocket connection to 'ws://.../ws/sessions/...?access_token=expired.invalid.token'
failed: Error during WebSocket handshake: Unexpected response code: 200
```

The client cannot distinguish "your token expired" from "you were removed from
this session" from a transient network drop, so it does the only thing it can —
reconnects forever.

**Fix:** set 401 for missing/invalid token, 403 for a non-participant, before
returning `false`.

**F7 — an expired token produces an infinite silent reconnect loop.**

`ws.onclose` unconditionally schedules a reconnect. With a dead token the client
sits on `reconnecting…` indefinitely, never telling the user to sign in again.
Reproduced by swapping in a bad token and forcing a reconnect: status stuck at
`reconnecting…`, no actionable message anywhere. Depends on F6 for a clean fix.

**F8 — unhandled promise rejection on "copy invite code".**

```js
navigator.clipboard.writeText(state.session.inviteCode);   // never caught
toast('Invite code copied', false);                        // lies on failure
```

Reproduced: `NotAllowedError: Failed to execute 'writeText' on 'Clipboard': Write
permission denied.` — an uncaught rejection in the console, while the UI cheerfully
claims success. Fires whenever the page is served over plain HTTP from anything
but `localhost`, or when permission is denied.

**F9 — `/favicon.ico` 404s on every page load.**

`SecurityConfig` permits `/favicon.ico` but no such file exists in
`src/main/resources/static/`. Confirmed 404. Console noise on every visit.

**F10 — validation errors reach the user as the bare string "Bad Request".**

There is no `MethodArgumentNotValidException` handler, so bean-validation failures
fall through to Spring's default body — which has no `message` field, only
`timestamp/status/error/path`. The client's error extractor finds nothing usable.
Submitting the empty login form shows a toast reading exactly **`Bad Request`**;
the actual server-side detail (`password: size must be between 8 and 128`,
`email: must be a well-formed email address`) is discarded. This blocks the
inline-validation requirement in Phase 2.

**F11 — presence is transmitted but never rendered.**

```js
case 'presence_updated':
  break; // selection sharing not rendered in this client
```

The whole presence pipeline works end to end — throttling, Redis relay,
selection transformation against concurrent ops — and the client throws it away.
Playwright found 0 remote-cursor elements. The most visually impressive feature
in the system is invisible. Phase 2 item.

**F12 — the execution panel never says who ran the code.**

`ExecutionUpdatedPayload` carries `requestedByEmail`, verified populated on the
wire. The client ignores it. Also ignored: `sourceRevision`, `startedAt`,
`finishedAt` (no timing shown), and `REJECTED` is missing from the terminal-status
list that re-enables the Run button, so a cooldown rejection arriving over
WebSocket leaves the button in a stale state.

### Console noise, full inventory

```
[V] http 400: http://localhost:8080/api/auth/login      (F10)
[V] console.error: Failed to load resource: 400          (F10)
[A] console.error: WebSocket handshake: Unexpected response code: 200   (F6)
    NotAllowedError: Clipboard write permission denied   (F8)
    404 /favicon.ico                                     (F9)
```

No layout shift observed on load. No horizontal overflow at 760px.

---

## 6. UI screens — honest ratings

Screenshots captured at 1440×900 against the running stack.

| Screen | Rating | Notes |
|---|---|---|
| Auth | **6/10** | The most finished screen. Centered card, sensible dark palette, decent hierarchy. Lets it down: the two buttons carry equal visual weight so there is no clear primary action, there is no inline field validation, and the "min 8 chars" hint is the only guidance. |
| Lobby | **4/10** | Functional and empty. Session rows are a bare `code · LANGUAGE · N online` string with an "Open" button — no language badges, no participant avatars, no created-at, no visual hierarchy between "create", "join" and "your sessions". The user chip renders a full raw email, which overflows the pill on long addresses. |
| Editor | **3/10** | This is the screen that has to sell the project, and it is a bare `<textarea>`. No syntax highlighting, no line numbers, no gutter, no active-line highlight. ~80% of the frame is empty dark space. Participant chips are full email addresses jammed into the header centre. No remote cursors (F11) — nothing on screen says "this is collaborative" unless you happen to catch text moving. |
| Execution panel | **3/10** | A 340px column with the word `OUTPUT`, a status string, and undifferentiated `<pre>` text. stdout and stderr are visually identical apart from a `--- stderr ---` text divider. No exit-code styling, no duration, no attribution (F12), no run history, no empty state. |
| Toast | **5/10** | Works, positioned sensibly, but it is the only feedback channel in the whole app — it carries auth errors, edit rejections, resync warnings, and success messages alike. |

Overall: a competent developer prototype. Nothing is ugly, but nothing looks
designed, and the editor — the thing the project is *about* — looks the least
finished.

---

## 7. Repo hygiene

| Gap | Detail |
|---|---|
| **No `LICENSE`** | Nothing at all. A portfolio repo with no license is legally "all rights reserved". |
| **No CI** | No `.github/` directory. No badge, no green check on the repo page, no proof the tests pass. |
| **No screenshots** | No `docs/`, no `docs/images/`. The README is 511 lines of unbroken text and tables — there is not a single image, so the GitHub page shows a wall of prose. |
| **README ordering is backwards** | Order today: Prerequisites → Quickstart → Verification → Web Client → REST API → WebSocket Protocol → Architecture → Design Decisions → agentic tools. The first screenful a reviewer sees is a bulleted list of Docker socket caveats. The genuinely impressive engineering — server-authoritative OT with convergence guarantees, snapshot-plus-replay, multi-instance Redis coordination, Docker sandboxing — is at lines 389–481, far below the fold. |
| **Agentic section dominates the ending** | "Extending This Project With Agentic Tools" is 27 lines with three sub-headings, and it is the last thing a reader sees. It advertises the toolchain, not the project. |
| **Stale doc claim** | README line 494 documents `.claude/`: "local workspace settings for Claude-compatible tooling." That directory does not exist in the repo. |
| **Mermaid diagram will not render as intended** | Every node label uses `\n` for line breaks (`Auth["auth\n(JWT, refresh tokens)"]`). GitHub's mermaid renderer does not convert `\n` inside node text — it needs `<br/>`. The labels will render with a literal `\n`. The diagram is also fairly dense at 7 subgraph nodes plus 3 external systems. |
| **WS auth doc contradicts itself** | Line 272 states handshake auth is enforced "via the `Authorization` header"; line 128 correctly notes browsers must use `access_token`. The protocol section should lead with the query parameter, since that is what every browser client actually does. |
| **`.env.example` is the documented run path** | `docker compose --env-file .env.example up --build` means the committed example file is what people actually run with — see F3. |
| **Virtual threads not enabled** | `CLAUDE.md` prescribes `spring.threads.virtual.enabled=true` for WebSocket handling; `application.yml` does not set it. Not a README lie (nothing claims it), but a stated design goal left unimplemented. |
| **No TODO/FIXME debt** | Clean — `grep` across `src/` and `scripts/` found zero TODO/FIXME/XXX/HACK markers. |

---

## Defect summary

| ID | Severity | Area | Summary |
|---|---|---|---|
| F1 | **Blocker** | Compose | App container cannot start — JRE 17 runtime vs Java 21 bytecode |
| F2 | **Blocker** | Compose | postgres/redis ports unpublished; documented inner-loop path cannot connect |
| F5 | **Blocker** | Client OT | Same user in two windows never syncs — the README's own demo |
| F4 | High | Tests | `./gradlew test` fails from a fresh clone (health test needs external Redis) |
| F6 | High | WS auth | Rejected handshake returns HTTP 200 |
| F7 | High | Client | Expired token → infinite silent reconnect loop |
| F10 | High | API | Validation errors surface as the bare string "Bad Request" |
| F11 | High | Client | Presence protocol fully implemented server-side, never rendered |
| F3 | Medium | Config | Committed JWT secret is long enough to silently work |
| F8 | Medium | Client | Unhandled clipboard rejection; UI claims success on failure |
| F12 | Medium | Client | No run attribution, timing, or `REJECTED` handling |
| F9 | Low | Client | `/favicon.ico` 404 on every load |

Server-side OT engine, snapshot/replay, and Redis coordination: **no defects
found.** 42/42 integration tests and 500/500 randomized client convergence
scenarios pass, and two-browser convergence held under a simultaneous-typing
race. Per the hard constraints, none of it will be restructured.
