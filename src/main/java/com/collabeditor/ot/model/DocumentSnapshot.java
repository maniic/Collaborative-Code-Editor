package com.collabeditor.ot.model;

/**
 * Immutable snapshot of a collaboration session's canonical document state.
 * Used for connect/resync flows to give a client the full current state.
 *
 * @param document the current canonical document text
 * @param revision the current canonical revision number
 */
public record DocumentSnapshot(
        String document,
        long revision
) {
}
