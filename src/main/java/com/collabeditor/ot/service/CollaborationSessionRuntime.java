package com.collabeditor.ot.service;

import com.collabeditor.ot.model.AppliedOperation;
import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.TextOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory canonical state holder for one collaboration session.
 *
 * <p>Stores the canonical document text, revision counter, and applied operation history.
 * All mutations are serialized per-session via a {@link ReentrantLock} so concurrent
 * WebSocket threads cannot corrupt the revision stream.
 *
 * <p>Phase 2 is fully in-memory: no PostgreSQL, Redis, or snapshot persistence.
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

    /**
     * Restores a runtime from durable state instead of starting empty.
     *
     * <p>This is the explicit entry point for lazy rebuilds from snapshot-plus-replay.
     * The restored runtime preserves the existing stale-op transform guarantees because
     * the provided history is used for transform lookups on operations with older base revisions.
     *
     * @param sessionId the session identity
     * @param document  the canonical document text at the given revision
     * @param revision  the canonical revision number
     * @param history   the canonical operation history needed for stale-op transforms
     * @param otService the OT transform service
     * @return a fully initialized runtime at the given state
     */
    public static CollaborationSessionRuntime restore(UUID sessionId, String document,
                                                       long revision, List<AppliedOperation> history,
                                                       OperationalTransformService otService) {
        CollaborationSessionRuntime runtime = new CollaborationSessionRuntime(sessionId, otService);
        runtime.document.append(document);
        runtime.revision = revision;
        runtime.history.addAll(history);
        return runtime;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns an immutable snapshot of the current canonical document state.
     */
    public DocumentSnapshot snapshot() {
        lock.lock();
        try {
            return new DocumentSnapshot(document.toString(), revision);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of operations in the canonical history.
     */
    public int getHistorySize() {
        lock.lock();
        try {
            return history.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a client operation to the canonical document.
     *
     * <p>If the client's base revision is behind the current canonical revision,
     * the operation is transformed sequentially through all history entries newer
     * than the base revision before being applied.
     *
     * @param operation the client operation to apply
     * @return the result containing the assigned revision, canonical operation, and snapshot
     * @throws IllegalArgumentException if baseRevision is in the future
     */
    public ApplyResult applyClientOperation(TextOperation operation) {
        lock.lock();
        try {
            long baseRevision = operation.baseRevision();

            if (baseRevision > revision) {
                throw new IllegalArgumentException(
                        "Operation base revision %d is ahead of canonical revision %d"
                                .formatted(baseRevision, revision));
            }

            // Transform against all operations in history since baseRevision
            TextOperation transformed = operation;
            for (int i = (int) baseRevision; i < history.size(); i++) {
                AppliedOperation historicalOp = history.get(i);
                transformed = otService.transform(transformed, historicalOp.operation());
            }

            // Apply the transformed operation to the canonical document (skip no-op deletes)
            if (transformed instanceof DeleteOperation del && del.length() == 0) {
                // No-op delete from transform; still assign a revision for consistency
            } else {
                String newDoc = otService.apply(document.toString(), transformed);
                document.setLength(0);
                document.append(newDoc);
            }

            // Increment revision and record in history
            revision++;
            AppliedOperation applied = new AppliedOperation(revision, transformed);
            history.add(applied);

            return new ApplyResult(revision, transformed, snapshot());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Result of applying a client operation to the canonical document.
     *
     * @param revision           the assigned canonical revision
     * @param canonicalOperation the operation as transformed and applied to the canonical document
     * @param snapshot           the document snapshot after applying the operation
     */
    public record ApplyResult(
            long revision,
            TextOperation canonicalOperation,
            DocumentSnapshot snapshot
    ) {
    }
}
