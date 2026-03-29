package com.collabeditor.execution.model;

/**
 * Immutable result of a sandboxed Docker container execution.
 *
 * <p>Captures the execution status, captured output streams,
 * and process exit code for a completed or timed-out container run.
 */
public record SandboxExecutionResult(
        ExecutionStatus status,
        String stdout,
        String stderr,
        Integer exitCode
) {}
