package com.collabeditor.websocket.protocol;

/**
 * Message types that the server sends to clients over the collaboration WebSocket.
 */
public enum ServerMessageType {
    document_sync,
    operation_ack,
    operation_applied,
    operation_error,
    resync_required,
    participant_joined,
    participant_left,
    presence_updated,
    execution_updated
}
