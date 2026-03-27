package com.collabeditor.websocket.service;

import com.collabeditor.websocket.model.SelectionRange;
import com.collabeditor.websocket.protocol.ParticipantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-session presence storage for participant identity, selection ranges,
 * and cursor update throttling.
 *
 * <p>Keyed by sessionId and userId. Stores the latest {@link SelectionRange},
 * last-broadcast timestamp, and participant identity (userId + email).
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final long cursorThrottleMs;

    /** sessionId -> (userId -> ParticipantPresence) */
    private final Map<UUID, Map<UUID, ParticipantPresence>> sessions = new ConcurrentHashMap<>();

    public PresenceService(@Value("${app.collaboration.cursor-throttle-ms:75}") long cursorThrottleMs) {
        this.cursorThrottleMs = cursorThrottleMs;
    }

    /**
     * Register a participant in a session's presence tracking.
     */
    public void join(UUID sessionId, UUID userId, String email) {
        sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>())
                .put(userId, new ParticipantPresence(userId, email, null, 0L));
        log.debug("Presence join: userId={} sessionId={}", userId, sessionId);
    }

    /**
     * Remove a participant from a session's presence tracking.
     */
    public void leave(UUID sessionId, UUID userId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap != null) {
            sessionMap.remove(userId);
            if (sessionMap.isEmpty()) {
                sessions.remove(sessionId);
            }
        }
        log.debug("Presence leave: userId={} sessionId={}", userId, sessionId);
    }

    /**
     * Returns the list of currently tracked participants for a session.
     */
    public List<ParticipantInfo> getParticipants(UUID sessionId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) {
            return List.of();
        }
        return sessionMap.values().stream()
                .map(p -> new ParticipantInfo(p.userId, p.email))
                .toList();
    }

    /**
     * Update the stored selection range for a participant.
     */
    public void updateSelection(UUID sessionId, UUID userId, SelectionRange range) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return;
        ParticipantPresence current = sessionMap.get(userId);
        if (current == null) return;
        sessionMap.put(userId, new ParticipantPresence(
                current.userId, current.email, range, current.lastBroadcastTime));
    }

    /**
     * Get the stored selection range for a participant, or null if not set.
     */
    public SelectionRange getSelection(UUID sessionId, UUID userId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return null;
        ParticipantPresence presence = sessionMap.get(userId);
        return presence != null ? presence.selection : null;
    }

    /**
     * Get the email for a participant, or null if not tracked.
     */
    public String getEmail(UUID sessionId, UUID userId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return null;
        ParticipantPresence presence = sessionMap.get(userId);
        return presence != null ? presence.email : null;
    }

    /**
     * Returns true if enough time has elapsed since the last broadcast for this user
     * in this session (based on the configured throttle interval).
     */
    public boolean shouldBroadcast(UUID sessionId, UUID userId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return true;
        ParticipantPresence presence = sessionMap.get(userId);
        if (presence == null) return true;
        return (System.currentTimeMillis() - presence.lastBroadcastTime) >= cursorThrottleMs;
    }

    /**
     * Record that a broadcast was just sent for this user in this session.
     */
    public void markBroadcast(UUID sessionId, UUID userId) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return;
        ParticipantPresence current = sessionMap.get(userId);
        if (current == null) return;
        sessionMap.put(userId, new ParticipantPresence(
                current.userId, current.email, current.selection, System.currentTimeMillis()));
    }

    /**
     * Internal presence state per participant.
     */
    private record ParticipantPresence(
            UUID userId,
            String email,
            SelectionRange selection,
            long lastBroadcastTime
    ) {
    }
}
