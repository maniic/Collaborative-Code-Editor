package com.collabeditor.execution.service;

import com.collabeditor.execution.model.ExecutionSourceSnapshot;
import com.collabeditor.execution.model.ExecutionStatus;
import com.collabeditor.execution.persistence.ExecutionHistoryRepository;
import com.collabeditor.execution.persistence.entity.ExecutionHistoryEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable execution-history state transitions for the execution lifecycle.
 *
 * <p>Persists lifecycle rows for queued, running, rejected, and terminal outcomes
 * in the {@code execution_history} table. Each method returns the updated entity
 * for downstream event broadcasting.
 */
@Service
public class ExecutionPersistenceService {

    private final ExecutionHistoryRepository repository;

    public ExecutionPersistenceService(ExecutionHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a new QUEUED execution from a canonical source snapshot.
     *
     * @param snapshot the captured room state at admission time
     * @return the persisted execution entity with status QUEUED
     */
    @Transactional
    public ExecutionHistoryEntity recordQueuedExecution(ExecutionSourceSnapshot snapshot) {
        ExecutionHistoryEntity entity = new ExecutionHistoryEntity(
                UUID.randomUUID(),
                snapshot.sessionId(),
                snapshot.requestedByUserId(),
                snapshot.language(),
                snapshot.sourceRevision(),
                ExecutionStatus.QUEUED.name()
        );
        return repository.save(entity);
    }

    /**
     * Records a REJECTED execution from a canonical source snapshot.
     *
     * <p>Used when an execution is refused at admission time (cooldown violation,
     * queue full, or other pre-execution rejection).
     *
     * @param snapshot the captured room state at admission time
     * @param stderr   the rejection reason message
     * @return the persisted execution entity with status REJECTED
     */
    @Transactional
    public ExecutionHistoryEntity recordRejectedExecution(ExecutionSourceSnapshot snapshot, String stderr) {
        ExecutionHistoryEntity entity = new ExecutionHistoryEntity(
                UUID.randomUUID(),
                snapshot.sessionId(),
                snapshot.requestedByUserId(),
                snapshot.language(),
                snapshot.sourceRevision(),
                ExecutionStatus.REJECTED.name()
        );
        entity.setStderr(stderr);
        entity.setFinishedAt(Instant.now());
        return repository.save(entity);
    }

    /**
     * Transitions an execution from QUEUED to RUNNING.
     *
     * @param executionId the execution to mark as running
     * @return the updated execution entity with status RUNNING and started_at set
     * @throws IllegalArgumentException if the execution does not exist
     */
    @Transactional
    public ExecutionHistoryEntity markRunning(UUID executionId) {
        ExecutionHistoryEntity entity = repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Execution not found: " + executionId));
        entity.setStatus(ExecutionStatus.RUNNING.name());
        entity.setStartedAt(Instant.now());
        return repository.save(entity);
    }

    /**
     * Completes an execution with terminal status and output fields.
     *
     * @param executionId the execution to finish
     * @param status      the terminal status (COMPLETED, FAILED, or TIMED_OUT)
     * @param stdout      the captured standard output (may be null)
     * @param stderr      the captured standard error (may be null)
     * @param exitCode    the process exit code (may be null for TIMED_OUT)
     * @return the updated execution entity with terminal fields set
     * @throws IllegalArgumentException if the execution does not exist
     */
    @Transactional
    public ExecutionHistoryEntity finishExecution(UUID executionId, ExecutionStatus status,
                                                   String stdout, String stderr, Integer exitCode) {
        ExecutionHistoryEntity entity = repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Execution not found: " + executionId));
        entity.setStatus(status.name());
        entity.setStdout(stdout);
        entity.setStderr(stderr);
        entity.setExitCode(exitCode);
        entity.setFinishedAt(Instant.now());
        return repository.save(entity);
    }
}
