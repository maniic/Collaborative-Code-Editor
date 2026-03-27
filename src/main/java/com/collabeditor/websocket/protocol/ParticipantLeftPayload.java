package com.collabeditor.websocket.protocol;

import java.util.UUID;

/**
 * Payload for {@code participant_left}: broadcast to all room sockets
 * when a participant disconnects.
 *
 * @param userId the leaving participant's user ID
 * @param email  the leaving participant's email
 */
public record ParticipantLeftPayload(
        UUID userId,
        String email
) {
}
