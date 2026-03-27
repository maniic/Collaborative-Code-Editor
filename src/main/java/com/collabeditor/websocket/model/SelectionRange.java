package com.collabeditor.websocket.model;

/**
 * Represents a text selection range in the editor.
 * A caret (cursor) is represented as a zero-length range where {@code start == end}.
 *
 * @param start the start character index (inclusive, 0-based)
 * @param end   the end character index (inclusive, 0-based)
 */
public record SelectionRange(int start, int end) {

    public SelectionRange {
        if (start < 0) {
            throw new IllegalArgumentException("Selection start must be >= 0, got: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException(
                    "Selection end (%d) must be >= start (%d)".formatted(end, start));
        }
    }
}
