package com.collabeditor.websocket.protocol;

import java.util.List;

/**
 * Payload for {@code document_sync}: sent immediately after connection to bootstrap
 * the client with the current canonical document state and active participants.
 *
 * @param document     the full current document text
 * @param revision     the current canonical revision number
 * @param participants snapshot of active participants in the session
 */
public record DocumentSyncPayload(
        String document,
        long revision,
        List<ParticipantInfo> participants
) {
}
