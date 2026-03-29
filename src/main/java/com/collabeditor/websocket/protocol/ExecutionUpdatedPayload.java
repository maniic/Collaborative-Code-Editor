package com.collabeditor.websocket.protocol;

import java.time.Instant;
import java.util.UUID;

public record ExecutionUpdatedPayload(
        UUID executionId,
        UUID requestedByUserId,
        String requestedByEmail,
        String language,
        long sourceRevision,
        String status,
        String stdout,
        String stderr,
        Integer exitCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String message
) {
}
