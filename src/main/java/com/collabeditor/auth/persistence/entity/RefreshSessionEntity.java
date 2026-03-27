package com.collabeditor.auth.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSessionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_session_id")
    private UUID replacedBySessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected RefreshSessionEntity() {}

    public RefreshSessionEntity(UUID id, UUID userId, String tokenHash, UUID deviceId,
                                 String userAgent, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.deviceId = deviceId;
        this.userAgent = userAgent;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.lastUsedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public UUID getDeviceId() { return deviceId; }
    public String getUserAgent() { return userAgent; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public UUID getReplacedBySessionId() { return replacedBySessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }

    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setReplacedBySessionId(UUID replacedBySessionId) { this.replacedBySessionId = replacedBySessionId; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (lastUsedAt == null) lastUsedAt = Instant.now();
    }
}
