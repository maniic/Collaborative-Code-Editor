---
status: complete
phase: 03-durable-persistence-and-multi-instance-coordination
source:
  - 03-01-SUMMARY.md
  - 03-02-SUMMARY.md
  - 03-03-SUMMARY.md
  - 03-04-SUMMARY.md
started: 2026-03-29T09:02:54Z
updated: 2026-03-29T09:52:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Cold Start Smoke Test
expected: Stop any running backend instances, then start the application from scratch against PostgreSQL and Redis. The backend should boot without startup errors, Flyway should complete cleanly, and a basic health or authenticated API request should succeed against the fresh process.
result: pass

### 2. Persisted Document Bootstrap After Restart
expected: Edit a collaboration session so the document text and canonical revision are non-zero, restart the backend, then reconnect to `/ws/sessions/{sessionId}`. The first `document_sync` frame should contain the persisted document and canonical revision instead of an empty room.
result: pass

### 3. Cross-Instance Canonical Operation Relay
expected: Run two backend instances against the same PostgreSQL and Redis stack, connect one client to each instance, and submit an INSERT from one side. The sender should receive `operation_ack`, both clients should receive `operation_applied` with the same canonical revision, and both views should settle on the same document text.
result: pass

### 4. Cross-Instance Presence and Join or Leave Relay
expected: With one client connected to each backend instance, connect or disconnect one participant and send `update_presence` from that side. The other client should receive `participant_joined` or `participant_left` plus `presence_updated` with the correct `userId`, `email`, and selection data through the shared relay path.
result: pass

## Summary

total: 4
passed: 4
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
