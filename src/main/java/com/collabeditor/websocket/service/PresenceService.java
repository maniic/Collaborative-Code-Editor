package com.collabeditor.websocket.service;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
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
                .compute(userId, (ignored, existing) -> {
                    if (existing == null) {
                        return new ParticipantPresence(userId, email, null, 0L);
                    }
                    return new ParticipantPresence(
                            userId,
                            email != null ? email : existing.email,
                            existing.selection,
                            existing.lastBroadcastTime
                    );
                });
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
     * Transform all stored selection ranges in a session based on a canonical operation.
     * This ensures cursors/selections stay aligned with the document after edits.
     *
     * <p>For inserts: shift affected range edges right by inserted text length.
     * <p>For deletes: clamp range edges so they never fall outside surviving text.
     *
     * @param sessionId the session whose participants' ranges to transform
     * @param operation the canonical operation that was applied to the document
     */
    public void transformSelectionsForSession(UUID sessionId, TextOperation operation) {
        Map<UUID, ParticipantPresence> sessionMap = sessions.get(sessionId);
        if (sessionMap == null) return;

        for (Map.Entry<UUID, ParticipantPresence> entry : sessionMap.entrySet()) {
            ParticipantPresence presence = entry.getValue();
            if (presence.selection == null) continue;

            SelectionRange transformed = transformRange(presence.selection, operation);
            sessionMap.put(entry.getKey(), new ParticipantPresence(
                    presence.userId, presence.email, transformed, presence.lastBroadcastTime));
        }
    }

    /**
     * Transform a single selection range against a canonical operation.
     */
    private SelectionRange transformRange(SelectionRange range, TextOperation operation) {
        if (operation instanceof InsertOperation insert) {
            return transformRangeForInsert(range, insert);
        } else if (operation instanceof DeleteOperation delete) {
            return transformRangeForDelete(range, delete);
        }
        return range;
    }

    private SelectionRange transformRangeForInsert(SelectionRange range, InsertOperation insert) {
        int insertPos = insert.position();
        int insertLen = insert.text().length();
        int newStart = range.start();
        int newEnd = range.end();

        if (insertPos <= range.start()) {
            // Insert before or at range start: shift both edges right
            newStart += insertLen;
            newEnd += insertLen;
        } else if (insertPos < range.end()) {
            // Insert inside range: only shift end right
            newEnd += insertLen;
        }
        // Insert after range: no change

        return new SelectionRange(newStart, newEnd);
    }

    private SelectionRange transformRangeForDelete(SelectionRange range, DeleteOperation delete) {
        int delStart = delete.position();
        int delEnd = delete.position() + delete.length();
        int newStart = range.start();
        int newEnd = range.end();

        // Clamp start
        if (newStart >= delEnd) {
            // Range start is after delete: shift left by delete length
            newStart -= delete.length();
        } else if (newStart > delStart) {
            // Range start is inside delete: clamp to delete start
            newStart = delStart;
        }

        // Clamp end
        if (newEnd >= delEnd) {
            // Range end is after delete: shift left by delete length
            newEnd -= delete.length();
        } else if (newEnd > delStart) {
            // Range end is inside delete: clamp to delete start
            newEnd = delStart;
        }

        return new SelectionRange(newStart, newEnd);
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
