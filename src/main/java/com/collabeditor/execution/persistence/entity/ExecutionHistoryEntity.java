package com.collabeditor.execution.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "execution_history")
public class ExecutionHistoryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @Column(name = "language", nullable = false, length = 16, updatable = false)
    private String language;

    @Column(name = "source_revision", nullable = false, updatable = false)
    private long sourceRevision;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "stdout")
    private String stdout;

    @Column(name = "stderr")
    private String stderr;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ExecutionHistoryEntity() {}

    public ExecutionHistoryEntity(UUID id, UUID sessionId, UUID requestedByUserId,
                                   String language, long sourceRevision, String status) {
        this.id = id;
        this.sessionId = sessionId;
        this.requestedByUserId = requestedByUserId;
        this.language = language;
        this.sourceRevision = sourceRevision;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getLanguage() { return language; }
    public long getSourceRevision() { return sourceRevision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStdout() { return stdout; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public String getStderr() { return stderr; }
    public void setStderr(String stderr) { this.stderr = stderr; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
