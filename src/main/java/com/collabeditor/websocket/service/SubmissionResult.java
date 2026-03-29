package com.collabeditor.websocket.service;

import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.TextOperation;

/**
 * Result of submitting an operation through the distributed collaboration gateway.
 *
 * @param revision           the assigned canonical revision
 * @param canonicalOperation the operation as transformed and applied to the canonical document
 * @param snapshot           the document snapshot after applying the operation
 */
public record SubmissionResult(
        long revision,
        TextOperation canonicalOperation,
        DocumentSnapshot snapshot
) {
}
