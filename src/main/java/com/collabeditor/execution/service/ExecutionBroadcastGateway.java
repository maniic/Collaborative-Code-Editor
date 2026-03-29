package com.collabeditor.execution.service;

import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.protocol.ExecutionUpdatedPayload;
import com.collabeditor.websocket.protocol.ServerMessageType;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridges Redis-relayed execution updates to local room WebSocket sockets.
 */
@Service
public class ExecutionBroadcastGateway {

    private static final Logger log = LoggerFactory.getLogger(ExecutionBroadcastGateway.class);

    private final CollaborationSessionRegistry registry;
    private final ExecutionEventRelayService relayService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, ExecutionEventRelayService.Subscription> subscriptions =
            new ConcurrentHashMap<>();

    public ExecutionBroadcastGateway(CollaborationSessionRegistry registry,
                                     ExecutionEventRelayService relayService,
                                     ObjectMapper objectMapper) {
        this.registry = registry;
        this.relayService = relayService;
        this.objectMapper = objectMapper;
    }

    public void ensureSessionSubscription(UUID sessionId) {
        subscriptions.computeIfAbsent(sessionId, ignored ->
                relayService.subscribe(sessionId, payload -> broadcast(sessionId, payload)));
    }

    public void unsubscribeSession(UUID sessionId) {
        ExecutionEventRelayService.Subscription subscription = subscriptions.remove(sessionId);
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    private void broadcast(UUID sessionId, ExecutionUpdatedPayload payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(
                    new CollaborationEnvelope(
                            ServerMessageType.execution_updated.name(),
                            objectMapper.valueToTree(payload)
                    )
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize execution_updated for session {}", sessionId, e);
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession socket : registry.getSockets(sessionId)) {
            if (!socket.isOpen()) {
                continue;
            }
            try {
                socket.sendMessage(message);
            } catch (IOException e) {
                log.warn("Failed to send execution_updated to socket {}: {}", socket.getId(), e.getMessage());
            }
        }
    }
}
