package com.collabeditor.ot;

import com.collabeditor.execution.persistence.ExecutionHistoryRepository;
import com.collabeditor.execution.persistence.entity.ExecutionHistoryEntity;
import com.collabeditor.ot.persistence.SessionOperationRepository;
import com.collabeditor.ot.persistence.entity.SessionOperationEntity;
import com.collabeditor.snapshot.persistence.DocumentStateSnapshotRepository;
import com.collabeditor.snapshot.persistence.entity.DocumentStateSnapshotEntity;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL-backed integration test proving repository query ordering
 * and latest-snapshot selection for the Phase 3 collaboration persistence layer.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CollaborationPersistenceRepositoryTest {

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
    private SessionOperationRepository sessionOperationRepository;

    @Autowired
    private DocumentStateSnapshotRepository documentStateSnapshotRepository;

    @Autowired
    private ExecutionHistoryRepository executionHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                "INSERT INTO users (id, email, password_hash) VALUES (?, 'repo-test@example.com', 'hash')",
                userId
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) VALUES (?, 'REPO1234', 'JAVA', ?)",
                sessionId, userId
        );
    }

    @Test
    void operationReplayReturnsRevisionsInAscendingOrder() {
        // Insert operations out of order
        saveOperation(sessionId, userId, 3, "op-3", "INSERT", 0, "c", null);
        saveOperation(sessionId, userId, 1, "op-1", "INSERT", 0, "a", null);
        saveOperation(sessionId, userId, 2, "op-2", "INSERT", 1, "b", null);

        // Replay from revision 0 should return ascending revisions
        List<SessionOperationEntity> ops =
                sessionOperationRepository.findBySessionIdAndRevisionGreaterThanOrderByRevisionAsc(sessionId, 0);

        assertThat(ops).hasSize(3);
        assertThat(ops.get(0).getRevision()).isEqualTo(1);
        assertThat(ops.get(1).getRevision()).isEqualTo(2);
        assertThat(ops.get(2).getRevision()).isEqualTo(3);
    }

    @Test
    void operationReplayExcludesRevisionsAtOrBeforeStart() {
        saveOperation(sessionId, userId, 1, "op-1", "INSERT", 0, "a", null);
        saveOperation(sessionId, userId, 2, "op-2", "INSERT", 1, "b", null);
        saveOperation(sessionId, userId, 3, "op-3", "INSERT", 2, "c", null);

        // Replay from revision 2 should only return revision 3
        List<SessionOperationEntity> ops =
                sessionOperationRepository.findBySessionIdAndRevisionGreaterThanOrderByRevisionAsc(sessionId, 2);

        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).getRevision()).isEqualTo(3);
    }

    @Test
    void findTopRevisionReturnsLatestForSession() {
        saveOperation(sessionId, userId, 1, "op-1", "INSERT", 0, "a", null);
        saveOperation(sessionId, userId, 5, "op-5", "INSERT", 1, "e", null);
        saveOperation(sessionId, userId, 3, "op-3", "INSERT", 2, "c", null);

        Optional<SessionOperationEntity> latest =
                sessionOperationRepository.findTopBySessionIdOrderByRevisionDesc(sessionId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getRevision()).isEqualTo(5);
    }

    @Test
    void findBySessionIdAndRevisionReturnsExactMatch() {
        saveOperation(sessionId, userId, 7, "op-7", "INSERT", 0, "x", null);

        Optional<SessionOperationEntity> found =
                sessionOperationRepository.findBySessionIdAndRevision(sessionId, 7);

        assertThat(found).isPresent();
        assertThat(found.get().getClientOperationId()).isEqualTo("op-7");
    }

    @Test
    void latestSnapshotReturnsHighestRevision() {
        saveSnapshot(sessionId, 50, "doc at 50");
        saveSnapshot(sessionId, 100, "doc at 100");
        saveSnapshot(sessionId, 150, "doc at 150");

        Optional<DocumentStateSnapshotEntity> latest =
                documentStateSnapshotRepository.findTopBySessionIdOrderByRevisionDesc(sessionId);

        assertThat(latest).isPresent();
        assertThat(latest.get().getRevision()).isEqualTo(150);
        assertThat(latest.get().getDocument()).isEqualTo("doc at 150");
    }

    @Test
    void latestSnapshotBelowARevisionReturnsCorrectSnapshot() {
        saveSnapshot(sessionId, 50, "doc at 50");
        saveSnapshot(sessionId, 100, "doc at 100");
        saveSnapshot(sessionId, 150, "doc at 150");

        // Requesting snapshot at or below revision 120 should return rev 100
        Optional<DocumentStateSnapshotEntity> snap =
                documentStateSnapshotRepository.findTopBySessionIdAndRevisionLessThanEqualOrderByRevisionDesc(sessionId, 120);

        assertThat(snap).isPresent();
        assertThat(snap.get().getRevision()).isEqualTo(100);
        assertThat(snap.get().getDocument()).isEqualTo("doc at 100");
    }

    @Test
    void executionHistoryReturnsNewestFirstByCreatedAt() {
        // Insert executions with explicit created_at ordering via SQL
        UUID exec1 = UUID.randomUUID();
        UUID exec2 = UUID.randomUUID();
        UUID exec3 = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO execution_history (id, session_id, requested_by_user_id, language, source_revision, status, created_at) " +
                "VALUES (?, ?, ?, 'JAVA', 1, 'COMPLETED', now() - interval '2 hours')",
                exec1, sessionId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO execution_history (id, session_id, requested_by_user_id, language, source_revision, status, created_at) " +
                "VALUES (?, ?, ?, 'JAVA', 2, 'COMPLETED', now() - interval '1 hour')",
                exec2, sessionId, userId
        );
        jdbcTemplate.update(
                "INSERT INTO execution_history (id, session_id, requested_by_user_id, language, source_revision, status, created_at) " +
                "VALUES (?, ?, ?, 'JAVA', 3, 'RUNNING', now())",
                exec3, sessionId, userId
        );

        List<ExecutionHistoryEntity> executions =
                executionHistoryRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);

        assertThat(executions).hasSize(3);
        // Newest first: revision 3, then 2, then 1
        assertThat(executions.get(0).getSourceRevision()).isEqualTo(3);
        assertThat(executions.get(1).getSourceRevision()).isEqualTo(2);
        assertThat(executions.get(2).getSourceRevision()).isEqualTo(1);
    }

    private void saveOperation(UUID sessionId, UUID authorId, long revision,
                                String clientOpId, String type, int position,
                                String text, Integer length) {
        sessionOperationRepository.save(new SessionOperationEntity(
                UUID.randomUUID(), sessionId, revision, authorId,
                clientOpId, type, position, text, length
        ));
    }

    private void saveSnapshot(UUID sessionId, long revision, String document) {
        documentStateSnapshotRepository.save(new DocumentStateSnapshotEntity(
                UUID.randomUUID(), sessionId, revision, document
        ));
    }
}
