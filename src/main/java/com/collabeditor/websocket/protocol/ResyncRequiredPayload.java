package com.collabeditor.websocket.protocol;

/**
 * Payload for {@code resync_required}: sent when the server detects an
 * unrecoverable desync and the client must re-bootstrap.
 *
 * @param document the full current canonical document text
 * @param revision the current canonical revision number
 * @param reason   human-readable reason for the resync
 */
public record ResyncRequiredPayload(
        String document,
        long revision,
        String reason
) {
}
