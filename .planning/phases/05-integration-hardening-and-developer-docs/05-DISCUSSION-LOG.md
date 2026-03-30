# Phase 5: Integration Hardening and Developer Docs - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-03-29
**Phase:** 05-integration-hardening-and-developer-docs
**Areas discussed:** Integration verification shape, local reproduction workflow, README contract, architecture and decision rationale

---

## Integration Verification Shape

| Option | Description | Selected |
|--------|-------------|----------|
| Single mega-suite | One monolithic end-to-end Testcontainers test proves everything in one place. | |
| Focused suites behind one command | Keep focused integration tests, but expose one obvious canonical verification run for developers. | ✓ |
| Existing fragmented tests only | Leave tests class-by-class with no canonical proof path. | |

**User's choice:** Delegated to the agent; selected the recommended default of focused integration suites behind one canonical verification command.
**Notes:** This satisfies the roadmap's "one automated run" requirement without coupling all infrastructure proof into one brittle test class.

---

## Local Reproduction Workflow

| Option | Description | Selected |
|--------|-------------|----------|
| Full-stack Compose canonical | `docker-compose` starts app, PostgreSQL, and Redis as the primary documented path. | ✓ |
| Infra-only Compose canonical | Compose starts only PostgreSQL and Redis, while developers always run the app via Gradle. | |
| Manual startup docs only | Document separate startup commands without a canonical Compose flow. | |

**User's choice:** Delegated to the agent; selected full-stack Compose as the canonical path.
**Notes:** An infra-only local-app path can still be documented as a secondary inner-loop workflow, but the roadmap explicitly requires the backend to be startable with Compose.

---

## README Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Quickstart only | Minimal setup notes with little contract detail. | |
| Practical reference | Quickstart, env vars, verification commands, representative REST/WebSocket examples, architecture notes, and design rationale. | ✓ |
| Exhaustive reference manual | Full endpoint/message catalog and deep reference material for every DTO and behavior. | |

**User's choice:** Delegated to the agent; selected the practical reference approach.
**Notes:** This is the best fit for Phase 5 because the README is currently empty and needs to become useful quickly without turning into generated API docs.

---

## Architecture and Decision Rationale

| Option | Description | Selected |
|--------|-------------|----------|
| Single high-signal architecture diagram | One system diagram plus concise explanation of the key design decisions. | ✓ |
| Multiple deep flow diagrams | Separate detailed diagrams for OT, persistence, relay, and execution flows. | |
| No diagram | Rely on prose only. | |

**User's choice:** Delegated to the agent; selected one high-signal architecture diagram plus concise rationale.
**Notes:** This gives strong onboarding and portfolio value without pushing Phase 5 into documentation sprawl.

---

## the agent's Discretion

- Exact Gradle task/suite packaging for the canonical verification run.
- Exact `docker-compose.yml` service structure and healthcheck approach.
- Exact README layout, example payloads, and diagram format.

## Deferred Ideas

None.
