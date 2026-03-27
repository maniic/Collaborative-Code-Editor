-- Phase 1 Baseline Schema: Auth and Session Lifecycle
-- Tables: users, refresh_sessions, coding_sessions, session_participants

-- ============================================================
-- Users
-- ============================================================
CREATE TABLE users (
    id         UUID PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Functional unique index for case-insensitive email uniqueness
CREATE UNIQUE INDEX uq_users_email ON users (LOWER(email));

-- ============================================================
-- Refresh Sessions (per-device token rotation)
-- ============================================================
CREATE TABLE refresh_sessions (
    id                     UUID PRIMARY KEY,
    user_id                UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash             VARCHAR(128) NOT NULL,
    device_id              UUID,
    user_agent             TEXT,
    expires_at             TIMESTAMPTZ  NOT NULL,
    revoked_at             TIMESTAMPTZ,
    replaced_by_session_id UUID,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_sessions_token_hash ON refresh_sessions (token_hash);
CREATE INDEX idx_refresh_sessions_user_id ON refresh_sessions (user_id);

-- ============================================================
-- Coding Sessions
-- ============================================================
CREATE TABLE coding_sessions (
    id              UUID PRIMARY KEY,
    invite_code     VARCHAR(8)  NOT NULL,
    language        VARCHAR(16) NOT NULL,
    owner_user_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    participant_cap SMALLINT    NOT NULL DEFAULT 12,
    empty_since     TIMESTAMPTZ,
    cleanup_after   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_coding_sessions_invite_code UNIQUE (invite_code),
    CHECK (language IN ('JAVA', 'PYTHON'))
);

CREATE INDEX idx_coding_sessions_invite_code ON coding_sessions (invite_code);

-- ============================================================
-- Session Participants
-- ============================================================
CREATE TABLE session_participants (
    session_id UUID        NOT NULL REFERENCES coding_sessions(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL DEFAULT 'PARTICIPANT',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,
    CONSTRAINT uq_session_participants_membership UNIQUE (session_id, user_id)
);

CREATE INDEX idx_session_participants_session_status
    ON session_participants (session_id, status);
CREATE INDEX idx_session_participants_user_id
    ON session_participants (user_id);
