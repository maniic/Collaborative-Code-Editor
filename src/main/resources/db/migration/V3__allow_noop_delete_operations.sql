-- Operational transforms can annul an operation into a no-op delete
-- (length = 0): delete/delete overlap consumes an entire range, and an
-- insert strictly inside a concurrent delete is annulled for TP1
-- convergence. No-op operations still occupy a canonical revision and are
-- persisted in the operation log, so the length check must permit 0.

ALTER TABLE session_operations
    DROP CONSTRAINT chk_session_operations_delete;

ALTER TABLE session_operations
    ADD CONSTRAINT chk_session_operations_delete
        CHECK (operation_type != 'DELETE' OR (text IS NULL AND length IS NOT NULL AND length >= 0));
