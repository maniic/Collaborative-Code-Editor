package com.collabeditor.redis.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical relay payload for collaboration events published through Redis pub/sub.
 *
 * <p>Every backend instance publishes and consumes the same typed event structure.
 * Jackson serialization/deserialization is used for transport through Redis channels.
 */
public record CanonicalCollaborationEvent(
        UUID sessionId,
        CanonicalEventType eventType,
        long revision,
        UUID userId,
        String payloadJson,
        Instant emittedAt
) {
}
