package com.collabeditor.ot.service;

import com.collabeditor.ot.model.AppliedOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.TextOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory canonical state holder for one collaboration session.
 * Serializes operation processing per session using a ReentrantLock.
 */
public class CollaborationSessionRuntime {

    private final UUID sessionId;
    private final OperationalTransformService otService;
    private final StringBuilder document;
    private long revision;
    private final List<AppliedOperation> history;
    private final ReentrantLock lock;

    public CollaborationSessionRuntime(UUID sessionId, OperationalTransformService otService) {
        this.sessionId = sessionId;
        this.otService = otService;
        this.document = new StringBuilder();
        this.revision = 0;
        this.history = new ArrayList<>();
        this.lock = new ReentrantLock();
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public DocumentSnapshot snapshot() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public ApplyResult applyClientOperation(TextOperation operation) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public int getHistorySize() {
        return history.size();
    }

    /**
     * Result of applying a client operation to the canonical document.
     */
    public record ApplyResult(
            long revision,
            TextOperation canonicalOperation,
            DocumentSnapshot snapshot
    ) {
    }
}
