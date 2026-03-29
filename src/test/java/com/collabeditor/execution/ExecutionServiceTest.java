package com.collabeditor.execution;

import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.persistence.ExecutionHistoryRepository;
import com.collabeditor.execution.persistence.entity.ExecutionHistoryEntity;
import com.collabeditor.execution.service.DockerSandboxRunner;
import com.collabeditor.execution.service.ExecutionCoordinatorService;
import com.collabeditor.execution.service.ExecutionEventRelayService;
import com.collabeditor.execution.service.ExecutionPersistenceService;
import com.collabeditor.execution.service.ExecutionRateLimitService;
import com.collabeditor.execution.service.ExecutionSourceService;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.session.persistence.CodingSessionRepository;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.CodingSessionEntity;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused service tests for Phase 4 execution admission and lifecycle persistence.
 *
 * <p>Covers three exact cases:
 * <ol>
 *   <li>capture(...) returns the persisted canonical document, canonical revision,
 *       immutable room language, and requester email for an ACTIVE participant</li>
 *   <li>capture(...) rejects a non-active or non-participant requester</li>
 *   <li>ExecutionPersistenceService writes durable QUEUED, RUNNING, REJECTED,
 *       and terminal (COMPLETED, FAILED) transitions with correct fields</li>
 * </ol>
 *
 * <p>Source capture tests use Mockito (unit). Persistence tests use Testcontainers (integration).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ExecutionServiceTest {

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
    private ExecutionPersistenceService persistenceService;

    @Autowired
    private ExecutionHistoryRepository executionHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID sessionId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM execution_history");
        jdbcTemplate.update("DELETE FROM document_snapshots");
        jdbcTemplate.update("DELETE FROM session_operations");
        jdbcTemplate.update("DELETE FROM session_participants");
        jdbcTemplate.update("DELETE FROM coding_sessions");
        jdbcTemplate.update("DELETE FROM refresh_sessions");
        jdbcTemplate.update("DELETE FROM users");

        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES (?, 'exec-test@example.com', 'hash')",
                userId);
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) VALUES (?, 'EXEC1234', 'PYTHON', ?)",
                sessionId, userId);
    }

    private ExecutionSourceSnapshot testSnapshot() {
        return new ExecutionSourceSnapshot(
                sessionId, userId, "exec-test@example.com", "PYTHON", 10L, "print('test')");
    }

    // ---------------------------------------------------------------
    // Case 1: capture returns canonical document, canonical revision,
    //         room language, and requester email for ACTIVE participant
    // ---------------------------------------------------------------

    @Test
    @DisplayName("capture returns canonical document, canonical revision, room language, and requester email")
    void captureReturnsCanonicalStateForActiveParticipant() {
        // Use mocks for source service since it crosses many service boundaries
        CodingSessionRepository mockSessionRepo = org.mockito.Mockito.mock(CodingSessionRepository.class);
        SessionParticipantRepository mockPartRepo = org.mockito.Mockito.mock(SessionParticipantRepository.class);
        UserRepository mockUserRepo = org.mockito.Mockito.mock(UserRepository.class);
        SnapshotRecoveryService mockRecovery = org.mockito.Mockito.mock(SnapshotRecoveryService.class);
        CollaborationSessionRuntime mockRuntime = org.mockito.Mockito.mock(CollaborationSessionRuntime.class);

        ExecutionSourceService sourceService = new ExecutionSourceService(
                mockSessionRepo, mockPartRepo, mockUserRepo, mockRecovery);

        // Given: a session with room language PYTHON
        CodingSessionEntity session = new CodingSessionEntity(
                sessionId, "TEST1234", "PYTHON", userId, 12);
        SessionParticipantEntity participant = new SessionParticipantEntity(
                sessionId, userId, "OWNER", "ACTIVE");
        UserEntity user = new UserEntity(userId, "alice@example.com", "hash");
        // Given: canonical document at revision 42
        DocumentSnapshot snapshot = new DocumentSnapshot("print('hello')", 42);

        when(mockSessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(mockPartRepo.findBySessionIdAndUserId(sessionId, userId))
                .thenReturn(Optional.of(participant));
        when(mockUserRepo.findById(userId)).thenReturn(Optional.of(user));
        when(mockRecovery.loadRuntime(sessionId)).thenReturn(mockRuntime);
        when(mockRuntime.snapshot()).thenReturn(snapshot);

        // When
        ExecutionSourceSnapshot result = sourceService.capture(sessionId, userId);

        // Then: canonical document is preserved
        assertThat(result.sourceCode()).isEqualTo("print('hello')");
        // Then: canonical revision is preserved
        assertThat(result.sourceRevision()).isEqualTo(42);
        // Then: room language is the immutable session language
        assertThat(result.language()).isEqualTo("PYTHON");
        // Then: requester email is resolved from user repository
        assertThat(result.requestedByEmail()).isEqualTo("alice@example.com");
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.requestedByUserId()).isEqualTo(userId);
    }

    // ---------------------------------------------------------------
    // Case 2: capture rejects non-active or non-participant requesters
    // ---------------------------------------------------------------

    @Test
    @DisplayName("capture rejects a non-active participant (LEFT status)")
    void captureRejectsNonActiveParticipant() {
        CodingSessionRepository mockSessionRepo = org.mockito.Mockito.mock(CodingSessionRepository.class);
        SessionParticipantRepository mockPartRepo = org.mockito.Mockito.mock(SessionParticipantRepository.class);
        UserRepository mockUserRepo = org.mockito.Mockito.mock(UserRepository.class);
        SnapshotRecoveryService mockRecovery = org.mockito.Mockito.mock(SnapshotRecoveryService.class);

        ExecutionSourceService sourceService = new ExecutionSourceService(
                mockSessionRepo, mockPartRepo, mockUserRepo, mockRecovery);

        CodingSessionEntity session = new CodingSessionEntity(
                sessionId, "TEST1234", "JAVA", userId, 12);
        SessionParticipantEntity participant = new SessionParticipantEntity(
                sessionId, userId, "PARTICIPANT", "LEFT");

        when(mockSessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(mockPartRepo.findBySessionIdAndUserId(sessionId, userId))
                .thenReturn(Optional.of(participant));

        // non-active participant should be rejected
        assertThatThrownBy(() -> sourceService.capture(sessionId, userId))
                .isInstanceOf(ExecutionSourceService.ExecutionAccessDeniedException.class)
                .hasMessageContaining("not an ACTIVE participant");
    }

    @Test
    @DisplayName("capture rejects a non-participant user who is not in the session")
    void captureRejectsNonParticipant() {
        UUID nonParticipantId = UUID.randomUUID();
        CodingSessionRepository mockSessionRepo = org.mockito.Mockito.mock(CodingSessionRepository.class);
        SessionParticipantRepository mockPartRepo = org.mockito.Mockito.mock(SessionParticipantRepository.class);
        UserRepository mockUserRepo = org.mockito.Mockito.mock(UserRepository.class);
        SnapshotRecoveryService mockRecovery = org.mockito.Mockito.mock(SnapshotRecoveryService.class);

        ExecutionSourceService sourceService = new ExecutionSourceService(
                mockSessionRepo, mockPartRepo, mockUserRepo, mockRecovery);

        CodingSessionEntity session = new CodingSessionEntity(
                sessionId, "TEST1234", "JAVA", userId, 12);

        when(mockSessionRepo.findById(sessionId)).thenReturn(Optional.of(session));
        when(mockPartRepo.findBySessionIdAndUserId(sessionId, nonParticipantId))
                .thenReturn(Optional.empty());

        // non-participant should be rejected
        assertThatThrownBy(() -> sourceService.capture(sessionId, nonParticipantId))
                .isInstanceOf(ExecutionSourceService.ExecutionAccessDeniedException.class)
                .hasMessageContaining("not a participant");
    }

    // ---------------------------------------------------------------
    // Case 3: ExecutionPersistenceService writes durable lifecycle rows
    //         QUEUED, RUNNING, REJECTED, COMPLETED, FAILED
    // ---------------------------------------------------------------

    @Test
    @DisplayName("recordQueuedExecution persists QUEUED row with source_revision and language")
    void recordQueuedExecution() {
        ExecutionHistoryEntity entity = persistenceService.recordQueuedExecution(testSnapshot());

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getStatus()).isEqualTo("QUEUED");
        assertThat(entity.getSourceRevision()).isEqualTo(10L);
        assertThat(entity.getLanguage()).isEqualTo("PYTHON");
        assertThat(entity.getSessionId()).isEqualTo(sessionId);
        assertThat(entity.getRequestedByUserId()).isEqualTo(userId);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getStartedAt()).isNull();
        assertThat(entity.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("markRunning transitions QUEUED to RUNNING with started_at set")
    void markRunningTransition() {
        ExecutionHistoryEntity queued = persistenceService.recordQueuedExecution(testSnapshot());
        assertThat(queued.getStatus()).isEqualTo("QUEUED");

        ExecutionHistoryEntity running = persistenceService.markRunning(queued.getId());
        assertThat(running.getStatus()).isEqualTo("RUNNING");
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("recordRejectedExecution persists REJECTED row with stderr reason")
    void recordRejectedExecution() {
        ExecutionHistoryEntity rejected = persistenceService.recordRejectedExecution(
                testSnapshot(), "Cooldown active: 3 seconds remaining");

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getStderr()).isEqualTo("Cooldown active: 3 seconds remaining");
        assertThat(rejected.getFinishedAt()).isNotNull();
        assertThat(rejected.getSourceRevision()).isEqualTo(10L);
    }

    @Test
    @DisplayName("finishExecution writes COMPLETED terminal with stdout, stderr, exit_code, and finished_at")
    void finishExecutionCompleted() {
        ExecutionHistoryEntity queued = persistenceService.recordQueuedExecution(testSnapshot());
        persistenceService.markRunning(queued.getId());

        ExecutionHistoryEntity finished = persistenceService.finishExecution(
                queued.getId(), ExecutionStatus.COMPLETED, "Hello, World!", "", 0);

        assertThat(finished.getStatus()).isEqualTo("COMPLETED");
        assertThat(finished.getStdout()).isEqualTo("Hello, World!");
        assertThat(finished.getStderr()).isEmpty();
        assertThat(finished.getExitCode()).isEqualTo(0);
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("finishExecution writes FAILED terminal with non-zero exit_code and stderr")
    void finishExecutionFailed() {
        ExecutionHistoryEntity queued = persistenceService.recordQueuedExecution(testSnapshot());
        persistenceService.markRunning(queued.getId());

        ExecutionHistoryEntity finished = persistenceService.finishExecution(
                queued.getId(), ExecutionStatus.FAILED, "",
                "NameError: name 'undefined_var' is not defined", 1);

        assertThat(finished.getStatus()).isEqualTo("FAILED");
        assertThat(finished.getStderr()).contains("NameError");
        assertThat(finished.getExitCode()).isEqualTo(1);
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByIdAndSessionId returns execution scoped to session")
    void findByIdAndSessionIdReturnsScoped() {
        ExecutionHistoryEntity queued = persistenceService.recordQueuedExecution(testSnapshot());

        Optional<ExecutionHistoryEntity> found =
                executionHistoryRepository.findByIdAndSessionId(queued.getId(), sessionId);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(queued.getId());

        // Different session should not find it
        Optional<ExecutionHistoryEntity> notFound =
                executionHistoryRepository.findByIdAndSessionId(queued.getId(), UUID.randomUUID());
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("enqueue returns queued response metadata, persists QUEUED, and publishes the initial lifecycle update")
    void enqueueReturnsQueuedResponseMetadata() {
        ExecutionSourceService sourceService = mock(ExecutionSourceService.class);
        ExecutionPersistenceService persistence = mock(ExecutionPersistenceService.class);
        ExecutionRateLimitService rateLimitService = mock(ExecutionRateLimitService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DockerSandboxRunner dockerSandboxRunner = mock(DockerSandboxRunner.class);
        ExecutionEventRelayService eventRelayService = mock(ExecutionEventRelayService.class);

        ExecutionCoordinatorService coordinator = new ExecutionCoordinatorService(
                sourceService,
                persistence,
                rateLimitService,
                executor,
                dockerSandboxRunner,
                eventRelayService
        );

        ExecutionSourceSnapshot snapshot = testSnapshot();
        ExecutionHistoryEntity queued = executionEntity("QUEUED");

        when(sourceService.capture(sessionId, userId)).thenReturn(snapshot);
        when(rateLimitService.tryAcquire(userId)).thenReturn(true);
        when(persistence.recordQueuedExecution(snapshot)).thenReturn(queued);

        var response = coordinator.enqueue(sessionId, userId);

        assertThat(response.executionId()).isEqualTo(queued.getId());
        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.language()).isEqualTo("PYTHON");
        assertThat(response.sourceRevision()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo("QUEUED");

        ArgumentCaptor<com.collabeditor.websocket.protocol.ExecutionUpdatedPayload> relayCaptor =
                ArgumentCaptor.forClass(com.collabeditor.websocket.protocol.ExecutionUpdatedPayload.class);
        verify(eventRelayService).publish(eq(sessionId), relayCaptor.capture());
        assertThat(relayCaptor.getValue().status()).isEqualTo("QUEUED");
        assertThat(relayCaptor.getValue().requestedByEmail()).isEqualTo("exec-test@example.com");
    }

    @Test
    @DisplayName("enqueue rejects cooldown violations with REJECTED persistence and a rate-limit exception")
    void enqueueRejectsCooldownViolations() {
        ExecutionSourceService sourceService = mock(ExecutionSourceService.class);
        ExecutionPersistenceService persistence = mock(ExecutionPersistenceService.class);
        ExecutionRateLimitService rateLimitService = mock(ExecutionRateLimitService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DockerSandboxRunner dockerSandboxRunner = mock(DockerSandboxRunner.class);
        ExecutionEventRelayService eventRelayService = mock(ExecutionEventRelayService.class);

        ExecutionCoordinatorService coordinator = new ExecutionCoordinatorService(
                sourceService,
                persistence,
                rateLimitService,
                executor,
                dockerSandboxRunner,
                eventRelayService
        );

        ExecutionSourceSnapshot snapshot = testSnapshot();
        ExecutionHistoryEntity rejected = executionEntity("REJECTED");
        rejected.setStderr("Execution cooldown is active. You can run code once every five seconds.");

        when(sourceService.capture(sessionId, userId)).thenReturn(snapshot);
        when(rateLimitService.tryAcquire(userId)).thenReturn(false);
        when(persistence.recordRejectedExecution(eq(snapshot), any())).thenReturn(rejected);

        assertThatThrownBy(() -> coordinator.enqueue(sessionId, userId))
                .isInstanceOf(ExecutionCoordinatorService.ExecutionRateLimitException.class)
                .hasMessageContaining("five seconds");

        ArgumentCaptor<com.collabeditor.websocket.protocol.ExecutionUpdatedPayload> relayCaptor =
                ArgumentCaptor.forClass(com.collabeditor.websocket.protocol.ExecutionUpdatedPayload.class);
        verify(eventRelayService).publish(eq(sessionId), relayCaptor.capture());
        assertThat(relayCaptor.getValue().status()).isEqualTo("REJECTED");
        assertThat(relayCaptor.getValue().stderr()).contains("five seconds");
    }

    @Test
    @DisplayName("enqueue rejects a full queue with REJECTED persistence, release, and a queue-full exception")
    void enqueueRejectsQueueFull() {
        ExecutionSourceService sourceService = mock(ExecutionSourceService.class);
        ExecutionPersistenceService persistence = mock(ExecutionPersistenceService.class);
        ExecutionRateLimitService rateLimitService = mock(ExecutionRateLimitService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DockerSandboxRunner dockerSandboxRunner = mock(DockerSandboxRunner.class);
        ExecutionEventRelayService eventRelayService = mock(ExecutionEventRelayService.class);

        ExecutionCoordinatorService coordinator = new ExecutionCoordinatorService(
                sourceService,
                persistence,
                rateLimitService,
                executor,
                dockerSandboxRunner,
                eventRelayService
        );

        ExecutionSourceSnapshot snapshot = testSnapshot();
        ExecutionHistoryEntity queued = executionEntity("QUEUED");
        ExecutionHistoryEntity rejected = executionEntity("REJECTED");
        rejected.setStderr("Execution queue is full. Try again shortly.");

        when(sourceService.capture(sessionId, userId)).thenReturn(snapshot);
        when(rateLimitService.tryAcquire(userId)).thenReturn(true);
        when(persistence.recordQueuedExecution(snapshot)).thenReturn(queued);
        when(persistence.recordRejectedExecution(eq(snapshot), any())).thenReturn(rejected);
        doThrow(new TaskRejectedException("full")).when(executor).execute(any());

        assertThatThrownBy(() -> coordinator.enqueue(sessionId, userId))
                .isInstanceOf(ExecutionCoordinatorService.ExecutionQueueFullException.class)
                .hasMessageContaining("queue is full");

        verify(rateLimitService).release(userId);
    }

    @Test
    @DisplayName("accepted enqueue transitions through QUEUED, RUNNING, and COMPLETED with terminal output")
    void acceptedEnqueueTransitionsThroughQueuedRunningAndCompleted() {
        ExecutionSourceService sourceService = mock(ExecutionSourceService.class);
        ExecutionPersistenceService persistence = mock(ExecutionPersistenceService.class);
        ExecutionRateLimitService rateLimitService = mock(ExecutionRateLimitService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DockerSandboxRunner dockerSandboxRunner = mock(DockerSandboxRunner.class);
        ExecutionEventRelayService eventRelayService = mock(ExecutionEventRelayService.class);

        ExecutionCoordinatorService coordinator = new ExecutionCoordinatorService(
                sourceService,
                persistence,
                rateLimitService,
                executor,
                dockerSandboxRunner,
                eventRelayService
        );

        ExecutionSourceSnapshot snapshot = testSnapshot();
        ExecutionHistoryEntity queued = executionEntity("QUEUED");
        ExecutionHistoryEntity running = executionEntity("RUNNING");
        running.setStartedAt(java.time.Instant.now());
        ExecutionHistoryEntity completed = executionEntity("COMPLETED");
        completed.setStartedAt(java.time.Instant.now());
        completed.setFinishedAt(java.time.Instant.now());
        completed.setStdout("done");
        completed.setExitCode(0);

        when(sourceService.capture(sessionId, userId)).thenReturn(snapshot);
        when(rateLimitService.tryAcquire(userId)).thenReturn(true);
        when(persistence.recordQueuedExecution(snapshot)).thenReturn(queued);
        when(persistence.markRunning(queued.getId())).thenReturn(running);
        when(dockerSandboxRunner.run(snapshot)).thenReturn(new SandboxExecutionResult(
                ExecutionStatus.COMPLETED, "done", "", 0));
        when(persistence.finishExecution(queued.getId(), ExecutionStatus.COMPLETED, "done", "", 0))
                .thenReturn(completed);
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0, Runnable.class);
            task.run();
            return null;
        }).when(executor).execute(any());

        coordinator.enqueue(sessionId, userId);

        ArgumentCaptor<com.collabeditor.websocket.protocol.ExecutionUpdatedPayload> relayCaptor =
                ArgumentCaptor.forClass(com.collabeditor.websocket.protocol.ExecutionUpdatedPayload.class);
        verify(eventRelayService, org.mockito.Mockito.times(3)).publish(eq(sessionId), relayCaptor.capture());
        assertThat(relayCaptor.getAllValues())
                .extracting(com.collabeditor.websocket.protocol.ExecutionUpdatedPayload::status)
                .containsExactly("QUEUED", "RUNNING", "COMPLETED");
        assertThat(relayCaptor.getAllValues().get(2).stdout()).isEqualTo("done");
    }

    private ExecutionHistoryEntity executionEntity(String status) {
        ExecutionHistoryEntity entity = new ExecutionHistoryEntity(
                UUID.randomUUID(),
                sessionId,
                userId,
                "PYTHON",
                10L,
                status
        );
        if ("REJECTED".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status)) {
            entity.setFinishedAt(java.time.Instant.now());
        }
        return entity;
    }
}
