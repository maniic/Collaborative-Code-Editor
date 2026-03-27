package com.collabeditor.websocket.protocol;

/**
 * Payload for {@code operation_ack}: sent to the submitting client after a
 * successful operation application.
 *
 * @param clientOperationId the client-assigned operation ID being acknowledged
 * @param revision          the canonical revision assigned to the operation
 */
public record OperationAckPayload(
        String clientOperationId,
        long revision
) {
}
