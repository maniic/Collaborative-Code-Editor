package com.collabeditor.ot;

import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.persistence.SessionOperationRepository;
import com.collabeditor.ot.service.CollaborationPersistenceService;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.OperationalTransformService;
import com.collabeditor.snapshot.persistence.DocumentStateSnapshotRepository;
import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL-backed integration tests proving snapshot cadence at exact
 * canonical revisions and lazy runtime rebuild from snapshot-plus-replay.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CollaborationPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("collabeditor_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CollaborationPersistenceService persistenceService;

    @Autowired
    private SnapshotRecoveryService snapshotRecoveryService;

    @Autowired
    private SessionOperationRepository sessionOperationRepository;

    @Autowired
    private DocumentStateSnapshotRepository documentStateSnapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperationalTransformService otService;

    private UUID sessionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // Clean up in reverse FK order
        jdbcTemplate.update("DELETE FROM execution_history");
        jdbcTemplate.update("DELETE FROM document_snapshots");
        jdbcTemplate.update("DELETE FROM session_operations");
        jdbcTemplate.update("DELETE FROM session_participants");
        jdbcTemplate.update("DELETE FROM coding_sessions");
        jdbcTemplate.update("DELETE FROM refresh_sessions");
        jdbcTemplate.update("DELETE FROM users");

        // Create test user and session
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES (?, 'persist-test@example.com', 'hash')",
                userId
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) VALUES (?, 'PERS1234', 'JAVA', ?)",
                sessionId, userId
        );
    }

    @Test
    @DisplayName("appending operations through revision 50 creates exactly one snapshot at revision 50, then through revision 100 creates a second snapshot at revision 100")
    void snapshotCadenceAtRevision50And100() {
        // Use a runtime to generate canonical operations and their snapshots
        CollaborationSessionRuntime runtime = new CollaborationSessionRuntime(sessionId, otService);

        // Append operations up to revision 50
        for (int i = 1; i <= 50; i++) {
            InsertOperation op = new InsertOperation(userId, (long) (i - 1), "op" + i, 0, String.valueOf((char) ('a' + (i % 26))));
            CollaborationSessionRuntime.ApplyResult result = runtime.applyClientOperation(op);
            persistenceService.appendAcceptedOperation(sessionId, result.canonicalOperation(), result.revision(), result.snapshot());
        }

        // Verify exactly one snapshot exists at revision 50
        List<DocumentStateSnapshotEntity> snapshots = documentStateSnapshotRepository.findAll().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .toList();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getRevision()).isEqualTo(50);

        // Continue through revision 100
        for (int i = 51; i <= 100; i++) {
            InsertOperation op = new InsertOperation(userId, (long) (i - 1), "op" + i, 0, String.valueOf((char) ('a' + (i % 26))));
            CollaborationSessionRuntime.ApplyResult result = runtime.applyClientOperation(op);
            persistenceService.appendAcceptedOperation(sessionId, result.canonicalOperation(), result.revision(), result.snapshot());
        }

        // Verify a second snapshot row exists at revision 100
        snapshots = documentStateSnapshotRepository.findAll().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .sorted((a, b) -> Long.compare(a.getRevision(), b.getRevision()))
                .toList();
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).getRevision()).isEqualTo(50);
        assertThat(snapshots.get(1).getRevision()).isEqualTo(100);
    }

    @Test
    @DisplayName("loadRuntime rebuilds from snapshot at revision 50 plus later operations through canonical revision 53")
    void loadRuntimeRebuildsFromSnapshotPlusReplay() {
        // Use a runtime to generate canonical operations
        CollaborationSessionRuntime runtime = new CollaborationSessionRuntime(sessionId, otService);

        // Build to revision 53 (which creates a snapshot at 50 and 3 more ops)
        for (int i = 1; i <= 53; i++) {
            InsertOperation op = new InsertOperation(userId, (long) (i - 1), "op" + i, 0, String.valueOf((char) ('a' + (i % 26))));
            CollaborationSessionRuntime.ApplyResult result = runtime.applyClientOperation(op);
            persistenceService.appendAcceptedOperation(sessionId, result.canonicalOperation(), result.revision(), result.snapshot());
        }

        // Record the expected document at revision 53
        String expectedDocument = runtime.snapshot().document();

        // Now rebuild via SnapshotRecoveryService
        CollaborationSessionRuntime rebuilt = snapshotRecoveryService.loadRuntime(sessionId);

        DocumentSnapshot rebuiltSnapshot = rebuilt.snapshot();
        assertThat(rebuiltSnapshot.revision()).isEqualTo(53);
        assertThat(rebuiltSnapshot.document()).isEqualTo(expectedDocument);
    }

    @Test
    @DisplayName("loadRuntime rebuilds a room with no snapshot rows starting from revision 0")
    void loadRuntimeRebuildsWithNoSnapshotRows() {
        // Create only 5 operations (no snapshot at all since < 50)
        CollaborationSessionRuntime runtime = new CollaborationSessionRuntime(sessionId, otService);

        for (int i = 1; i <= 5; i++) {
            InsertOperation op = new InsertOperation(userId, (long) (i - 1), "op" + i, 0, String.valueOf((char) ('a' + i)));
            CollaborationSessionRuntime.ApplyResult result = runtime.applyClientOperation(op);
            persistenceService.appendAcceptedOperation(sessionId, result.canonicalOperation(), result.revision(), result.snapshot());
        }

        // Confirm no snapshot rows exist
        List<DocumentStateSnapshotEntity> snapshots = documentStateSnapshotRepository.findAll().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .toList();
        assertThat(snapshots).isEmpty();

        String expectedDocument = runtime.snapshot().document();

        // Rebuild from full replay starting at revision 0
        CollaborationSessionRuntime rebuilt = snapshotRecoveryService.loadRuntime(sessionId);

        DocumentSnapshot rebuiltSnapshot = rebuilt.snapshot();
        assertThat(rebuiltSnapshot.revision()).isEqualTo(5);
        assertThat(rebuiltSnapshot.document()).isEqualTo(expectedDocument);
    }
}
