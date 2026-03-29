# Phase 4: Sandboxed Code Execution - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-03-29
**Phase:** 04-sandboxed-code-execution
**Areas discussed:** Execution Request and Result Flow, Language Runtime Contract, Queueing and Failure Semantics

---

## Execution Request and Result Flow

| Option | Description | Selected |
|--------|-------------|----------|
| REST enqueue + WebSocket lifecycle | Authenticated REST request enqueues a run; active room sockets receive queued/running/finished execution events and final structured output. | ✓ |
| WebSocket-only command | Execution is triggered and resolved entirely over the collaboration socket. | |
| Synchronous REST result | HTTP request blocks until the container finishes and returns the final output inline. | |

**User's choice:** Agent discretion
**Notes:** User said "Do as you best see fit." Recommended default chosen: REST is a better fit for queue admission and idempotent request semantics, while additive WebSocket events preserve the collaborative realtime experience.

---

## Language Runtime Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Strict explicit entrypoints | Python runs as a script; Java requires a package-less `Main` class with `public static void main(String[] args)`. | ✓ |
| Inferred Java main class | Compile the document and try to detect whichever public class should be executed. | |
| Auto-wrap snippets | Transform incomplete Java code into a runnable wrapper automatically. | |

**User's choice:** Agent discretion
**Notes:** Recommended default chosen: explicit runtime contracts are more predictable, easier to document, and easier to secure than snippet rewriting or entrypoint inference.

---

## Queueing and Failure Semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Bounded FIFO + explicit rejection | Accepted runs enter a bounded FIFO queue; cooldown violations and full-queue requests fail fast with clear status/error feedback. | ✓ |
| Always queue eventually | Every request waits until it can run, regardless of queue length or user cooldown timing. | |
| Single-slot replace behavior | New requests replace older queued requests from the same user or room. | |

**User's choice:** Agent discretion
**Notes:** Recommended default chosen: fail-fast feedback is easier for clients to reason about and avoids hidden latency spikes or surprising request replacement.

---

## the agent's Discretion

- Exact execution DTO field names and WebSocket event names
- Exact worker-pool size and queue capacity
- Exact Docker image tags, command lines, and temporary writable-mount layout
- Exact terminal status vocabulary beyond the locked queued/running/terminal lifecycle model

## Deferred Ideas

- Live log streaming instead of final structured output blobs
- Java snippet wrapping or inferred entrypoints
- Multi-file project execution
- Additional languages beyond Java and Python
