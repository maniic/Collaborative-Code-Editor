package com.collabeditor.websocket.service;

import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.OperationalTransformService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory registry of active WebSocket sessions and their associated
 * {@link CollaborationSessionRuntime} instances.
 *
 * <p>Thread-safe: uses ConcurrentHashMap for runtime lookup and
 * CopyOnWriteArraySet for per-session socket tracking.
 */
@Component
public class CollaborationSessionRegistry {

    private final OperationalTransformService otService;
    private final Map<UUID, CollaborationSessionRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<WebSocketSession>> sessionSockets = new ConcurrentHashMap<>();

    public CollaborationSessionRegistry(OperationalTransformService otService) {
        this.otService = otService;
    }

    /**
     * Get or create the canonical runtime for a session.
     */
    public CollaborationSessionRuntime getOrCreateRuntime(UUID sessionId) {
        return runtimes.computeIfAbsent(sessionId,
                id -> new CollaborationSessionRuntime(id, otService));
    }

    /**
     * Register a WebSocket session for a collaboration room.
     */
    public void addSocket(UUID sessionId, WebSocketSession socket) {
        sessionSockets.computeIfAbsent(sessionId, id -> new CopyOnWriteArraySet<>()).add(socket);
    }

    /**
     * Remove a WebSocket session from a collaboration room.
     */
    public void removeSocket(UUID sessionId, WebSocketSession socket) {
        Set<WebSocketSession> sockets = sessionSockets.get(sessionId);
        if (sockets != null) {
            sockets.remove(socket);
            if (sockets.isEmpty()) {
                sessionSockets.remove(sessionId);
            }
        }
    }

    /**
     * Returns all active WebSocket sessions for a given collaboration room.
     */
    public Set<WebSocketSession> getSockets(UUID sessionId) {
        return sessionSockets.getOrDefault(sessionId, Set.of());
    }
}
