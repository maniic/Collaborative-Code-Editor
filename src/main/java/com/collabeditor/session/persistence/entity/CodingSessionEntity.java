package com.collabeditor.session.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coding_sessions")
public class CodingSessionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invite_code", nullable = false, length = 8, updatable = false)
    private String inviteCode;

    @Column(name = "language", nullable = false, length = 16, updatable = false)
    private String language;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "participant_cap", nullable = false)
    private short participantCap;

    @Column(name = "empty_since")
    private Instant emptySince;

    @Column(name = "cleanup_after")
    private Instant cleanupAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CodingSessionEntity() {}

    public CodingSessionEntity(UUID id, String inviteCode, String language,
                                UUID ownerUserId, int participantCap) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.language = language;
        this.ownerUserId = ownerUserId;
        this.participantCap = toSmallInt(participantCap);
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getInviteCode() { return inviteCode; }
    public String getLanguage() { return language; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public int getParticipantCap() { return participantCap; }
    public Instant getEmptySince() { return emptySince; }
    public void setEmptySince(Instant emptySince) { this.emptySince = emptySince; }
    public Instant getCleanupAfter() { return cleanupAfter; }
    public void setCleanupAfter(Instant cleanupAfter) { this.cleanupAfter = cleanupAfter; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    private short toSmallInt(int participantCap) {
        if (participantCap < Short.MIN_VALUE || participantCap > Short.MAX_VALUE) {
            throw new IllegalArgumentException("participantCap must fit PostgreSQL SMALLINT");
        }
        return (short) participantCap;
    }
}
