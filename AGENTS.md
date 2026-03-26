# Collaborative Code Editor

## Project

Real-time collaborative code editor backend built in Java with Spring Boot 3. Multiple users join coding sessions via WebSocket, edit the same document simultaneously with server-authoritative Operational Transform, and execute code together in Docker-sandboxed containers.

**Core Value:** The OT engine must guarantee document convergence so every participant ends up with the same document after concurrent edits.

## Constraints

- **Tech stack:** Java 17+, Spring Boot 3, PostgreSQL, Redis, Docker
- **OT implementation:** From scratch, no OT libraries
- **Execution sandbox:** Docker containers only
- **Scale target:** 2-3 backend instances
- **Package structure:** `auth`, `session`, `websocket`, `ot`, `execution`, `redis`, `snapshot`

## Workflow Enforcement

Before using edit tools or changing repository files, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:
- `$gsd-quick` for small fixes, doc updates, and ad-hoc tasks
- `$gsd-debug` for investigation and bug fixing
- `$gsd-execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.

## Planning Sources

Use these documents as the project source of truth:
- `.planning/PROJECT.md`
- `.planning/REQUIREMENTS.md`
- `.planning/ROADMAP.md`
- `.planning/STATE.md`

`CLAUDE.md` contains the generated GSD project and stack summary for tools that read that file.
