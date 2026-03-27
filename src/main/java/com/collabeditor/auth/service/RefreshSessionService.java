package com.collabeditor.auth.service;

import com.collabeditor.auth.persistence.RefreshSessionRepository;
import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import com.collabeditor.auth.security.SecurityProperties;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RefreshSessionService {

    private final RefreshSessionRepository refreshSessionRepository;
    private final SecurityProperties securityProperties;

    public RefreshSessionService(RefreshSessionRepository refreshSessionRepository,
                                  SecurityProperties securityProperties) {
        this.refreshSessionRepository = refreshSessionRepository;
        this.securityProperties = securityProperties;
    }

    /**
     * Result of creating or rotating a refresh session.
     */
    public record RefreshResult(String rawToken, RefreshSessionEntity session) {}

    /**
     * Create a new refresh session for the given user and device.
     */
    public RefreshResult createSession(UUID userId, UUID deviceId, String userAgent) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Rotate a refresh session: validate the raw token, revoke the old session,
     * create a replacement with the same deviceId.
     */
    public RefreshResult rotate(String rawToken) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Hash a raw token using SHA-256.
     */
    public String hashToken(String rawToken) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
