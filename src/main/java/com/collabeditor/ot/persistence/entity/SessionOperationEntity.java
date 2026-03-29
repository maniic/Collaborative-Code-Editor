package com.collabeditor.ot.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_operations")
public class SessionOperationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(name = "client_operation_id", nullable = false, length = 128, updatable = false)
    private String clientOperationId;

    @Column(name = "operation_type", nullable = false, length = 16, updatable = false)
    private String operationType;

    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    @Column(name = "text", updatable = false)
    private String text;

    @Column(name = "length", updatable = false)
    private Integer length;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SessionOperationEntity() {}

    public SessionOperationEntity(UUID id, UUID sessionId, long revision, UUID authorUserId,
                                   String clientOperationId, String operationType,
                                   int position, String text, Integer length) {
        this.id = id;
        this.sessionId = sessionId;
        this.revision = revision;
        this.authorUserId = authorUserId;
        this.clientOperationId = clientOperationId;
        this.operationType = operationType;
        this.position = position;
        this.text = text;
        this.length = length;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public long getRevision() { return revision; }
    public UUID getAuthorUserId() { return authorUserId; }
    public String getClientOperationId() { return clientOperationId; }
    public String getOperationType() { return operationType; }
    public int getPosition() { return position; }
    public String getText() { return text; }
    public Integer getLength() { return length; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
