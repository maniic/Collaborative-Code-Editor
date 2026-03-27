package com.collabeditor.websocket.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed envelope for all collaboration WebSocket messages.
 * Both client-to-server and server-to-client messages use the same shape:
 * {@code {"type": "...", "payload": {...}}}.
 *
 * @param type    the message type string (matching ClientMessageType or ServerMessageType)
 * @param payload the type-specific payload as a JSON node
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollaborationEnvelope(
        String type,
        JsonNode payload
) {
}
