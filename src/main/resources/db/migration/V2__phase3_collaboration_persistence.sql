-- Phase 3: Durable Collaboration Persistence
-- Tables: session_operations, document_snapshots, execution_history

-- ============================================================
-- Session Operations (append-only canonical operation log)
-- ============================================================
CREATE TABLE session_operations (
    id                  UUID PRIMARY KEY,
    session_id          UUID         NOT NULL REFERENCES coding_sessions(id) ON DELETE CASCADE,
    revision            BIGINT       NOT NULL,
    author_user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_operation_id VARCHAR(128) NOT NULL,
    operation_type      VARCHAR(16)  NOT NULL,
    position            INTEGER      NOT NULL,
    text                TEXT,
    length              INTEGER,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Only INSERT or DELETE allowed
    CONSTRAINT chk_session_operations_type
        CHECK (operation_type IN ('INSERT', 'DELETE')),

    -- Position must be non-negative
    CONSTRAINT chk_session_operations_position
        CHECK (position >= 0),

    -- INSERT operations require non-null text and null length
    CONSTRAINT chk_session_operations_insert
        CHECK (operation_type != 'INSERT' OR (text IS NOT NULL AND length IS NULL)),

    -- DELETE operations require null text and length > 0
    CONSTRAINT chk_session_operations_delete
        CHECK (operation_type != 'DELETE' OR (text IS NULL AND length IS NOT NULL AND length > 0)),

    -- One canonical revision per session
    CONSTRAINT uq_session_operations_session_revision
        UNIQUE (session_id, revision),

    -- Idempotency guard: one client operation per author per session
    CONSTRAINT uq_session_operations_client_op
        UNIQUE (session_id, author_user_id, client_operation_id)
);

CREATE INDEX idx_session_operations_session_revision
    ON session_operations (session_id, revision);

CREATE INDEX idx_session_operations_session_created
    ON session_operations (session_id, created_at);

-- ============================================================
-- Document Snapshots (periodic full-document state)
-- ============================================================
CREATE TABLE document_snapshots (
    id         UUID PRIMARY KEY,
    session_id UUID        NOT NULL REFERENCES coding_sessions(id) ON DELETE CASCADE,
    revision   BIGINT      NOT NULL,
    document   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_document_snapshots_session_revision
        UNIQUE (session_id, revision)
);

CREATE INDEX idx_document_snapshots_session_revision_desc
    ON document_snapshots (session_id, revision DESC);

-- ============================================================
-- Execution History (schema foundation for Phase 4)
-- ============================================================
CREATE TABLE execution_history (
    id                   UUID PRIMARY KEY,
    session_id           UUID         NOT NULL REFERENCES coding_sessions(id) ON DELETE CASCADE,
    requested_by_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    language             VARCHAR(16)  NOT NULL,
    source_revision      BIGINT       NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    stdout               TEXT,
    stderr               TEXT,
    exit_code            INTEGER,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ
);
