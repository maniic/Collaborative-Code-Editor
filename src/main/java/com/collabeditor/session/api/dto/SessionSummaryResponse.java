package com.collabeditor.session.api.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionSummaryResponse(
    UUID sessionId,
    String inviteCode,
    String language,
    UUID ownerUserId,
    int participantCap,
    Instant createdAt
) {}
