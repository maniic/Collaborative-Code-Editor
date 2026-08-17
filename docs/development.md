# Development

## Prerequisites

- **Java 21** (Eclipse Temurin recommended; the Gradle toolchain resolver will
  auto-provision if missing)
- **Docker 24+** with a running daemon
- **Docker Compose v2** (the `docker compose` subcommand, not `docker-compose` v1)
- **Node 18+**, only for the client-side OT convergence harness
- **Docker socket accessible** at `/var/run/docker.sock`, or overridden via
  `DOCKER_SOCKET_PATH`

The app container mounts the Docker daemon socket so it can launch execution
sandboxes. For Docker Desktop, Linux, and Colima Compose runs, keep
`DOCKER_SOCKET_PATH=/var/run/docker.sock`. Do not point it at the host-side
Colima client socket under `~/.colima/...`.

---

## Inner loop

For faster iteration, run only the infrastructure in Compose and the app on the
host:

```bash
docker compose up -d postgres redis

APP_DB_URL=jdbc:postgresql://localhost:5432/collabeditor \
APP_DB_USERNAME=collabeditor \
APP_DB_PASSWORD=collabeditor \
APP_REDIS_HOST=localhost \
APP_REDIS_PORT=6379 \
APP_JWT_SECRET=$(openssl rand -base64 48) \
./gradlew bootRun
```

Both services publish their ports to the host, so `localhost:5432` and
`localhost:6379` resolve. This skips the image build and uses the Gradle
toolchain-resolved JDK directly.

Static client assets are served straight from `src/main/resources/static/`, so
a browser refresh picks up edits without a restart when running under
`bootRun`.

---

## Tests

```bash
./gradlew test              # unit, slice, and integration tests
./gradlew integrationTest   # integration-tagged suites only
node scripts/test-ot-client.mjs
```

`./gradlew test` runs everything. `integrationTest` runs only the suites tagged
`@Tag("integration")`, which is the fast way to exercise the parts that need
real infrastructure:

- Flyway schema bootstrapping and JPA validation against PostgreSQL
- Durable OT operation persistence, snapshot creation, and snapshot-plus-replay recovery
- Redis-backed cross-instance collaboration relay
- Docker-backed sandboxed Python and Java execution

All of these bring up their own containers via Testcontainers — no host
PostgreSQL or Redis required — but they do need a working Docker daemon.

`scripts/test-ot-client.mjs` simulates the server's canonical log plus 2-4
browser clients making randomized concurrent edits with arbitrary message
interleaving, across 500 seeded scenarios, and asserts every client converges
on the server document. It imports the real client engine from
`src/main/resources/static/ot.js`, so it fails if the browser and server
transform rules ever drift apart.

---

## Web client

The client is plain HTML, CSS, and ES modules under
`src/main/resources/static/` — no npm install, no bundler, no framework.

| File | Role |
|---|---|
| `index.html` | Markup for the auth, lobby, and editor views |
| `app.css` | The whole design system: tokens, components, layouts |
| `app.js` | Application logic: auth, lobby, socket lifecycle, presence, execution |
| `ot.js` | Client-side OT engine, mirroring the server's transform rules |
| `editor.js` | CodeMirror 6 integration and remote-cursor decorations |
| `vendor/` | Pre-built CodeMirror 6 bundle (see `vendor/README.md`) |

CodeMirror is vendored as a pre-built ES module bundle so the browser can
import it directly. Rebuilding it is an authoring-time step documented in
`src/main/resources/static/vendor/README.md`; nothing in the normal build,
test, or run path invokes it.

---

## Configuration

Runtime configuration lives in `src/main/resources/application.yml` and is
overridable by environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/collabeditor` | JDBC URL |
| `APP_DB_USERNAME` | `collabeditor` | Database user |
| `APP_DB_PASSWORD` | `collabeditor` | Database password |
| `APP_REDIS_HOST` | `localhost` | Redis host |
| `APP_REDIS_PORT` | `6379` | Redis port |
| `APP_JWT_SECRET` | *(none — required)* | HMAC signing secret, min 32 characters |
| `DOCKER_SOCKET_PATH` | `/var/run/docker.sock` | Socket mounted into the app container |

Notable fixed settings: access tokens live 15 minutes, refresh tokens 30 days,
sessions cap at 12 participants, execution cooldown is 5 seconds, and the
sandbox limits in `app.execution` are validated on every run and must not be
changed.

---

## Repository layout

```
src/main/java/com/collabeditor/
  auth/         registration, JWT, refresh rotation, security config
  session/      session lifecycle, invite codes, cleanup scheduler
  websocket/    room handler, handshake auth, presence, relay gateway
  ot/           transform engine, session runtime, durable operation log
  snapshot/     periodic checkpoints and replay-based recovery
  redis/        revision counters, locks, cross-instance pub/sub
  execution/    admission, queue, Docker sandbox runner, result relay
src/main/resources/
  db/migration/ Flyway migrations
  static/       the browser client
scripts/        client OT convergence harness
docs/           API, protocol, and architecture reference
```

---

## Planning artifacts and agentic tooling

This repository was developed with AI coding assistants, and keeps the
planning trail in `.planning/` along with `AGENTS.md` and `CLAUDE.md` at the
root. None of it is part of the application runtime — it exists so the
architecture, constraints, and delivery history do not have to be
re-discovered. Delete it freely if you are forking this for your own use.
