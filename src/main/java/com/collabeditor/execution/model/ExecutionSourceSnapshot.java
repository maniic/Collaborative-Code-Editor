package com.collabeditor.execution.model;

import java.util.UUID;

/**
 * Immutable capture of the canonical room source at execution enqueue time.
 */
public record ExecutionSourceSnapshot(
        UUID sessionId,
        UUID requestedByUserId,
        String requestedByEmail,
        String language,
        long sourceRevision,
        String sourceCode
) {}
