package com.collabeditor.websocket.protocol;

import com.collabeditor.websocket.model.SelectionRange;

import java.util.UUID;

/**
 * Payload for {@code presence_updated}: broadcast to all room sockets
 * when a participant's cursor/selection changes.
 *
 * @param userId    the participant whose presence changed
 * @param email     the participant's email
 * @param selection the participant's current selection range
 */
public record PresenceUpdatedPayload(
        UUID userId,
        String email,
        SelectionRange selection
) {
}
