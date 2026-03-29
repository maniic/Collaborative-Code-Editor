package com.collabeditor.websocket;

import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.ot.model.DeleteOperation;
import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.OperationalTransformService;
import com.collabeditor.websocket.model.SelectionRange;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.websocket.handler.CollaborationWebSocketHandler;
import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
import com.collabeditor.websocket.service.DistributedCollaborationGateway;
import com.collabeditor.execution.service.ExecutionBroadcastGateway;
import com.collabeditor.websocket.service.PresenceService;
import com.collabeditor.websocket.service.SubmissionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationWebSocketHandlerTest {

    @Mock private CollaborationSessionRegistry registry;
    @Mock private DistributedCollaborationGateway gateway;
    @Mock private ExecutionBroadcastGateway executionBroadcastGateway;
    @Mock private SessionParticipantRepository participantRepository;
    @Mock private UserRepository userRepository;
    @Mock private WebSocketSession senderSocket;
    @Mock private WebSocketSession peerSocket;

    private CollaborationWebSocketHandler handler;
    private PresenceService presenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID peerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String email = "user@example.com";
    private final String peerEmail = "peer@example.com";

    @BeforeEach
    void setUp() {
        presenceService = new PresenceService(75);
        handler = new CollaborationWebSocketHandler(registry, gateway, executionBroadcastGateway, participantRepository,
                userRepository, presenceService, objectMapper);
    }

    private Map<String, Object> socketAttributes(UUID sid, UUID uid) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionId", sid);
        attrs.put("userId", uid);
        attrs.put("email", email);
        return attrs;
    }

    @Nested
    @DisplayName("Connection bootstrap -- document_sync from persisted document")
    class DocumentSync {

        @Test
        @DisplayName("afterConnectionEstablished sends document_sync with persisted document, revision, and participant snapshot")
        void sendsDocumentSyncOnConnect() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            when(gateway.connectSnapshot(sessionId)).thenReturn(new DocumentSnapshot("hello", 3));

            UserEntity userEntity = new UserEntity(userId, email, "hash");
            when(userRepository.findById(userId)).thenReturn(Optional.of(userEntity));

            SessionParticipantEntity participant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of(participant));

            handler.afterConnectionEstablished(senderSocket);

            verify(registry).addSocket(sessionId, senderSocket);
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            // First message is document_sync
            CollaborationEnvelope envelope = objectMapper.readValue(
                    captor.getAllValues().get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(envelope.type()).isEqualTo("document_sync");

            JsonNode payload = envelope.payload();
            assertThat(payload.get("document").asText()).isEqualTo("hello");
            assertThat(payload.get("revision").asLong()).isEqualTo(3);
            assertThat(payload.get("participants")).isNotNull();
            assertThat(payload.get("participants").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("document_sync bootstraps from persisted document state, not empty room")
        void documentSyncFromPersistedState() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            // Gateway returns persisted document state (non-empty, non-zero revision)
            when(gateway.connectSnapshot(sessionId)).thenReturn(new DocumentSnapshot("persisted document content", 42));

            UserEntity userEntity = new UserEntity(userId, email, "hash");
            when(userRepository.findById(userId)).thenReturn(Optional.of(userEntity));
            when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of());

            handler.afterConnectionEstablished(senderSocket);

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            CollaborationEnvelope envelope = objectMapper.readValue(
                    captor.getAllValues().get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(envelope.type()).isEqualTo("document_sync");
            assertThat(envelope.payload().get("document").asText()).isEqualTo("persisted document content");
            assertThat(envelope.payload().get("revision").asLong()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("Submit operation -- operation_ack and relay delegation")
    class SubmitOperation {

        @Test
        @DisplayName("valid submit_operation produces operation_ack for sender and leaves fan-out to the relay")
        void validSubmitProducesAckAndRelayDelegation() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-1", 0, "hi");
            DocumentSnapshot snapshot = new DocumentSnapshot("hi", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snapshot));

            // Build submit_operation message
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-1",
                            "baseRevision", 0,
                            "operationType", "INSERT",
                            "position", 0,
                            "text", "hi"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            // Verify ack sent to sender
            ArgumentCaptor<TextMessage> senderCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(senderCaptor.capture());

            List<TextMessage> senderMessages = senderCaptor.getAllValues();
            CollaborationEnvelope ackEnvelope = objectMapper.readValue(
                    senderMessages.get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(ackEnvelope.type()).isEqualTo("operation_ack");
            assertThat(ackEnvelope.payload().get("clientOperationId").asText()).isEqualTo("op-1");
            assertThat(ackEnvelope.payload().get("revision").asLong()).isEqualTo(1);

            verify(gateway).submitOperation(eq(sessionId), eq(userId), any());
            verify(peerSocket, never()).sendMessage(any());
        }
    }

    @Nested
    @DisplayName("Error handling -- operation_error")
    class OperationError {

        @Test
        @DisplayName("malformed payload yields operation_error and keeps socket open")
        void malformedPayloadYieldsOperationError() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            // Send invalid JSON
            String json = "not valid json at all";
            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());
            verify(senderSocket, never()).close(any());

            CollaborationEnvelope errorEnvelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(errorEnvelope.type()).isEqualTo("operation_error");
            assertThat(errorEnvelope.payload().get("error").asText()).contains("Malformed");
        }

        @Test
        @DisplayName("missing operationType yields operation_error")
        void missingOperationTypeYieldsError() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-2",
                            "baseRevision", 0,
                            "position", 0
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            CollaborationEnvelope errorEnvelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(errorEnvelope.type()).isEqualTo("operation_error");
            assertThat(errorEnvelope.payload().get("error").asText()).contains("operationType");
        }
    }

    @Nested
    @DisplayName("Authorization -- non-active participant")
    class Authorization {

        @Test
        @DisplayName("message from non-active participant sends error and closes socket")
        void nonActiveParticipantClosesSocket() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            // Participant is LEFT
            SessionParticipantEntity leftParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "LEFT");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(leftParticipant));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-3",
                            "baseRevision", 0,
                            "operationType", "INSERT",
                            "position", 0,
                            "text", "x"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            verify(senderSocket).close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Nested
    @DisplayName("Desync -- resync_required")
    class Resync {

        @Test
        @DisplayName("future base revision triggers resync_required with full snapshot")
        void futureRevisionTriggersResync() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenThrow(new IllegalArgumentException("base revision 99 is ahead of canonical revision 1"));
            when(gateway.connectSnapshot(sessionId)).thenReturn(new DocumentSnapshot("current doc", 1));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-4",
                            "baseRevision", 99,
                            "operationType", "INSERT",
                            "position", 0,
                            "text", "x"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            CollaborationEnvelope resyncEnvelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(resyncEnvelope.type()).isEqualTo("resync_required");
            assertThat(resyncEnvelope.payload().get("document").asText()).isEqualTo("current doc");
            assertThat(resyncEnvelope.payload().get("revision").asLong()).isEqualTo(1);
            assertThat(resyncEnvelope.payload().get("reason").asText()).contains("rejected");
        }
    }

    @Nested
    @DisplayName("Connection closed -- cleanup")
    class ConnectionClosed {

        @Test
        @DisplayName("afterConnectionClosed removes socket from registry and publishes participant_left")
        void removesSocketOnClose() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            // Pre-join so presence has email to look up
            presenceService.join(sessionId, userId, email);

            handler.afterConnectionClosed(senderSocket, CloseStatus.NORMAL);

            verify(registry).removeSocket(sessionId, senderSocket);
            verify(gateway).publishParticipantLeft(sessionId, userId, email);
        }
    }

    @Nested
    @DisplayName("Contract -- document_sync bootstrap fields")
    class DocumentSyncBootstrapContract {

        @Test
        @DisplayName("document_sync contains document, revision, and participants fields")
        void documentSyncContainsExpectedFields() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            when(gateway.connectSnapshot(sessionId)).thenReturn(new DocumentSnapshot("", 0));

            UserEntity userEntity = new UserEntity(userId, email, "hash");
            UserEntity peerEntity = new UserEntity(peerId, peerEmail, "hash");
            when(userRepository.findById(userId)).thenReturn(Optional.of(userEntity));
            when(userRepository.findById(peerId)).thenReturn(Optional.of(peerEntity));

            SessionParticipantEntity p1 = new SessionParticipantEntity(sessionId, userId, "OWNER", "ACTIVE");
            SessionParticipantEntity p2 = new SessionParticipantEntity(sessionId, peerId, "MEMBER", "ACTIVE");
            when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of(p1, p2));

            handler.afterConnectionEstablished(senderSocket);

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            // First message is document_sync
            CollaborationEnvelope envelope = objectMapper.readValue(
                    captor.getAllValues().get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(envelope.type()).isEqualTo("document_sync");

            JsonNode payload = envelope.payload();
            assertThat(payload.has("document")).isTrue();
            assertThat(payload.has("revision")).isTrue();
            assertThat(payload.has("participants")).isTrue();
            assertThat(payload.get("document").asText()).isEmpty();
            assertThat(payload.get("revision").asLong()).isEqualTo(0);
            assertThat(payload.get("participants").size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Contract -- handler does not bypass relay delivery")
    class RelayOwnedFanOutContract {

        @Test
        @DisplayName("peer socket does not receive operation_applied directly from the handler")
        void peerOperationAppliedIsRelayOwned() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            // Simulate server transforming and advancing revision
            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-peer", 5, "world");
            DocumentSnapshot snapshot = new DocumentSnapshot("helloworld", 7);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(7, canonicalOp, snapshot));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-peer",
                            "baseRevision", 3,
                            "operationType", "INSERT",
                            "position", 5,
                            "text", "world"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            verify(peerSocket, never()).sendMessage(any());
        }
    }

    @Nested
    @DisplayName("Contract -- unknown message type")
    class UnknownMessageType {

        @Test
        @DisplayName("unknown message type yields operation_error")
        void unknownTypeYieldsError() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "cursor_update",
                    "payload", Map.of("position", 5)
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            CollaborationEnvelope errorEnvelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(errorEnvelope.type()).isEqualTo("operation_error");
            assertThat(errorEnvelope.payload().get("error").asText()).contains("Unknown message type");
        }
    }

    @Nested
    @DisplayName("Contract -- DELETE operation flow")
    class DeleteOperationContract {

        @Test
        @DisplayName("valid DELETE submit_operation produces ack and leaves the broadcast to the relay")
        void validDeleteProducesAckOnly() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            DeleteOperation canonicalOp = new DeleteOperation(userId, 0, "op-del", 2, 3);
            DocumentSnapshot snapshot = new DocumentSnapshot("he", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snapshot));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-del",
                            "baseRevision", 0,
                            "operationType", "DELETE",
                            "position", 2,
                            "length", 3
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            List<TextMessage> messages = captor.getAllValues();
            // First message is ack
            CollaborationEnvelope ack = objectMapper.readValue(messages.get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(ack.type()).isEqualTo("operation_ack");
            assertThat(ack.payload().get("clientOperationId").asText()).isEqualTo("op-del");
            assertThat(messages).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Contract -- missing envelope type field")
    class MissingTypeField {

        @Test
        @DisplayName("envelope with null type yields operation_error")
        void nullTypeYieldsError() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            // Send envelope without type
            String json = "{\"payload\":{\"foo\":\"bar\"}}";

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            CollaborationEnvelope errorEnvelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(errorEnvelope.type()).isEqualTo("operation_error");
            assertThat(errorEnvelope.payload().get("error").asText()).contains("missing type");
        }
    }

    @Nested
    @DisplayName("Phase 2 Final -- explicit join/leave events through relay")
    class ExplicitJoinLeaveEvents {

        @Test
        @DisplayName("participant_joined is published through the gateway on connect")
        void participantJoinedPublishedThroughGateway() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            when(gateway.connectSnapshot(sessionId)).thenReturn(new DocumentSnapshot("", 0));
            when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of());

            UserEntity userEntity = new UserEntity(userId, email, "hash");
            when(userRepository.findById(userId)).thenReturn(Optional.of(userEntity));

            handler.afterConnectionEstablished(senderSocket);

            // Verify gateway.publishParticipantJoined was called
            verify(gateway).publishParticipantJoined(sessionId, userId, email);

            // Verify document_sync was sent first
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            CollaborationEnvelope envelope = objectMapper.readValue(
                    captor.getAllValues().get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(envelope.type()).isEqualTo("document_sync");
        }

        @Test
        @DisplayName("participant_left is published through the gateway on disconnect")
        void participantLeftPublishedThroughGateway() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            presenceService.join(sessionId, userId, email);

            handler.afterConnectionClosed(senderSocket, CloseStatus.NORMAL);

            verify(gateway).publishParticipantLeft(sessionId, userId, email);
        }
    }

    @Nested
    @DisplayName("Phase 3 -- sender ack is owned by the handler while relay owns fan-out")
    class SenderAckContract {

        @Test
        @DisplayName("sender receives only operation_ack from the handler path")
        void senderReceivesOnlyAckFromHandlerPath() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-self", 0, "hello");
            DocumentSnapshot snap = new DocumentSnapshot("hello", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snap));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-self",
                            "baseRevision", 0,
                            "operationType", "INSERT",
                            "position", 0,
                            "text", "hello"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            List<CollaborationEnvelope> envelopes = captor.getAllValues().stream()
                    .map(msg -> {
                        try { return objectMapper.readValue(msg.getPayload(), CollaborationEnvelope.class); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).toList();

            assertThat(envelopes).hasSize(1);
            assertThat(envelopes.get(0).type()).isEqualTo("operation_ack");
            assertThat(envelopes.get(0).payload().get("clientOperationId").asText()).isEqualTo("op-self");
            assertThat(envelopes.get(0).payload().get("revision").asLong()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Phase 3 -- selection transforms wait for relay application")
    class RelayDrivenSelectionTransforms {

        @Test
        @DisplayName("selection ranges are not transformed until the relay event is consumed")
        void selectionRangesWaitForRelayOnInsert() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "opIns", 0, "abc");
            DocumentSnapshot snap = new DocumentSnapshot("abc", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snap));

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            // Register peer's presence with a selection range
            presenceService.join(sessionId, peerId, peerEmail);
            presenceService.updateSelection(sessionId, peerId, new SelectionRange(5, 10));

            // User A inserts 3 chars at position 0 (before peer's range)
            String insertJson = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "opIns", "baseRevision", 0,
                            "operationType", "INSERT", "position", 0, "text", "abc")));

            handler.handleTextMessage(senderSocket, new TextMessage(insertJson));

            // No local transform happens until the canonical relay event is consumed.
            SelectionRange peerRange = presenceService.getSelection(sessionId, peerId);
            assertThat(peerRange).isNotNull();
            assertThat(peerRange.start()).isEqualTo(5);
            assertThat(peerRange.end()).isEqualTo(10);
        }

        @Test
        @DisplayName("selection ranges are not transformed on delete until the relay event is consumed")
        void selectionRangesWaitForRelayOnDelete() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            DeleteOperation canonicalOp = new DeleteOperation(userId, 1, "opDel", 0, 6);
            DocumentSnapshot snap = new DocumentSnapshot("world", 2);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(2, canonicalOp, snap));

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            // Register peer's presence with selection on "world" (positions 6-11)
            presenceService.join(sessionId, peerId, peerEmail);
            presenceService.updateSelection(sessionId, peerId, new SelectionRange(6, 11));

            // User A deletes "hello " (positions 0-6)
            String deleteJson = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "opDel", "baseRevision", 1,
                            "operationType", "DELETE", "position", 0, "length", 6)));

            handler.handleTextMessage(senderSocket, new TextMessage(deleteJson));

            // No local transform happens until the canonical relay event is consumed.
            SelectionRange peerRange = presenceService.getSelection(sessionId, peerId);
            assertThat(peerRange).isNotNull();
            assertThat(peerRange.start()).isEqualTo(6);
            assertThat(peerRange.end()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("Phase 3 -- presence updates relay through gateway")
    class PresenceRelayThroughGateway {

        @Test
        @DisplayName("update_presence publishes through gateway.publishPresenceUpdated when throttle passes")
        void presenceUpdateRelaysThroughGateway() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            // Pre-join so presence service has email
            presenceService.join(sessionId, userId, email);

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "update_presence",
                    "payload", Map.of(
                            "selection", Map.of("start", 5, "end", 10)
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            // Verify presence was published through the gateway relay
            verify(gateway).publishPresenceUpdated(eq(sessionId), eq(userId), eq(email),
                    eq(new SelectionRange(5, 10)));
            verify(senderSocket, never()).sendMessage(any());
        }
    }

    @Nested
    @DisplayName("Phase 3 -- submit delegates to gateway durable coordination path")
    class DurableCoordinationPath {

        @Test
        @DisplayName("submitOperation delegates to gateway which handles persist, revision sync, and relay")
        void submitDelegatesToGateway() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-durable", 0, "test");
            DocumentSnapshot snap = new DocumentSnapshot("test", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snap));

            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "submit_operation",
                    "payload", Map.of(
                            "clientOperationId", "op-durable",
                            "baseRevision", 0,
                            "operationType", "INSERT",
                            "position", 0,
                            "text", "test"
                    )
            ));

            handler.handleTextMessage(senderSocket, new TextMessage(json));

            // Verify gateway.submitOperation was called (which internally does persist + revision + relay)
            verify(gateway).submitOperation(eq(sessionId), eq(userId), any());

            // Verify operation_ack was sent after gateway completes
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket, atLeast(1)).sendMessage(captor.capture());

            CollaborationEnvelope ack = objectMapper.readValue(
                    captor.getAllValues().get(0).getPayload(), CollaborationEnvelope.class);
            assertThat(ack.type()).isEqualTo("operation_ack");
            assertThat(ack.payload().get("revision").asLong()).isEqualTo(1);
        }
    }
}
