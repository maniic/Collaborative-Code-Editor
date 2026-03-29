package com.collabeditor.ot.service;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.persistence.SessionOperationRepository;
import com.collabeditor.ot.persistence.entity.SessionOperationEntity;
import com.collabeditor.snapshot.persistence.DocumentStateSnapshotRepository;
import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Durable append and snapshot write service for accepted canonical operations.
 *
 * <p>Persists each accepted canonical operation to the {@code session_operations} table
 * and creates a document snapshot in {@code document_snapshots} whenever the canonical
 * revision is a multiple of 50.
 */
@Service
public class CollaborationPersistenceService {

    private final SessionOperationRepository sessionOperationRepository;
    private final DocumentStateSnapshotRepository documentStateSnapshotRepository;

    public CollaborationPersistenceService(SessionOperationRepository sessionOperationRepository,
                                            DocumentStateSnapshotRepository documentStateSnapshotRepository) {
        this.sessionOperationRepository = sessionOperationRepository;
        this.documentStateSnapshotRepository = documentStateSnapshotRepository;
    }

    /**
     * Persists one accepted canonical operation and triggers a snapshot when
     * the revision is a multiple of 50.
     *
     * @param sessionId          the collaboration session identity
     * @param canonicalOperation the transformed canonical operation to persist
     * @param revision           the assigned canonical revision number
     * @param snapshot           the document snapshot after applying the operation
     */
    public void appendAcceptedOperation(UUID sessionId, TextOperation canonicalOperation,
                                         long revision, DocumentSnapshot snapshot) {
        // Persist the canonical operation to the session_operations table
        SessionOperationEntity entity = toEntity(sessionId, canonicalOperation, revision);
        sessionOperationRepository.save(entity);

        // Create a snapshot at every 50th canonical revision
        if (revision % 50 == 0) {
            recordSnapshot(sessionId, snapshot);
        }
    }

    /**
     * Writes one document snapshot row containing the full canonical document text and revision.
     *
     * @param sessionId the collaboration session identity
     * @param snapshot  the document snapshot to persist
     */
    public void recordSnapshot(UUID sessionId, DocumentSnapshot snapshot) {
        DocumentStateSnapshotEntity snapshotEntity = new DocumentStateSnapshotEntity(
                UUID.randomUUID(),
                sessionId,
                snapshot.revision(),
                snapshot.document()
        );
        documentStateSnapshotRepository.save(snapshotEntity);
    }

    private SessionOperationEntity toEntity(UUID sessionId, TextOperation operation, long revision) {
        if (operation instanceof InsertOperation ins) {
            return new SessionOperationEntity(
                    UUID.randomUUID(),
                    sessionId,
                    revision,
                    ins.authorUserId(),
                    ins.clientOperationId(),
                    "INSERT",
                    ins.position(),
                    ins.text(),
                    null
            );
        } else if (operation instanceof DeleteOperation del) {
            return new SessionOperationEntity(
                    UUID.randomUUID(),
                    sessionId,
                    revision,
                    del.authorUserId(),
                    del.clientOperationId(),
                    "DELETE",
                    del.position(),
                    null,
                    del.length()
            );
        }
        throw new IllegalArgumentException("Unknown operation type: " + operation.getClass());
    }
}
