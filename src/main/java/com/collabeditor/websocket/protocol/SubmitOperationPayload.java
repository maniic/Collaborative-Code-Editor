package com.collabeditor.websocket.protocol;

/**
 * Payload for {@code submit_operation}: sent by a client to apply an edit.
 *
 * @param clientOperationId client-assigned ID for deduplication and acknowledgement
 * @param baseRevision      the canonical revision this operation was composed against
 * @param operationType     either "INSERT" or "DELETE"
 * @param position          character index where the operation applies (0-based)
 * @param text              the text to insert (present for INSERT, null for DELETE)
 * @param length            the number of characters to delete (present for DELETE, null for INSERT)
 */
public record SubmitOperationPayload(
        String clientOperationId,
        long baseRevision,
        String operationType,
        int position,
        String text,
        Integer length
) {
}
