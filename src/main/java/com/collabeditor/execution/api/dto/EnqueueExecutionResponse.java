package com.collabeditor.execution.api.dto;

import java.util.UUID;

public record EnqueueExecutionResponse(
        UUID executionId,
        UUID sessionId,
        String language,
        long sourceRevision,
        String status
) {
}
