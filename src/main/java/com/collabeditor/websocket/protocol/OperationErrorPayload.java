package com.collabeditor.websocket.protocol;

/**
 * Payload for {@code operation_error}: sent to the submitting client when an
 * operation is malformed or invalid.
 *
 * @param clientOperationId the client-assigned operation ID that failed (null if unparseable)
 * @param error             human-readable error description
 */
public record OperationErrorPayload(
        String clientOperationId,
        String error
) {
}
