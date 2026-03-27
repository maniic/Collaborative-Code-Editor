package com.collabeditor.websocket.protocol;

import java.util.UUID;

/**
 * Payload for {@code operation_applied}: broadcast to all session participants
 * (including the sender) after a canonical operation is accepted.
 *
 * @param userId        the author of the operation
 * @param revision      the canonical revision assigned to the operation
 * @param operationType "INSERT" or "DELETE"
 * @param position      character index where the canonical operation applies
 * @param text          the inserted text (for INSERT operations, null for DELETE)
 * @param length        the delete length (for DELETE operations, null for INSERT)
 */
public record OperationAppliedPayload(
        UUID userId,
        long revision,
        String operationType,
        int position,
        String text,
        Integer length
) {
}
