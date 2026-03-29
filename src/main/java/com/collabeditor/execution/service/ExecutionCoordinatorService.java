package com.collabeditor.execution.service;

import com.collabeditor.execution.api.dto.EnqueueExecutionResponse;
import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.model.SandboxExecutionResult;
import com.collabeditor.execution.persistence.entity.ExecutionHistoryEntity;
import com.collabeditor.websocket.protocol.ExecutionUpdatedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Coordinates execution admission, cooldown enforcement, background work, and lifecycle relays.
 */
@Service
public class ExecutionCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCoordinatorService.class);

    private static final String COOLDOWN_MESSAGE =
            "Execution cooldown is active. You can run code once every five seconds.";
    private static final String QUEUE_FULL_MESSAGE =
            "Execution queue is full. Try again shortly.";

    private final ExecutionSourceService sourceService;
    private final ExecutionPersistenceService persistenceService;
    private final ExecutionRateLimitService rateLimitService;
    private final ThreadPoolTaskExecutor executionTaskExecutor;
    private final DockerSandboxRunner dockerSandboxRunner;
    private final ExecutionEventRelayService eventRelayService;

    public ExecutionCoordinatorService(ExecutionSourceService sourceService,
                                       ExecutionPersistenceService persistenceService,
                                       ExecutionRateLimitService rateLimitService,
                                       ThreadPoolTaskExecutor executionTaskExecutor,
                                       DockerSandboxRunner dockerSandboxRunner,
                                       ExecutionEventRelayService eventRelayService) {
        this.sourceService = sourceService;
        this.persistenceService = persistenceService;
        this.rateLimitService = rateLimitService;
        this.executionTaskExecutor = executionTaskExecutor;
        this.dockerSandboxRunner = dockerSandboxRunner;
        this.eventRelayService = eventRelayService;
    }

    public EnqueueExecutionResponse enqueue(UUID sessionId, UUID requestedByUserId) {
        ExecutionSourceSnapshot snapshot = sourceService.capture(sessionId, requestedByUserId);

        if (!rateLimitService.tryAcquire(requestedByUserId)) {
            ExecutionHistoryEntity rejected = persistenceService.recordRejectedExecution(snapshot, COOLDOWN_MESSAGE);
            publishLifecycle(rejected, snapshot, COOLDOWN_MESSAGE);
            throw new ExecutionRateLimitException(COOLDOWN_MESSAGE);
        }

        ExecutionHistoryEntity queued = persistenceService.recordQueuedExecution(snapshot);
        publishLifecycle(queued, snapshot, "Execution queued.");

        try {
            executionTaskExecutor.execute(() -> runExecution(queued.getId(), snapshot));
        } catch (TaskRejectedException ex) {
            rateLimitService.release(requestedByUserId);
            ExecutionHistoryEntity rejected = persistenceService.recordRejectedExecution(snapshot, QUEUE_FULL_MESSAGE);
            publishLifecycle(rejected, snapshot, QUEUE_FULL_MESSAGE);
            throw new ExecutionQueueFullException(QUEUE_FULL_MESSAGE);
        }

        return new EnqueueExecutionResponse(
                queued.getId(),
                snapshot.sessionId(),
                snapshot.language(),
                snapshot.sourceRevision(),
                queued.getStatus()
        );
    }

    private void runExecution(UUID executionId, ExecutionSourceSnapshot snapshot) {
        try {
            ExecutionHistoryEntity running = persistenceService.markRunning(executionId);
            publishLifecycle(running, snapshot, "Execution started.");

            SandboxExecutionResult result = dockerSandboxRunner.run(snapshot);
            ExecutionHistoryEntity finished = persistenceService.finishExecution(
                    executionId,
                    result.status(),
                    result.stdout(),
                    result.stderr(),
                    result.exitCode()
            );
            publishLifecycle(finished, snapshot, terminalMessage(result));
        } catch (RuntimeException ex) {
            log.error("Execution {} failed unexpectedly", executionId, ex);
            ExecutionHistoryEntity failed = persistenceService.finishExecution(
                    executionId,
                    ExecutionStatus.FAILED,
                    "",
                    errorMessage(ex),
                    null
            );
            publishLifecycle(failed, snapshot, "Execution failed.");
        }
    }

    private void publishLifecycle(ExecutionHistoryEntity entity,
                                  ExecutionSourceSnapshot snapshot,
                                  String message) {
        eventRelayService.publish(
                snapshot.sessionId(),
                new ExecutionUpdatedPayload(
                        entity.getId(),
                        entity.getRequestedByUserId(),
                        snapshot.requestedByEmail(),
                        entity.getLanguage(),
                        entity.getSourceRevision(),
                        entity.getStatus(),
                        entity.getStdout(),
                        entity.getStderr(),
                        entity.getExitCode(),
                        entity.getCreatedAt(),
                        entity.getStartedAt(),
                        entity.getFinishedAt(),
                        message
                )
        );
    }

    private String terminalMessage(SandboxExecutionResult result) {
        return switch (result.status()) {
            case COMPLETED -> "Execution completed successfully.";
            case TIMED_OUT -> "Execution timed out after ten seconds.";
            case FAILED -> "Execution failed.";
            case REJECTED, RUNNING, QUEUED -> result.status().name();
        };
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return (message == null || message.isBlank())
                ? exception.getClass().getSimpleName()
                : message;
    }

    public static class ExecutionRateLimitException extends RuntimeException {
        public ExecutionRateLimitException(String message) {
            super(message);
        }
    }

    public static class ExecutionQueueFullException extends RuntimeException {
        public ExecutionQueueFullException(String message) {
            super(message);
        }
    }
}
