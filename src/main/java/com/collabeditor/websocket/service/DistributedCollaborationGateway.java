package com.collabeditor.websocket.service;

import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.service.CollaborationPersistenceService;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.CollaborationSessionRuntime.ApplyResult;
import com.collabeditor.redis.model.CanonicalCollaborationEvent;
import com.collabeditor.redis.model.CanonicalEventType;
import com.collabeditor.redis.service.CollaborationRelayService;
import com.collabeditor.redis.service.SessionCoordinationService;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import com.collabeditor.websocket.model.SelectionRange;
import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.protocol.OperationAppliedPayload;
import com.collabeditor.websocket.protocol.ParticipantJoinedPayload;
import com.collabeditor.websocket.protocol.ParticipantLeftPayload;
import com.collabeditor.websocket.protocol.PresenceUpdatedPayload;
import com.collabeditor.websocket.protocol.ResyncRequiredPayload;
import com.collabeditor.websocket.protocol.ServerMessageType;
import com.collabeditor.websocket.protocol.SubmitOperationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridge from the existing raw WebSocket contract to durable persistence
 * and Redis coordination.
 *
 * <p>Orchestrates the canonical apply path: lazy runtime rebuild from durable state,
 * distributed locking via Redis, durable append to PostgreSQL, revision mirror update,
 * and canonical relay event publishing.
 *
 * <p>The handler delegates to this gateway instead of directly mutating runtimes.
 */
@Service
public class DistributedCollaborationGateway {

    private static final Logger log = LoggerFactory.getLogger(DistributedCollaborationGateway.class);

    private final CollaborationSessionRegistry registry;
    private final SnapshotRecoveryService snapshotRecoveryService;
    private final CollaborationPersistenceService persistenceService;
    private final SessionCoordinationService coordinationService;
    private final CollaborationRelayService relayService;
    private final PresenceService presenceService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, CollaborationRelayService.Subscription> relaySubscriptions =
            new ConcurrentHashMap<>();

