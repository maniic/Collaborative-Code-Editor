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
import com.collabeditor.websocket.protocol.SubmitOperationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final ObjectMapper objectMapper;

    public DistributedCollaborationGateway(CollaborationSessionRegistry registry,
                                            SnapshotRecoveryService snapshotRecoveryService,
                                            CollaborationPersistenceService persistenceService,
                                            SessionCoordinationService coordinationService,
                                            CollaborationRelayService relayService,
                                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.snapshotRecoveryService = snapshotRecoveryService;
        this.persistenceService = persistenceService;
        this.coordinationService = coordinationService;
        this.relayService = relayService;
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
        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of("userId", userId, "email", email));
            relayService.publish(new CanonicalCollaborationEvent(
                    sessionId, CanonicalEventType.PARTICIPANT_JOINED, 0, userId, payloadJson, Instant.now()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize participant_joined payload for session {}", sessionId, e);
        }
    }

    /**
     * Publishes a participant_left event through the canonical Redis relay.
     */
    public void publishParticipantLeft(UUID sessionId, UUID userId, String email) {
        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of("userId", userId, "email", email));
            relayService.publish(new CanonicalCollaborationEvent(
                    sessionId, CanonicalEventType.PARTICIPANT_LEFT, 0, userId, payloadJson, Instant.now()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize participant_left payload for session {}", sessionId, e);
        }
    }

    /**
     * Publishes a presence_updated event through the canonical Redis relay.
     */
    public void publishPresenceUpdated(UUID sessionId, UUID userId, String email, SelectionRange selection) {
        try {
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of("userId", userId, "email", email,
                            "selection", Map.of("start", selection.start(), "end", selection.end())));
            relayService.publish(new CanonicalCollaborationEvent(
                    sessionId, CanonicalEventType.PRESENCE_UPDATED, 0, userId, payloadJson, Instant.now()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize presence_updated payload for session {}", sessionId, e);
        }
    }

    /**
     * Handles a received canonical relay event from Redis pub/sub.
     * Validates revision continuity and evicts runtime on gaps.
     *
     * @param event the canonical collaboration event received from Redis
     */
    public void handleRelayEvent(CanonicalCollaborationEvent event) {
        UUID sessionId = event.sessionId();

        if (event.eventType() == CanonicalEventType.OPERATION_APPLIED) {
            Optional<CollaborationSessionRuntime> cached = registry.getRuntimeIfPresent(sessionId);
            if (cached.isPresent()) {
                long expectedRevision = cached.get().snapshot().revision() + 1;
                if (event.revision() != expectedRevision) {
                    log.warn("Revision gap detected for session {}: expected {} but got {}. Evicting runtime.",
                            sessionId, expectedRevision, event.revision());
                    registry.evictRuntime(sessionId);
                }
            }
        }
        // Event delivery to local sockets is handled by the WebSocket handler layer
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
        try {
            TextOperation canonical = result.canonicalOperation();
            String payloadJson;
            if (canonical instanceof InsertOperation insert) {
                payloadJson = objectMapper.writeValueAsString(Map.of(
                        "userId", userId,
                        "revision", result.revision(),
                        "operationType", "INSERT",
                        "position", insert.position(),
                        "text", insert.text()));
            } else if (canonical instanceof DeleteOperation delete) {
                payloadJson = objectMapper.writeValueAsString(Map.of(
                        "userId", userId,
                        "revision", result.revision(),
                        "operationType", "DELETE",
                        "position", delete.position(),
                        "length", delete.length()));
            } else {
                throw new IllegalStateException("Unknown operation type: " + canonical.getClass());
            }

            relayService.publish(new CanonicalCollaborationEvent(
                    sessionId, CanonicalEventType.OPERATION_APPLIED,
                    result.revision(), userId, payloadJson, Instant.now()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize operation_applied payload for session {}", sessionId, e);
        }
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
}
