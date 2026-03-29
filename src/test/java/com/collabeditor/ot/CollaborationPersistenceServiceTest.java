package com.collabeditor.ot;

import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.persistence.SessionOperationRepository;
import com.collabeditor.ot.persistence.entity.SessionOperationEntity;
import com.collabeditor.ot.service.CollaborationPersistenceService;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.OperationalTransformService;
import com.collabeditor.snapshot.persistence.DocumentStateSnapshotRepository;
import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CollaborationPersistenceService proving durable append
 * and snapshot cadence logic.
 */
@ExtendWith(MockitoExtension.class)
class CollaborationPersistenceServiceTest {

    @Mock
    private SessionOperationRepository sessionOperationRepository;

    @Mock
    private DocumentStateSnapshotRepository documentStateSnapshotRepository;

    private CollaborationPersistenceService persistenceService;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userA = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        persistenceService = new CollaborationPersistenceService(
                sessionOperationRepository, documentStateSnapshotRepository);
    }

    @Test
    @DisplayName("appendAcceptedOperation persists one session_operations row")
    void appendAcceptedOperationPersistsRow() {
        InsertOperation op = new InsertOperation(userA, 0L, "op1", 0, "hello");
        DocumentSnapshot snapshot = new DocumentSnapshot("hello", 1);

        persistenceService.appendAcceptedOperation(sessionId, op, 1, snapshot);

        ArgumentCaptor<SessionOperationEntity> captor = ArgumentCaptor.forClass(SessionOperationEntity.class);
        verify(sessionOperationRepository).save(captor.capture());
        SessionOperationEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(sessionId);
        assertThat(saved.getRevision()).isEqualTo(1);
        assertThat(saved.getOperationType()).isEqualTo("INSERT");
        assertThat(saved.getText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("appendAcceptedOperation records snapshot at revision 50")
    void appendRecordsSnapshotAtRevision50() {
        InsertOperation op = new InsertOperation(userA, 49L, "op50", 0, "x");
        DocumentSnapshot snapshot = new DocumentSnapshot("doc at 50", 50);

        persistenceService.appendAcceptedOperation(sessionId, op, 50, snapshot);

        verify(sessionOperationRepository).save(any(SessionOperationEntity.class));
        ArgumentCaptor<DocumentStateSnapshotEntity> snapCaptor =
                ArgumentCaptor.forClass(DocumentStateSnapshotEntity.class);
        verify(documentStateSnapshotRepository).save(snapCaptor.capture());
        DocumentStateSnapshotEntity savedSnap = snapCaptor.getValue();
        assertThat(savedSnap.getSessionId()).isEqualTo(sessionId);
        assertThat(savedSnap.getRevision()).isEqualTo(50);
        assertThat(savedSnap.getDocument()).isEqualTo("doc at 50");
    }

    @Test
    @DisplayName("appendAcceptedOperation records snapshot at revision 100")
    void appendRecordsSnapshotAtRevision100() {
        InsertOperation op = new InsertOperation(userA, 99L, "op100", 0, "y");
        DocumentSnapshot snapshot = new DocumentSnapshot("doc at 100", 100);

        persistenceService.appendAcceptedOperation(sessionId, op, 100, snapshot);

        verify(documentStateSnapshotRepository).save(any(DocumentStateSnapshotEntity.class));
    }

    @Test
    @DisplayName("appendAcceptedOperation does not record snapshot at non-50 revision")
    void appendDoesNotRecordSnapshotAtNon50Revision() {
        InsertOperation op = new InsertOperation(userA, 0L, "op1", 0, "hello");
        DocumentSnapshot snapshot = new DocumentSnapshot("hello", 1);

        persistenceService.appendAcceptedOperation(sessionId, op, 1, snapshot);

        verify(sessionOperationRepository).save(any(SessionOperationEntity.class));
        verify(documentStateSnapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordSnapshot writes one document_snapshots row")
    void recordSnapshotWritesRow() {
        DocumentSnapshot snapshot = new DocumentSnapshot("snapshot text", 50);

        persistenceService.recordSnapshot(sessionId, snapshot);

        ArgumentCaptor<DocumentStateSnapshotEntity> captor =
                ArgumentCaptor.forClass(DocumentStateSnapshotEntity.class);
        verify(documentStateSnapshotRepository).save(captor.capture());
        DocumentStateSnapshotEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(sessionId);
        assertThat(saved.getRevision()).isEqualTo(50);
        assertThat(saved.getDocument()).isEqualTo("snapshot text");
    }
}
