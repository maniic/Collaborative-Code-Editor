---
phase: 1
slug: secure-access-and-session-lifecycle
status: ready
nyquist_compliant: true
wave_0_complete: false
created: 2026-03-26
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Spring Security Test |
| **Config file** | `build.gradle.kts` |
| **Quick run command** | `./gradlew test --tests "*Auth*" --tests "*Session*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~20-60 seconds once the project skeleton exists |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*Auth*" --tests "*Session*"`
- **After every plan wave:** Run `./gradlew test`
- **Before `$gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 01-01-01 | 01 | 1 | QUAL-01 | migration smoke | `./gradlew test --tests "*Migration*"` | ❌ 01-01 | ⬜ pending |
| 01-02-01 | 02 | 2 | AUTH-01, AUTH-02, AUTH-03 | unit + controller slice | `./gradlew test --tests "*Auth*"` | ❌ 01-01 | ⬜ pending |
| 01-03-01 | 03 | 3 | SESS-01, SESS-02, SESS-03, SESS-04 | service + controller slice | `./gradlew test --tests "*Session*"` | ❌ 01-02 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `gradlew` and `build.gradle.kts` — Gradle wrapper and Spring Boot build with testing dependencies
- [ ] `src/test/resources/application-test.yml` — shared test configuration for migration and slice tests
- [ ] `src/test/java/.../migration/` — Flyway migration smoke test
- [ ] `src/main/resources/db/migration/V1__phase1_baseline.sql` — Flyway migration baseline

*If none: "Existing infrastructure covers all phase requirements."*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Refresh cookie uses secure httpOnly semantics expected by the chosen auth contract | AUTH-03 | Cookie flags and browser behavior are awkward to fully prove with unit tests alone at this phase | Call refresh/login endpoints, inspect `Set-Cookie`, and confirm httpOnly-style refresh behavior matches the contract |
| Invite-code sharing flow is clear while UUID remains internal | SESS-03 | API semantics may be technically correct but confusing without a contract review | Review endpoint and DTO design to confirm invite code is the user-facing join identifier |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 60s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-03-26
