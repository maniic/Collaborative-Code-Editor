package com.collabeditor.websocket.protocol;

import java.util.UUID;

/**
 * Participant identity snapshot for document_sync bootstrap.
 *
 * @param userId the participant's user ID
 * @param email  the participant's email
 */
public record ParticipantInfo(
        UUID userId,
        String email
) {
}
