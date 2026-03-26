# Phase 1: Secure Access and Session Lifecycle - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-03-26
**Phase:** 1-secure-access-and-session-lifecycle
**Areas discussed:** Authentication Contract, Session Discovery and Join Flow, Ownership and Lifecycle

---

## Authentication Contract

| Option | Description | Selected |
|--------|-------------|----------|
| Bearer + cookie | Short-lived JWT access token in `Authorization`, refresh token in an httpOnly cookie. | ✓ |
| Bearer + bearer | Both access and refresh tokens are returned in JSON and sent explicitly by the client. | |
| Long-lived JWT | Use a longer-lived access token and avoid a separate refresh token model. | |

**User's choice:** Bearer + cookie  
**Notes:** Chosen together with short-lived access tokens, multi-device refresh sessions, and later confirmed refresh-token rotation.

| Option | Description | Selected |
|--------|-------------|----------|
| Short | Around 10-15 minutes, leaning on refresh for renewal. | ✓ |
| Medium | Around 1 hour, fewer refreshes but wider exposure window. | |
| Long | Multiple hours or more, simplest client flow but weakest security posture. | |

**User's choice:** Short  
**Notes:** User wanted a tighter security posture and was comfortable relying on refresh.

| Option | Description | Selected |
|--------|-------------|----------|
| Multi-device | Allow separate refresh sessions per device/browser. | ✓ |
| Single-session | A new login invalidates prior refresh sessions. | |
| You decide | Leave the exact session policy open for planning. | |

**User's choice:** Multi-device  
**Notes:** This locks in a per-device refresh-session model.

| Option | Description | Selected |
|--------|-------------|----------|
| Rotate on refresh | Issue a new refresh token every successful refresh and invalidate the old one. | ✓ |
| Stable token | Keep the same refresh token valid until expiry or manual invalidation. | |
| You decide | Let planning choose the exact rotation policy. | |

**User's choice:** Rotate on refresh  
**Notes:** This makes refresh-token persistence and invalidation an explicit Phase 1 concern.

## Session Discovery and Join Flow

| Option | Description | Selected |
|--------|-------------|----------|
| All joinable | List all sessions that are currently open and joinable. | |
| Mine only | Only list sessions the user created or already joined. | ✓ |
| Private default | Don’t expose a broad list; joining is mostly by explicit identifier. | |

**User's choice:** Mine only  
**Notes:** The user preferred a more private discovery model than a global public room directory.

| Option | Description | Selected |
|--------|-------------|----------|
| UUID | Use the session UUID directly as the canonical backend identifier. | |
| Invite code | Introduce a separate human-friendly join code now. | |
| Both | Keep a UUID internally but also expose a friendly join code in Phase 1. | ✓ |

**User's choice:** Both  
**Notes:** This was narrowed by a follow-up decision that invite codes are the human-facing share path.

| Option | Description | Selected |
|--------|-------------|----------|
| Immutable | Choose Python or Java at creation time and keep it fixed. | ✓ |
| Change before join | Creator can change it until another participant joins. | |
| Owner editable | Allow the owner to change it later as part of room management. | |

**User's choice:** Immutable  
**Notes:** Language selection is part of room creation and should not drift afterward.

| Option | Description | Selected |
|--------|-------------|----------|
| Invite code | Use the friendly code for sharing; keep the UUID as the internal primary key. | ✓ |
| Either | Expose both and let clients/users choose which one to share. | |
| UUID | Keep the UUID as the main identifier users also share directly. | |

**User's choice:** Invite code  
**Notes:** This resolves the earlier “both” choice into a clear human-facing join path.

## Ownership and Lifecycle

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, creator owns it | The creator is the owner and gets any owner-only room powers. | ✓ |
| No special owner | After creation, all participants are effectively peers. | |
| You decide | Leave ownership semantics open for planning. | |

**User's choice:** Yes, creator owns it  
**Notes:** Ownership should exist from Phase 1 rather than being deferred.

| Option | Description | Selected |
|--------|-------------|----------|
| Transfer owner | Keep the room alive and hand ownership to another participant. | ✓ |
| No owner after exit | Room stays alive but loses any special owner state. | |
| Close room | The session ends when the creator leaves. | |

**User's choice:** Transfer owner  
**Notes:** Room continuity matters more than tying room lifetime to the original creator.

| Option | Description | Selected |
|--------|-------------|----------|
| Keep with TTL | Persist the session for a while so users can reconnect, then clean it up after inactivity. | ✓ |
| Delete immediately | Remove the session as soon as the room becomes empty. | |
| Persist until manual cleanup | Keep empty rooms unless an explicit cleanup job or owner action removes them. | |

**User's choice:** Keep with TTL  
**Notes:** This led to a follow-up decision on the rough TTL length.

| Option | Description | Selected |
|--------|-------------|----------|
| About 1 hour | Long enough for reconnects without accumulating stale rooms. | ✓ |
| About 15 minutes | Aggressive cleanup with a shorter reconnect window. | |
| About 24 hours | Favor convenience and history over aggressive cleanup. | |

**User's choice:** About 1 hour  
**Notes:** The reconnect window should be meaningful but not leave rooms hanging around indefinitely.

| Option | Description | Selected |
|--------|-------------|----------|
| Soft cap ~8-16 | Keep collaboration sessions small and predictable for the first milestone. | ✓ |
| No explicit cap | Let infrastructure and later phases define practical limits. | |
| Very small cap | Lock rooms to tiny groups, such as 2-4 participants. | |

**User's choice:** Soft cap ~8-16  
**Notes:** This keeps Phase 1 predictable without prematurely optimizing for large-room collaboration.

## the agent's Discretion

- Exact JWT claim layout and cookie naming
- Exact invite-code format
- Owner-transfer selection rule
- Concrete empty-room cleanup implementation
- Exact participant limit within the chosen soft-cap range

## Deferred Ideas

None — discussion stayed within phase scope.
