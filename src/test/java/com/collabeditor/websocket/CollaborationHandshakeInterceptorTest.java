package com.collabeditor.websocket;

import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.auth.service.JwtTokenService.TokenIdentity;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import com.collabeditor.websocket.security.CollaborationHandshakeInterceptor;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationHandshakeInterceptorTest {

    @Mock private JwtTokenService jwtTokenService;
    @Mock private SessionParticipantRepository participantRepository;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;
    @Mock private Claims claims;

    private CollaborationHandshakeInterceptor interceptor;
    private Map<String, Object> attributes;

    private final UUID sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID userId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final String email = "user@example.com";

    @BeforeEach
    void setUp() {
        interceptor = new CollaborationHandshakeInterceptor(jwtTokenService, participantRepository);
        attributes = new HashMap<>();
    }

    @Nested
    @DisplayName("Rejects handshake for missing bearer token")
    class MissingToken {

        @Test
        @DisplayName("missing bearer token rejects handshake")
        void missingBearerTokenRejectsHandshake() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            when(request.getHeaders()).thenReturn(headers);
            when(request.getURI()).thenReturn(new URI("/ws/sessions/" + sessionId));

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Rejects handshake for invalid jwt")
    class InvalidJwt {

        @Test
        @DisplayName("invalid jwt rejects handshake")
        void invalidJwtRejectsHandshake() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer invalid-token");
            when(request.getHeaders()).thenReturn(headers);
            when(request.getURI()).thenReturn(new URI("/ws/sessions/" + sessionId));
            when(jwtTokenService.parseToken("invalid-token")).thenReturn(Optional.empty());

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Rejects handshake for non-active participant")
    class NonActiveParticipant {

        @Test
        @DisplayName("non-active participant rejects handshake")
        void nonActiveParticipantRejectsHandshake() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer valid-token");
            when(request.getHeaders()).thenReturn(headers);
            when(request.getURI()).thenReturn(new URI("/ws/sessions/" + sessionId));
            when(jwtTokenService.parseToken("valid-token")).thenReturn(Optional.of(claims));
            when(jwtTokenService.extractIdentity(claims)).thenReturn(Optional.of(new TokenIdentity(userId, email)));

            // Participant exists but is LEFT, not ACTIVE
            SessionParticipantEntity participant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "LEFT");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.of(participant));

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("missing participant rejects handshake")
        void missingParticipantRejectsHandshake() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer valid-token");
            when(request.getHeaders()).thenReturn(headers);
            when(request.getURI()).thenReturn(new URI("/ws/sessions/" + sessionId));
            when(jwtTokenService.parseToken("valid-token")).thenReturn(Optional.of(claims));
            when(jwtTokenService.extractIdentity(claims)).thenReturn(Optional.of(new TokenIdentity(userId, email)));
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Accepts handshake for active participant")
    class AcceptsActive {

        @Test
        @DisplayName("active participant passes handshake and stores attributes")
        void activeParticipantAcceptsHandshake() throws Exception {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer valid-token");
            when(request.getHeaders()).thenReturn(headers);
            when(request.getURI()).thenReturn(new URI("/ws/sessions/" + sessionId));
            when(jwtTokenService.parseToken("valid-token")).thenReturn(Optional.of(claims));
            when(jwtTokenService.extractIdentity(claims)).thenReturn(Optional.of(new TokenIdentity(userId, email)));

            SessionParticipantEntity participant = new SessionParticipantEntity(sessionId, userId, "MEMBER", "ACTIVE");
            when(participantRepository.findBySessionIdAndUserId(sessionId, userId)).thenReturn(Optional.of(participant));

            boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

            assertThat(result).isTrue();
            assertThat(attributes.get("sessionId")).isEqualTo(sessionId);
            assertThat(attributes.get("userId")).isEqualTo(userId);
            assertThat(attributes.get("email")).isEqualTo(email);
        }
    }
}
