package com.collabeditor.websocket.protocol;

import com.collabeditor.websocket.model.SelectionRange;

/**
 * Payload for {@code update_presence}: sent by client to update their
 * cursor/selection position in the session.
 *
 * @param selection the client's current selection range (caret if start == end)
 */
public record PresenceUpdatePayload(
        SelectionRange selection
) {
}
