package com.collabeditor.ot.model;

import java.util.UUID;

/**
 * Multi-character insert operation: inserts {@code text} at {@code position}.
 *
 * @param authorUserId      the user who authored this operation
 * @param baseRevision      the canonical revision this operation was composed against
 * @param clientOperationId client-assigned ID for deduplication and acknowledgement
 * @param position          character index where text is inserted (0-based, inclusive)
 * @param text              the text to insert (must be non-null, non-empty)
 */
public record InsertOperation(
        UUID authorUserId,
        long baseRevision,
        String clientOperationId,
        int position,
        String text
) implements TextOperation {

    public InsertOperation {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Insert text must not be null or empty");
        }
    }

    /**
     * Returns a new InsertOperation with the given position, preserving all other fields.
     */
    public InsertOperation withPosition(int newPosition) {
        return new InsertOperation(authorUserId, baseRevision, clientOperationId, newPosition, text);
    }
}
