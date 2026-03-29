package com.collabeditor.snapshot.service;

import com.collabeditor.ot.model.AppliedOperation;
import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.persistence.SessionOperationRepository;
import com.collabeditor.ot.persistence.entity.SessionOperationEntity;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.OperationalTransformService;
import com.collabeditor.snapshot.persistence.DocumentStateSnapshotRepository;
import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lazy runtime rebuild from latest snapshot plus replay.
 *
 * <p>Loads the most recent document snapshot for a session, then replays
 * all canonical operations after that snapshot revision to reconstruct
 * the full {@link CollaborationSessionRuntime}. If no snapshot exists,
 * rebuilds from an empty document at revision 0 with the full operation list.
 */
@Service
public class SnapshotRecoveryService {

    private final DocumentStateSnapshotRepository snapshotRepository;
    private final SessionOperationRepository operationRepository;
    private final OperationalTransformService otService;

    public SnapshotRecoveryService(DocumentStateSnapshotRepository snapshotRepository,
                                    SessionOperationRepository operationRepository,
                                    OperationalTransformService otService) {
        this.snapshotRepository = snapshotRepository;
        this.operationRepository = operationRepository;
        this.otService = otService;
    }

    /**
     * Rebuilds a collaboration session runtime on demand from durable state.
     *
     * <p>Finds the latest snapshot for the session, loads all operations after
     * that snapshot revision, converts them to {@link AppliedOperation} values,
     * and returns a restored {@link CollaborationSessionRuntime}.
     *
     * <p>If no snapshot exists, rebuilds from an empty document at revision 0
     * with the full ordered operation list.
     *
     * @param sessionId the collaboration session to rebuild
     * @return a fully hydrated runtime at the latest canonical state
     */
    public CollaborationSessionRuntime loadRuntime(UUID sessionId) {
        Optional<DocumentStateSnapshotEntity> latestSnapshot =
                snapshotRepository.findTopBySessionIdOrderByRevisionDesc(sessionId);

        if (latestSnapshot.isPresent()) {
            DocumentStateSnapshotEntity snap = latestSnapshot.get();
            List<SessionOperationEntity> laterOps =
                    operationRepository.findBySessionIdAndRevisionGreaterThanOrderByRevisionAsc(
                            sessionId, snap.getRevision());

            List<AppliedOperation> history = laterOps.stream()
                    .map(this::toAppliedOperation)
                    .collect(Collectors.toList());

            // Replay later operations on top of the snapshot document
            String document = snap.getDocument();
            long revision = snap.getRevision();
            for (AppliedOperation applied : history) {
                document = otService.apply(document, applied.operation());
                revision = applied.revision();
            }

            return CollaborationSessionRuntime.restore(
                    sessionId, document, revision, history, otService);
        } else {
            // No snapshot exists: rebuild from empty document with full operation list
            List<SessionOperationEntity> allOps =
                    operationRepository.findBySessionIdOrderByRevisionAsc(sessionId);

            List<AppliedOperation> history = allOps.stream()
                    .map(this::toAppliedOperation)
                    .collect(Collectors.toList());

            // Replay operations to reconstruct the document
            String document = "";
            long revision = 0;
            for (AppliedOperation applied : history) {
                document = otService.apply(document, applied.operation());
                revision = applied.revision();
            }

            return CollaborationSessionRuntime.restore(
                    sessionId, document, revision, history, otService);
        }
    }

    private AppliedOperation toAppliedOperation(SessionOperationEntity entity) {
        TextOperation op;
        if ("INSERT".equals(entity.getOperationType())) {
            op = new InsertOperation(
                    entity.getAuthorUserId(),
                    0L, // base revision is not needed for history replay
                    entity.getClientOperationId(),
                    entity.getPosition(),
                    entity.getText()
            );
        } else if ("DELETE".equals(entity.getOperationType())) {
            op = new DeleteOperation(
                    entity.getAuthorUserId(),
                    0L,
                    entity.getClientOperationId(),
                    entity.getPosition(),
                    entity.getLength()
            );
        } else {
            throw new IllegalArgumentException("Unknown operation type: " + entity.getOperationType());
        }
        return new AppliedOperation(entity.getRevision(), op);
    }
}
