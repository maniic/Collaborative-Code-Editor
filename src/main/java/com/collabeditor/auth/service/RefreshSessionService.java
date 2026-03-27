package com.collabeditor.auth.service;

import com.collabeditor.auth.persistence.RefreshSessionRepository;
import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import com.collabeditor.auth.security.SecurityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshSessionService {

    private final RefreshSessionRepository refreshSessionRepository;
    private final SecurityProperties securityProperties;
    private final SecureRandom secureRandom = new SecureRandom();

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
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);

        Instant expiresAt = Instant.now().plus(securityProperties.refreshTokenTtl());

        RefreshSessionEntity session = new RefreshSessionEntity(
                UUID.randomUUID(), userId, tokenHash, deviceId, userAgent, expiresAt);

        RefreshSessionEntity saved = refreshSessionRepository.save(session);
        return new RefreshResult(rawToken, saved);
    }

    /**
     * Rotate a refresh session: validate the raw token, revoke the old session,
     * create a replacement with the same deviceId.
     */
    @Transactional
    public RefreshResult rotate(String rawToken) {
        String tokenHash = hashToken(rawToken);

        RefreshSessionEntity oldSession = refreshSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not found"));

        if (oldSession.getRevokedAt() != null) {
            // Token reuse detected -- revoke all sessions for this device
            if (oldSession.getDeviceId() != null) {
                refreshSessionRepository.revokeByUserIdAndDeviceId(
                        oldSession.getUserId(), oldSession.getDeviceId());
            }
            throw new RefreshTokenReusedException("Refresh token has already been used");
        }

        if (oldSession.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        // Create replacement session with the same device ID
        String newRawToken = generateRawToken();
        String newTokenHash = hashToken(newRawToken);
        Instant expiresAt = Instant.now().plus(securityProperties.refreshTokenTtl());

        RefreshSessionEntity newSession = new RefreshSessionEntity(
                UUID.randomUUID(), oldSession.getUserId(), newTokenHash,
                oldSession.getDeviceId(), oldSession.getUserAgent(), expiresAt);

        RefreshSessionEntity saved = refreshSessionRepository.save(newSession);

        // Mark the old session as replaced
        oldSession.setReplacedBySessionId(saved.getId());
        oldSession.setRevokedAt(Instant.now());
        refreshSessionRepository.save(oldSession);

        return new RefreshResult(newRawToken, saved);
    }

    /**
     * Hash a raw token using SHA-256, returning the hex digest.
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Thrown when a refresh token is not found or has expired.
     */
    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a refresh token has already been used (reuse detection).
     */
    public static class RefreshTokenReusedException extends RuntimeException {
        public RefreshTokenReusedException(String message) {
            super(message);
        }
    }
}
