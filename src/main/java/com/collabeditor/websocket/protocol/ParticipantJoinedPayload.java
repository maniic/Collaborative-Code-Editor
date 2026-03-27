package com.collabeditor.websocket.protocol;

import java.util.UUID;

/**
 * Payload for {@code participant_joined}: broadcast to all room sockets
 * when a new participant connects.
 *
 * @param userId the joining participant's user ID
 * @param email  the joining participant's email
 */
public record ParticipantJoinedPayload(
        UUID userId,
        String email
) {
}
