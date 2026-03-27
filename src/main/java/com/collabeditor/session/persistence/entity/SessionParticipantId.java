package com.collabeditor.session.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class SessionParticipantId implements Serializable {

    private UUID sessionId;
    private UUID userId;

    public SessionParticipantId() {}

    public SessionParticipantId(UUID sessionId, UUID userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SessionParticipantId that = (SessionParticipantId) o;
        return Objects.equals(sessionId, that.sessionId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, userId);
    }
}
