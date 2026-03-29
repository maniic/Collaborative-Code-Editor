# Phase 3: Durable Persistence and Multi-Instance Coordination - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-03-29
**Phase:** 03-durable-persistence-and-multi-instance-coordination
**Areas discussed:** Durable write model, Snapshot and recovery flow, Redis coordination role, Cross-instance relay semantics

---

## Durable Write Model

### Q1. When should an accepted operation become durable?

| Option | Description | Selected |
|--------|-------------|----------|
| Sync on accept | Persist each accepted canonical operation and revision before `operation_ack` or participant broadcast so an acknowledged edit survives restart. | ✓ |
| Short batch flush | Acknowledge from memory first and flush operations to PostgreSQL in small batches shortly afterward. | |
| Snapshot-weighted durability | Keep accepted edits mostly in memory and rely more heavily on periodic snapshots than per-operation durability. | |

**User's choice:** Delegated to the agent; recommended option applied.
**Notes:** This keeps acknowledged edits durable and aligns with the phase goal that session state survives restarts.

---

## Snapshot and Recovery Flow

### Q1. How should the server rebuild canonical document state?

| Option | Description | Selected |
|--------|-------------|----------|
| Snapshot plus replay on demand | Restore a session from the latest snapshot and replay only later canonical operations when the room becomes active again. | ✓ |
| Replay the entire operation log every time | Simpler model, but restart and cold-room recovery cost grows with session history. | |
| Keep startup-wide warm caches | Preload many or all sessions at boot so rebuilds happen eagerly instead of lazily. | |

**User's choice:** Delegated to the agent; recommended option applied.
**Notes:** Chosen to meet the snapshot requirement while keeping restart recovery and cold-start cost bounded.

---

## Redis Coordination Role

### Q1. What should Redis own in Phase 3?

| Option | Description | Selected |
|--------|-------------|----------|
| Active coordination only | Redis coordinates active session state, apply sequencing, and revision advancement, while PostgreSQL remains the durable record. | ✓ |
| Full source of truth | Redis holds the primary canonical document state and history, with PostgreSQL as a secondary persistence target. | |
| Relay only | Redis is used only for pub/sub fan-out, leaving revision coordination entirely elsewhere. | |

**User's choice:** Delegated to the agent; recommended option applied.
**Notes:** This matches the project-level Redis decision without turning Redis into the long-term system of record.

---

## Cross-Instance Relay Semantics

### Q1. How should collaboration events reach sockets on different instances?

| Option | Description | Selected |
|--------|-------------|----------|
| Canonical pub/sub relay path | Publish canonical collaboration events through Redis pub/sub and have each instance fan out to its own local sockets from that same relay path. | ✓ |
| Origin-instance direct broadcast | Let the instance that accepts an operation broadcast locally and relay a second path only for remote instances. | |
| Best-effort relay | Use pub/sub opportunistically and tolerate local revision drift until a later reconnect. | |

**User's choice:** Delegated to the agent; recommended option applied.
**Notes:** Using one canonical relay path reduces split-brain logic and makes revision-gap handling explicit.

---

## the agent's Discretion

- Exact table and index design for durable collaboration storage.
- Exact Redis key, channel, and TTL naming.
- Exact locking or atomic sequencing strategy for per-session canonical apply.
- Exact runtime-eviction and extra snapshot heuristics.

## Deferred Ideas

- User-facing history replay API or UI.
- Durable presence restoration across restarts.
