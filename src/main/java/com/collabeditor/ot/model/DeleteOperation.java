package com.collabeditor.ot.model;

import java.util.UUID;

/**
 * Multi-character delete operation: deletes {@code length} characters starting at {@code position}.
 *
 * @param authorUserId      the user who authored this operation
 * @param baseRevision      the canonical revision this operation was composed against
 * @param clientOperationId client-assigned ID for deduplication and acknowledgement
 * @param position          character index where deletion starts (0-based, inclusive)
 * @param length            number of characters to delete (must be > 0 for original ops; 0 for no-ops after transform)
 */
public record DeleteOperation(
        UUID authorUserId,
        long baseRevision,
        String clientOperationId,
        int position,
        int length
) implements TextOperation {

    /**
     * Returns a new DeleteOperation with the given position and length, preserving other fields.
     */
    public DeleteOperation withPositionAndLength(int newPosition, int newLength) {
        return new DeleteOperation(authorUserId, baseRevision, clientOperationId, newPosition, newLength);
    }
}
