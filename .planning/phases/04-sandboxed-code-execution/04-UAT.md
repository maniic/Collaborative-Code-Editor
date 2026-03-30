---
status: complete
phase: 04-sandboxed-code-execution
source:
  - 04-01-SUMMARY.md
  - 04-02-SUMMARY.md
  - 04-03-SUMMARY.md
  - 04-04-SUMMARY.md
started: 2026-03-29T21:39:05Z
updated: 2026-03-30T01:45:41Z
---

## Current Test

[testing complete]

## Tests

### 1. Live Async Python Execution
expected: Call `POST /api/sessions/{sessionId}/executions` for a Python room while connected to `/ws/sessions/{sessionId}`. HTTP should return `202 Accepted` immediately with queued execution metadata, then the websocket should emit ordered `execution_updated` events ending in `COMPLETED` with Python stdout.
result: pass

### 2. Live Java Execution
expected: Enqueue a valid package-less Java `Main` program. The run should complete successfully and the final `execution_updated` payload should contain Java stdout with a terminal `COMPLETED` status.
result: pass

### 3. Invalid Java Contract Failure
expected: Enqueue Java source that is missing a package-less `public class Main` or `public static void main(String[] args)`. The execution should fail predictably instead of hanging, and stderr should explain the Java contract violation.
result: pass

### 4. Timeout Enforcement
expected: Enqueue code with an infinite loop. The execution should transition through `QUEUED` and `RUNNING`, then finish as `TIMED_OUT` after roughly ten seconds instead of running indefinitely.
result: pass

### 5. No-Network Sandbox Isolation
expected: Enqueue code that attempts an outbound network connection. The execution should fail inside the sandbox, and the final payload should show failure details rather than successful network access.
result: pass

### 6. Read-Only Filesystem Isolation
expected: Enqueue code that tries to write outside the writable tmpfs paths. The execution should fail and stderr should reflect the read-only or permission-denied filesystem constraint.
result: pass

### 7. Per-User Cooldown Enforcement
expected: Submit two execution requests from the same user within five seconds. The second request should be rejected with HTTP `429 Too Many Requests` instead of being queued.
result: pass

### 8. Cross-Instance Relay Ordering
expected: Run two backend instances against the same PostgreSQL and Redis stack, connect one websocket client to each instance for the same room, and enqueue one execution. Both clients should observe the same ordered `execution_updated` lifecycle sequence with the same terminal result.
result: pass

## Summary

total: 8
passed: 8
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