    public DistributedCollaborationGateway(CollaborationSessionRegistry registry,
                                            SnapshotRecoveryService snapshotRecoveryService,
                                            CollaborationPersistenceService persistenceService,
                                            SessionCoordinationService coordinationService,
                                            CollaborationRelayService relayService,
                                            PresenceService presenceService,
                                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.snapshotRecoveryService = snapshotRecoveryService;
        this.persistenceService = persistenceService;
        this.coordinationService = coordinationService;
        this.relayService = relayService;
        this.presenceService = presenceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns a document snapshot for bootstrap, lazily rebuilding the runtime
     * from durable state if not cached on this instance.
     *
     * @param sessionId the collaboration session identity
     * @return the current canonical document snapshot
     */
    public DocumentSnapshot connectSnapshot(UUID sessionId) {
        CollaborationSessionRuntime runtime = getOrRebuildRuntime(sessionId);
        DocumentSnapshot snapshot = runtime.snapshot();

        // Ensure Redis revision mirror is initialized from the durable state
        coordinationService.initializeRevisionIfAbsent(sessionId, snapshot.revision());
        coordinationService.markSessionActive(sessionId);
        ensureRelaySubscription(sessionId);

        return snapshot;
    }

    /**
     * Applies a client operation through the full durable coordination path:
     * lock, hydrate runtime, apply OT, persist, update Redis revision, publish relay event.
     *
     * @param sessionId the collaboration session identity
     * @param userId    the submitting user
     * @param payload   the client operation payload
     * @return the submission result with canonical revision and operation
     */
    public SubmissionResult submitOperation(UUID sessionId, UUID userId, SubmitOperationPayload payload) {
        return coordinationService.withSessionLock(sessionId, () -> {
            CollaborationSessionRuntime runtime = getOrRebuildRuntime(sessionId);
            coordinationService.markSessionActive(sessionId);

            // Build the TextOperation from the payload
            TextOperation operation = buildOperation(payload, userId);

            // Apply through the canonical runtime
            ApplyResult result = runtime.applyClientOperation(operation);

            // Persist the accepted operation durably
            persistenceService.appendAcceptedOperation(
                    sessionId, result.canonicalOperation(), result.revision(), result.snapshot());

            // Update Redis revision mirror
            coordinationService.setRevision(sessionId, result.revision());

            // Publish canonical relay event
            publishOperationApplied(sessionId, userId, result);

            return new SubmissionResult(result.revision(), result.canonicalOperation(), result.snapshot());
        });
    }

    /**
     * Publishes a participant_joined event through the canonical Redis relay.
     */
    public void publishParticipantJoined(UUID sessionId, UUID userId, String email) {
        publishCanonicalEvent(sessionId, CanonicalEventType.PARTICIPANT_JOINED, 0, userId,
                new ParticipantRelayPayload(userId, email));
    }

    /**
     * Publishes a participant_left event through the canonical Redis relay.
     */
    public void publishParticipantLeft(UUID sessionId, UUID userId, String email) {
        publishCanonicalEvent(sessionId, CanonicalEventType.PARTICIPANT_LEFT, 0, userId,
                new ParticipantRelayPayload(userId, email));
        if (registry.getSockets(sessionId).isEmpty()) {
            unsubscribeRelay(sessionId);
        }
    }

    /**
     * Publishes a presence_updated event through the canonical Redis relay.
     */
    public void publishPresenceUpdated(UUID sessionId, UUID userId, String email, SelectionRange selection) {
        publishCanonicalEvent(sessionId, CanonicalEventType.PRESENCE_UPDATED, 0, userId,
                new PresenceRelayPayload(userId, email, selection));
    }

    /**
     * Handles a received canonical relay event from Redis pub/sub.
     * Validates revision continuity and evicts runtime on gaps.
     *
     * @param event the canonical collaboration event received from Redis
     */
    public void handleRelayEvent(CanonicalCollaborationEvent event) {
        switch (event.eventType()) {
            case OPERATION_APPLIED -> handleOperationApplied(event);
            case PARTICIPANT_JOINED -> handleParticipantJoined(event);
            case PARTICIPANT_LEFT -> handleParticipantLeft(event);
            case PRESENCE_UPDATED -> handlePresenceUpdated(event);
        }
    }

    private CollaborationSessionRuntime getOrRebuildRuntime(UUID sessionId) {
        Optional<CollaborationSessionRuntime> cached = registry.getRuntimeIfPresent(sessionId);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Lazy rebuild from durable state
        CollaborationSessionRuntime runtime = snapshotRecoveryService.loadRuntime(sessionId);
        registry.cacheRuntime(sessionId, runtime);
        return runtime;
    }

    private void publishOperationApplied(UUID sessionId, UUID userId, ApplyResult result) {
        TextOperation canonical = result.canonicalOperation();
        if (canonical instanceof InsertOperation insert) {
            publishCanonicalEvent(sessionId, CanonicalEventType.OPERATION_APPLIED, result.revision(), userId,
                    new OperationRelayPayload(
                            userId,
                            result.revision(),
                            "INSERT",
                            insert.position(),
                            insert.text(),
                            null,
                            insert.clientOperationId()
                    ));
            return;
        }

        if (canonical instanceof DeleteOperation delete) {
            publishCanonicalEvent(sessionId, CanonicalEventType.OPERATION_APPLIED, result.revision(), userId,
                    new OperationRelayPayload(
                            userId,
                            result.revision(),
                            "DELETE",
                            delete.position(),
                            null,
                            delete.length(),
                            delete.clientOperationId()
                    ));
            return;
        }

        throw new IllegalStateException("Unknown operation type: " + canonical.getClass());
    }

    private TextOperation buildOperation(SubmitOperationPayload payload, UUID userId) {
        return switch (payload.operationType()) {
            case "INSERT" -> {
                if (payload.text() == null || payload.text().isEmpty()) {
                    throw new IllegalArgumentException("INSERT operation requires non-empty text");
                }
                yield new InsertOperation(userId, payload.baseRevision(),
                        payload.clientOperationId(), payload.position(), payload.text());
            }
            case "DELETE" -> {
                if (payload.length() == null || payload.length() <= 0) {
                    throw new IllegalArgumentException("DELETE operation requires positive length");
                }
                yield new DeleteOperation(userId, payload.baseRevision(),
                        payload.clientOperationId(), payload.position(), payload.length());
            }
            default -> throw new IllegalArgumentException(
                    "Unknown operationType: " + payload.operationType());
        };
    }

    private void handleOperationApplied(CanonicalCollaborationEvent event) {
        UUID sessionId = event.sessionId();
        OperationRelayPayload payload = readPayload(event, OperationRelayPayload.class);
        if (payload == null) {
            return;
        }

        CollaborationSessionRuntime runtime = getOrRebuildRuntime(sessionId);
        long currentRevision = runtime.snapshot().revision();
        TextOperation canonicalOperation = toCanonicalOperation(
                payload,
                event.userId(),
                Math.max(0, event.revision() - 1),
                payload.clientOperationId() != null ? payload.clientOperationId() : "relay-" + event.revision()
        );

        if (currentRevision > event.revision()) {
            log.debug("Ignoring stale relay event for session {} at revision {} because runtime is already at {}",
                    sessionId, event.revision(), currentRevision);
            return;
        }

        if (currentRevision + 1 == event.revision()) {
            try {
                ApplyResult applyResult = runtime.applyClientOperation(canonicalOperation);
                if (applyResult.revision() != event.revision()) {
                    emitResyncRequired(sessionId,
                            "Relay revision mismatch: expected " + event.revision() + " but applied " + applyResult.revision());
                    return;
                }
            } catch (IllegalArgumentException ex) {
                emitResyncRequired(sessionId, "Relay apply failed: " + ex.getMessage());
                return;
            }
        } else if (currentRevision != event.revision()) {
            emitResyncRequired(sessionId,
                    "Revision gap detected: expected " + (currentRevision + 1) + " but received " + event.revision());
            return;
        }

        presenceService.transformSelectionsForSession(sessionId, canonicalOperation);
        broadcast(sessionId, ServerMessageType.operation_applied, new OperationAppliedPayload(
                event.userId(),
                event.revision(),
                payload.operationType(),
                payload.position(),
                payload.text(),
                payload.length()
        ));
    }

    private void handleParticipantJoined(CanonicalCollaborationEvent event) {
        ParticipantRelayPayload payload = readPayload(event, ParticipantRelayPayload.class);
        if (payload == null) {
            return;
        }

        presenceService.join(event.sessionId(), payload.userId(), payload.email());
        broadcast(event.sessionId(), ServerMessageType.participant_joined,
                new ParticipantJoinedPayload(payload.userId(), payload.email()));
    }

    private void handleParticipantLeft(CanonicalCollaborationEvent event) {
        ParticipantRelayPayload payload = readPayload(event, ParticipantRelayPayload.class);
        if (payload == null) {
            return;
        }

        presenceService.leave(event.sessionId(), payload.userId());
        broadcast(event.sessionId(), ServerMessageType.participant_left,
                new ParticipantLeftPayload(payload.userId(), payload.email()));
    }

    private void handlePresenceUpdated(CanonicalCollaborationEvent event) {
        PresenceRelayPayload payload = readPayload(event, PresenceRelayPayload.class);
        if (payload == null) {
            return;
        }

        presenceService.join(event.sessionId(), payload.userId(), payload.email());
        presenceService.updateSelection(event.sessionId(), payload.userId(), payload.selection());
        broadcast(event.sessionId(), ServerMessageType.presence_updated,
                new PresenceUpdatedPayload(payload.userId(), payload.email(), payload.selection()));
    }

    private void ensureRelaySubscription(UUID sessionId) {
        relaySubscriptions.computeIfAbsent(sessionId, ignored ->
                relayService.subscribe(sessionId, this::handleRelayEvent));
    }

    private void unsubscribeRelay(UUID sessionId) {
        CollaborationRelayService.Subscription subscription = relaySubscriptions.remove(sessionId);
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    private void emitResyncRequired(UUID sessionId, String reason) {
        log.warn("Resync required for session {}: {}", sessionId, reason);
        registry.evictRuntime(sessionId);
        CollaborationSessionRuntime rebuiltRuntime = snapshotRecoveryService.loadRuntime(sessionId);
        registry.cacheRuntime(sessionId, rebuiltRuntime);
        DocumentSnapshot snapshot = rebuiltRuntime.snapshot();
        broadcast(sessionId, ServerMessageType.resync_required,
                new ResyncRequiredPayload(snapshot.document(), snapshot.revision(), reason));
    }

    private void publishCanonicalEvent(UUID sessionId, CanonicalEventType eventType,
                                        long revision, UUID userId, Object payload) {
        try {
            relayService.publish(new CanonicalCollaborationEvent(
                    sessionId,
                    eventType,
                    revision,
                    userId,
                    objectMapper.writeValueAsString(payload),
                    Instant.now()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} payload for session {}", eventType, sessionId, e);
            throw new IllegalStateException("Cannot serialize canonical collaboration event", e);
        }
    }

    private <T> T readPayload(CanonicalCollaborationEvent event, Class<T> payloadType) {
        try {
            return objectMapper.readValue(event.payloadJson(), payloadType);
        } catch (IOException e) {
            log.error("Failed to parse relay payload for session {}", event.sessionId(), e);
            return null;
        }
    }

    private TextOperation toCanonicalOperation(OperationRelayPayload payload, UUID userId,
                                                long baseRevision, String clientOperationId) {
        return switch (payload.operationType()) {
            case "INSERT" -> new InsertOperation(
                    userId,
                    baseRevision,
                    clientOperationId,
                    payload.position(),
                    payload.text()
            );
            case "DELETE" -> new DeleteOperation(
                    userId,
                    baseRevision,
                    clientOperationId,
                    payload.position(),
                    payload.length()
            );
            default -> throw new IllegalArgumentException("Unknown operationType: " + payload.operationType());
        };
    }

    private void broadcast(UUID sessionId, ServerMessageType type, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(
                    new CollaborationEnvelope(type.name(), objectMapper.valueToTree(payload)));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} broadcast for session {}", type, sessionId, e);
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession socket : registry.getSockets(sessionId)) {
            if (!socket.isOpen()) {
                continue;
            }
            try {
                synchronized (socket) {
                    socket.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("Failed to send {} to socket {}: {}", type, socket.getId(), e.getMessage());
            }
        }
    }

    private record ParticipantRelayPayload(UUID userId, String email) {
    }

    private record PresenceRelayPayload(UUID userId, String email, SelectionRange selection) {
    }

    private record OperationRelayPayload(
            UUID userId,
            long revision,
            String operationType,
            int position,
            String text,
            Integer length,
            String clientOperationId
    ) {
    }
}
