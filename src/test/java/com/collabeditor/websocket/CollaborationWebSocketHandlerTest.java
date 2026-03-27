package com.collabeditor.websocket;

import com.collabeditor.ot.model.DocumentSnapshot;
import com.collabeditor.ot.model.InsertOperation;
import com.collabeditor.ot.model.TextOperation;
import com.collabeditor.ot.service.CollaborationSessionRuntime;
import com.collabeditor.ot.service.CollaborationSessionRuntime.ApplyResult;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.websocket.handler.CollaborationWebSocketHandler;
import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
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
    @Mock private SessionParticipantRepository participantRepository;
    @Mock private WebSocketSession senderSocket;
    @Mock private WebSocketSession peerSocket;
    @Mock private CollaborationSessionRuntime runtime;

    private CollaborationWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID peerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String email = "user@example.com";

    @BeforeEach
    void setUp() {
        handler = new CollaborationWebSocketHandler(registry, participantRepository, objectMapper);
    }

    private Map<String, Object> socketAttributes(UUID sid, UUID uid) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sessionId", sid);
        attrs.put("userId", uid);
        attrs.put("email", email);
        return attrs;
    }

    @Nested
    @DisplayName("Connection bootstrap — document_sync")
    class DocumentSync {

        @Test
        @DisplayName("afterConnectionEstablished sends document_sync with document, revision, and participant snapshot")
        void sendsDocumentSyncOnConnect() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);
            when(registry.getOrCreateRuntime(sessionId)).thenReturn(runtime);
            when(runtime.snapshot()).thenReturn(new DocumentSnapshot("hello", 3));

            SessionParticipantEntity participant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findActiveBySessionIdOrdered(sessionId)).thenReturn(List.of(participant));

            handler.afterConnectionEstablished(senderSocket);

            verify(registry).addSocket(sessionId, senderSocket);
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(senderSocket).sendMessage(captor.capture());

            CollaborationEnvelope envelope = objectMapper.readValue(
                    captor.getValue().getPayload(), CollaborationEnvelope.class);
            assertThat(envelope.type()).isEqualTo("document_sync");

            JsonNode payload = envelope.payload();
            assertThat(payload.get("document").asText()).isEqualTo("hello");
            assertThat(payload.get("revision").asLong()).isEqualTo(3);
            assertThat(payload.get("participants")).isNotNull();
            assertThat(payload.get("participants").size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Submit operation — operation_ack and operation_applied")
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

            when(registry.getOrCreateRuntime(sessionId)).thenReturn(runtime);

            InsertOperation canonicalOp = new InsertOperation(userId, 0, "op-1", 0, "hi");
            DocumentSnapshot snapshot = new DocumentSnapshot("hi", 1);
            when(runtime.applyClientOperation(any(TextOperation.class)))
                    .thenReturn(new ApplyResult(1, canonicalOp, snapshot));

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
            // First direct message is the ack, then broadcast includes sender too
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
    @DisplayName("Error handling — operation_error")
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
    @DisplayName("Authorization — non-active participant")
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
    @DisplayName("Desync — resync_required")
    class Resync {

        @Test
        @DisplayName("future base revision triggers resync_required with full snapshot")
        void futureRevisionTriggersResync() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            SessionParticipantEntity activeParticipant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId))
                    .thenReturn(Optional.of(activeParticipant));

            when(registry.getOrCreateRuntime(sessionId)).thenReturn(runtime);
            when(runtime.applyClientOperation(any(TextOperation.class)))
                    .thenThrow(new IllegalArgumentException("base revision 99 is ahead of canonical revision 1"));
            when(runtime.snapshot()).thenReturn(new DocumentSnapshot("current doc", 1));

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
    @DisplayName("Connection closed — cleanup")
    class ConnectionClosed {

        @Test
        @DisplayName("afterConnectionClosed removes socket from registry")
        void removesSocketOnClose() throws Exception {
            Map<String, Object> attrs = socketAttributes(sessionId, userId);
            when(senderSocket.getAttributes()).thenReturn(attrs);

            handler.afterConnectionClosed(senderSocket, CloseStatus.NORMAL);

            verify(registry).removeSocket(sessionId, senderSocket);
        }
    }
}
