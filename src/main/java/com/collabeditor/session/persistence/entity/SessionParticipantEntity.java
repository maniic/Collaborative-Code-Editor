package com.collabeditor.session.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_participants")
@IdClass(SessionParticipantId.class)
public class SessionParticipantEntity {

    @Id
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    protected SessionParticipantEntity() {}

    public SessionParticipantEntity(UUID sessionId, UUID userId, String role, String status) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.status = status;
        this.joinedAt = Instant.now();
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
}
