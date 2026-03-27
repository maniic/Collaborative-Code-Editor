package com.collabeditor.websocket.security;

import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.auth.service.JwtTokenService.TokenIdentity;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.session.persistence.entity.SessionParticipantEntity;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates JWT bearer token during WebSocket handshake and enforces
 * that the connecting user is an ACTIVE participant of the target session.
 *
 * <p>Extracts sessionId from the URI path {@code /ws/sessions/{sessionId}}
 * and stores sessionId, userId, and email on the WebSocket session attributes.
 */
@Component
public class CollaborationHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CollaborationHandshakeInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String WS_PATH_PREFIX = "/ws/sessions/";

    private final JwtTokenService jwtTokenService;
    private final SessionParticipantRepository participantRepository;

    public CollaborationHandshakeInterceptor(JwtTokenService jwtTokenService,
                                              SessionParticipantRepository participantRepository) {
        this.jwtTokenService = jwtTokenService;
        this.participantRepository = participantRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // Extract sessionId from URI path
        UUID sessionId = extractSessionId(request.getURI());
        if (sessionId == null) {
            log.debug("WebSocket handshake rejected: invalid session path");
            return false;
        }

        // Extract and validate bearer token
        String token = extractBearerToken(request.getHeaders());
        if (token == null) {
            log.debug("WebSocket handshake rejected: missing bearer token");
            return false;
        }

        Optional<Claims> claimsOpt = jwtTokenService.parseToken(token);
        if (claimsOpt.isEmpty()) {
            log.debug("WebSocket handshake rejected: invalid jwt");
            return false;
        }

        Optional<TokenIdentity> identityOpt = jwtTokenService.extractIdentity(claimsOpt.get());
        if (identityOpt.isEmpty()) {
            log.debug("WebSocket handshake rejected: invalid identity claims");
            return false;
        }

        TokenIdentity identity = identityOpt.get();

        // Verify active room membership
        Optional<SessionParticipantEntity> participantOpt =
                participantRepository.findBySessionIdAndUserId(sessionId, identity.userId());
        if (participantOpt.isEmpty() || !"ACTIVE".equals(participantOpt.get().getStatus())) {
            log.debug("WebSocket handshake rejected: non-active participant userId={} sessionId={}",
                    identity.userId(), sessionId);
            return false;
        }

        // Store identity on socket attributes for handler use
        attributes.put("sessionId", sessionId);
        attributes.put("userId", identity.userId());
        attributes.put("email", identity.email());

        log.debug("WebSocket handshake accepted: userId={} sessionId={}", identity.userId(), sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    private String extractBearerToken(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private UUID extractSessionId(URI uri) {
        String path = uri.getPath();
        if (path != null && path.startsWith(WS_PATH_PREFIX)) {
            String sessionIdStr = path.substring(WS_PATH_PREFIX.length());
            // Remove trailing slash if present
            if (sessionIdStr.endsWith("/")) {
                sessionIdStr = sessionIdStr.substring(0, sessionIdStr.length() - 1);
            }
            try {
                return UUID.fromString(sessionIdStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
