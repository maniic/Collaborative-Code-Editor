package com.collabeditor.execution.model;

/**
 * Lifecycle statuses for code execution requests.
 *
 * <p>Every execution moves through a subset of these states:
 * <ul>
 *   <li>{@code QUEUED} - accepted and waiting for a worker</li>
 *   <li>{@code RUNNING} - container started, execution in progress</li>
 *   <li>{@code COMPLETED} - finished successfully with exit code 0</li>
 *   <li>{@code FAILED} - finished with non-zero exit code</li>
 *   <li>{@code TIMED_OUT} - killed after exceeding the timeout limit</li>
 *   <li>{@code REJECTED} - refused before execution (cooldown, queue full, invalid)</li>
 * </ul>
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    REJECTED
}
