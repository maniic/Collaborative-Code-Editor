package com.collabeditor.ot.model;

/**
 * An operation that has been transformed and applied to the canonical document,
 * together with its assigned canonical revision number.
 *
 * @param revision  the canonical revision assigned after applying this operation
 * @param operation the transformed operation as it was applied to the canonical document
 */
public record AppliedOperation(
        long revision,
        TextOperation operation
) {
}
