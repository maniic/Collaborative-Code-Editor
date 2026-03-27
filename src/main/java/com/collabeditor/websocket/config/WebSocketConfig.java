package com.collabeditor.websocket.config;

import com.collabeditor.websocket.handler.CollaborationWebSocketHandler;
import com.collabeditor.websocket.security.CollaborationHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the raw WebSocket endpoint at {@code /ws/sessions/{sessionId}}.
 * Authentication and room membership are enforced during handshake.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CollaborationWebSocketHandler collaborationHandler;
    private final CollaborationHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(CollaborationWebSocketHandler collaborationHandler,
                           CollaborationHandshakeInterceptor handshakeInterceptor) {
        this.collaborationHandler = collaborationHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(collaborationHandler, "/ws/sessions/{sessionId}")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
