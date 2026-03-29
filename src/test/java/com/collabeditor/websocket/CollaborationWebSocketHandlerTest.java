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
        handler = new CollaborationWebSocketHandler(registry, gateway, participantRepository,
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
    @DisplayName("Submit operation -- operation_ack and operation_applied")
    class SubmitOperation {

        @Test
        @DisplayName("valid submit_operation produces operation_ack for sender and operation_applied broadcast")
        void validSubmitProducesAckAndBroadcast() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);
            when(senderSocket.isOpen()).thenReturn(true);
            when(peerSocket.isOpen()).thenReturn(true);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-1", 0, "hi");
            DocumentSnapshot snapshot = new DocumentSnapshot("hi", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snapshot));

            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket, peerSocket));

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

            // Verify broadcast to peer
            ArgumentCaptor<TextMessage> peerCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(peerSocket).sendMessage(peerCaptor.capture());

            CollaborationEnvelope appliedEnvelope = objectMapper.readValue(
                    peerCaptor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(appliedEnvelope.type()).isEqualTo("operation_applied");
            assertThat(appliedEnvelope.payload().get("revision").asLong()).isEqualTo(1);
            assertThat(appliedEnvelope.payload().get("operationType").asText()).isEqualTo("INSERT");
            assertThat(appliedEnvelope.payload().get("position").asInt()).isEqualTo(0);
            assertThat(appliedEnvelope.payload().get("text").asText()).isEqualTo("hi");
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
        @DisplayName("afterConnectionClosed removes socket from registry and broadcasts participant_left")
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
    @DisplayName("Contract -- peer receives operation_applied with canonical revision")
    class PeerOperationAppliedContract {

        @Test
        @DisplayName("peer socket receives operation_applied with correct canonical revision and transformed operation")
        void peerReceivesOperationAppliedWithCanonicalRevision() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);
            when(senderSocket.isOpen()).thenReturn(true);
            when(peerSocket.isOpen()).thenReturn(true);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            // Simulate server transforming and advancing revision
            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-peer", 5, "world");
            DocumentSnapshot snapshot = new DocumentSnapshot("helloworld", 7);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(7, canonicalOp, snapshot));

            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket, peerSocket));

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

            // Verify peer received operation_applied
            ArgumentCaptor<TextMessage> peerCaptor = ArgumentCaptor.forClass(TextMessage.class);
            verify(peerSocket).sendMessage(peerCaptor.capture());

            CollaborationEnvelope applied = objectMapper.readValue(
                    peerCaptor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(applied.type()).isEqualTo("operation_applied");
            assertThat(applied.payload().get("userId").asText()).isEqualTo(userId.toString());
            assertThat(applied.payload().get("revision").asLong()).isEqualTo(7);
            assertThat(applied.payload().get("operationType").asText()).isEqualTo("INSERT");
            assertThat(applied.payload().get("position").asInt()).isEqualTo(5);
            assertThat(applied.payload().get("text").asText()).isEqualTo("world");
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
        @DisplayName("valid DELETE submit_operation produces ack and applied broadcast")
        void validDeleteProducesAckAndBroadcast() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            when(senderSocket.isOpen()).thenReturn(true);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            DeleteOperation canonicalOp = new DeleteOperation(userId, 0, "op-del", 2, 3);
            DocumentSnapshot snapshot = new DocumentSnapshot("he", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snapshot));

            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket));

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

            // Second message is broadcast (including sender)
            CollaborationEnvelope applied = objectMapper.readValue(messages.get(1).getPayload(), CollaborationEnvelope.class);
            assertThat(applied.type()).isEqualTo("operation_applied");
            assertThat(applied.payload().get("operationType").asText()).isEqualTo("DELETE");
            assertThat(applied.payload().get("position").asInt()).isEqualTo(2);
            assertThat(applied.payload().get("length").asInt()).isEqualTo(3);
            assertThat(applied.payload().get("text").isNull()).isTrue();
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
    @DisplayName("Phase 2 Final -- sender receives operation_applied broadcast after operation_ack")
    class SenderReceivesBothAckAndBroadcast {

        @Test
        @DisplayName("sender receives operation_ack then operation_applied for their own operation")
        void senderReceivesAckThenApplied() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);
            when(senderSocket.isOpen()).thenReturn(true);

            SessionParticipantEntity active = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(active));

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-self", 0, "hello");
            DocumentSnapshot snap = new DocumentSnapshot("hello", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snap));

            // Only the sender is in the room
            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket));

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
            verify(senderSocket, atLeast(2)).sendMessage(captor.capture());

            List<CollaborationEnvelope> envelopes = captor.getAllValues().stream()
                    .map(msg -> {
                        try { return objectMapper.readValue(msg.getPayload(), CollaborationEnvelope.class); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).toList();

            // First: operation_ack, then operation_applied
            assertThat(envelopes.get(0).type()).isEqualTo("operation_ack");
            assertThat(envelopes.get(0).payload().get("clientOperationId").asText()).isEqualTo("op-self");
            assertThat(envelopes.get(0).payload().get("revision").asLong()).isEqualTo(1);

            assertThat(envelopes.get(1).type()).isEqualTo("operation_applied");
            assertThat(envelopes.get(1).payload().get("userId").asText()).isEqualTo(userId.toString());
            assertThat(envelopes.get(1).payload().get("revision").asLong()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Phase 2 Final -- selection ranges survive operations")
    class SelectionRangesSurviveOps {

        @Test
        @DisplayName("selection ranges are transformed when operations are applied through the handler")
        void selectionRangesTransformedByOps() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);
            when(senderSocket.isOpen()).thenReturn(true);

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "opIns", 0, "abc");
            DocumentSnapshot snap = new DocumentSnapshot("abc", 1);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(1, canonicalOp, snap));

            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket));

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

            // Peer's range should have shifted right by 3
            SelectionRange peerRange = presenceService.getSelection(sessionId, peerId);
            assertThat(peerRange).isNotNull();
            assertThat(peerRange.start()).isEqualTo(8);  // 5 + 3
            assertThat(peerRange.end()).isEqualTo(13);   // 10 + 3
        }

        @Test
        @DisplayName("selection ranges survive delete operations and clamp correctly")
        void selectionRangesSurviveDeletes() throws Exception {
            Map<String, Object> senderAttrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(senderAttrs);
            when(senderSocket.isOpen()).thenReturn(true);

            DeleteOperation canonicalOp = new DeleteOperation(userId, 1, "opDel", 0, 6);
            DocumentSnapshot snap = new DocumentSnapshot("world", 2);
            when(gateway.submitOperation(eq(sessionId), eq(userId), any()))
                    .thenReturn(new SubmissionResult(2, canonicalOp, snap));

            when(registry.getSockets(sessionId)).thenReturn(Set.of(senderSocket));

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

            // Peer's range should be clamped and shifted: was [6,11], delete [0,6)
            SelectionRange peerRange = presenceService.getSelection(sessionId, peerId);
            assertThat(peerRange).isNotNull();
            assertThat(peerRange.start()).isEqualTo(0);
            assertThat(peerRange.end()).isEqualTo(5);
        }
    }
}
