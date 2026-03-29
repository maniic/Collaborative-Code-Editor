package com.collabeditor.snapshot.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_snapshots")
public class DocumentStateSnapshotEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "revision", nullable = false, updatable = false)
    private long revision;

    @Column(name = "document", nullable = false, updatable = false)
    private String document;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentStateSnapshotEntity() {}

    public DocumentStateSnapshotEntity(UUID id, UUID sessionId, long revision, String document) {
        this.id = id;
        this.sessionId = sessionId;
        this.revision = revision;
        this.document = document;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public long getRevision() { return revision; }
    public String getDocument() { return document; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
