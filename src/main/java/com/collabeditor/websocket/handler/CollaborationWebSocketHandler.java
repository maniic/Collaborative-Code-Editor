package com.collabeditor.websocket.handler;

import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.websocket.protocol.*;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
import com.collabeditor.websocket.service.DistributedCollaborationGateway;
import com.collabeditor.execution.service.ExecutionBroadcastGateway;
import com.collabeditor.websocket.service.PresenceService;
import com.collabeditor.websocket.service.SubmissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles raw WebSocket collaboration messages for a session room.
 *
 * <p>On connect: sends {@code document_sync} with current document, revision,
 * and participant snapshot via the {@link DistributedCollaborationGateway}.
 *
 * <p>On message: deserializes {@code submit_operation}, delegates to the gateway
 * for durable coordination, sends {@code operation_ack} to sender and publishes
 * canonical events through the Redis relay path.
 *
 * <p>On error: sends {@code operation_error} for validation failures,
 * {@code resync_required} for unrecoverable desyncs.
 */
@Component
public class CollaborationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CollaborationWebSocketHandler.class);

    private final CollaborationSessionRegistry registry;
    private final DistributedCollaborationGateway gateway;
    private final ExecutionBroadcastGateway executionBroadcastGateway;
    private final SessionParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final PresenceService presenceService;
    private final ObjectMapper objectMapper;

    public CollaborationWebSocketHandler(CollaborationSessionRegistry registry,
                                          DistributedCollaborationGateway gateway,
                                          ExecutionBroadcastGateway executionBroadcastGateway,
                                          SessionParticipantRepository participantRepository,
                                          UserRepository userRepository,
                                          PresenceService presenceService,
                                          ObjectMapper objectMapper) {
        this.registry = registry;
        this.gateway = gateway;
        this.executionBroadcastGateway = executionBroadcastGateway;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.presenceService = presenceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID sessionId = getSessionId(session);
        UUID userId = getUserId(session);

        log.debug("WebSocket connected: userId={} sessionId={}", userId, sessionId);

        // Resolve email from user store
        String email = resolveEmail(userId);

        // Register socket and presence
        registry.addSocket(sessionId, session);
        presenceService.join(sessionId, userId, email);

        // Bootstrap from durable state via the distributed gateway
        DocumentSnapshot snapshot = gateway.connectSnapshot(sessionId);
        executionBroadcastGateway.ensureSessionSubscription(sessionId);

        // Build participant list from active participants in the session
        List<ParticipantInfo> participants = participantRepository
                .findActiveBySessionIdOrdered(sessionId)
                .stream()
                .map(p -> new ParticipantInfo(p.getUserId(), resolveEmail(p.getUserId())))
                .toList();

        DocumentSyncPayload syncPayload = new DocumentSyncPayload(
                snapshot.document(), snapshot.revision(), participants);

        sendMessage(session, ServerMessageType.document_sync, syncPayload);

        // Publish participant_joined through canonical relay
        gateway.publishParticipantJoined(sessionId, userId, email);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        UUID sessionId = getSessionId(session);
        UUID userId = getUserId(session);

        // Re-check active membership for each message
        Optional<SessionParticipantEntity> participantOpt =
                participantRepository.findBySessionIdAndUserId(sessionId, userId);
        if (participantOpt.isEmpty() || !"ACTIVE".equals(participantOpt.get().getStatus())) {
            log.debug("WebSocket unauthorized: userId={} no longer active in sessionId={}", userId, sessionId);
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Unauthorized: not an active participant"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Parse envelope
        CollaborationEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), CollaborationEnvelope.class);
        } catch (Exception e) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Malformed message: invalid JSON envelope"));
            return;
        }

        if (envelope.type() == null) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Malformed message: missing type"));
            return;
        }

        // Dispatch by type
        if (ClientMessageType.submit_operation.name().equals(envelope.type())) {
            handleSubmitOperation(session, sessionId, userId, envelope);
        } else if (ClientMessageType.update_presence.name().equals(envelope.type())) {
            handleUpdatePresence(session, sessionId, userId, envelope);
        } else {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Unknown message type: " + envelope.type()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        UUID sessionId = getSessionId(session);
        UUID userId = getUserId(session);

        log.debug("WebSocket disconnected: userId={} sessionId={} status={}", userId, sessionId, status);

        // Resolve email before removing presence
        String email = presenceService.getEmail(sessionId, userId);

        registry.removeSocket(sessionId, session);
        if (registry.getSockets(sessionId).isEmpty()) {
            executionBroadcastGateway.unsubscribeSession(sessionId);
        }
        presenceService.leave(sessionId, userId);

        // Publish participant_left through canonical relay
        gateway.publishParticipantLeft(sessionId, userId, email);
    }

    private void handleSubmitOperation(WebSocketSession session, UUID sessionId, UUID userId,
                                        CollaborationEnvelope envelope) throws IOException {
        // Parse payload
        SubmitOperationPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), SubmitOperationPayload.class);
        } catch (Exception e) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Malformed submit_operation payload"));
            return;
        }

        // Validate required fields
        if (payload.clientOperationId() == null || payload.clientOperationId().isBlank()) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Missing clientOperationId"));
            return;
        }
        if (payload.operationType() == null) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(payload.clientOperationId(), "Missing operationType"));
            return;
        }

        // Delegate to the distributed gateway for the full durable coordination path
        SubmissionResult result;
        try {
            result = gateway.submitOperation(sessionId, userId, payload);
        } catch (IllegalArgumentException e) {
            // Future revision or other validation failure -- send resync
            DocumentSnapshot snapshot = gateway.connectSnapshot(sessionId);
            sendMessage(session, ServerMessageType.resync_required,
                    new ResyncRequiredPayload(snapshot.document(), snapshot.revision(),
                            "Operation rejected: " + e.getMessage()));
            return;
        }

        // Send ack to sender (after durable persist + relay publish)
        sendMessage(session, ServerMessageType.operation_ack,
                new OperationAckPayload(payload.clientOperationId(), result.revision()));
    }

    private void handleUpdatePresence(WebSocketSession session, UUID sessionId, UUID userId,
                                       CollaborationEnvelope envelope) throws IOException {
        PresenceUpdatePayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), PresenceUpdatePayload.class);
        } catch (Exception e) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Malformed update_presence payload"));
            return;
        }

        if (payload.selection() == null) {
            sendMessage(session, ServerMessageType.operation_error,
                    new OperationErrorPayload(null, "Missing selection in update_presence"));
            return;
        }

        // Store latest selection regardless of throttle
        presenceService.updateSelection(sessionId, userId, payload.selection());

        // Broadcast only if throttle window has passed
        if (presenceService.shouldBroadcast(sessionId, userId)) {
            presenceService.markBroadcast(sessionId, userId);
            String email = presenceService.getEmail(sessionId, userId);

            // Publish through canonical relay
            gateway.publishPresenceUpdated(sessionId, userId, email, payload.selection());
        }
    }

    private String resolveEmail(UUID userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getEmail)
                .orElse(null);
    }

    private <T> void sendMessage(WebSocketSession session, ServerMessageType type, T payload) throws IOException {
        CollaborationEnvelope envelope = new CollaborationEnvelope(
                type.name(), objectMapper.valueToTree(payload));
        String json = objectMapper.writeValueAsString(envelope);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }

    private UUID getSessionId(WebSocketSession session) {
        return (UUID) session.getAttributes().get("sessionId");
    }

    private UUID getUserId(WebSocketSession session) {
        return (UUID) session.getAttributes().get("userId");
    }
}
