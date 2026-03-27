package com.collabeditor.auth;

import com.collabeditor.auth.persistence.RefreshSessionRepository;
import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.RefreshSessionService;
import com.collabeditor.auth.service.RefreshSessionService.RefreshResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshSessionServiceTest {

    @Mock
    private RefreshSessionRepository refreshSessionRepository;

    private RefreshSessionService refreshSessionService;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties(
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "ccd_refresh_token",
                "/api/auth",
                "Strict"
        );
        refreshSessionService = new RefreshSessionService(refreshSessionRepository, props);
    }

    @Test
    void shouldCreateSessionWithHashedToken() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        when(refreshSessionRepository.save(any(RefreshSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefreshResult result = refreshSessionService.createSession(userId, deviceId, "TestBrowser");

        assertThat(result.rawToken()).isNotBlank();
        // Raw token should be Base64URL (no + or /)
        assertThat(result.rawToken()).doesNotContain("+", "/");

        ArgumentCaptor<RefreshSessionEntity> captor = ArgumentCaptor.forClass(RefreshSessionEntity.class);
        verify(refreshSessionRepository).save(captor.capture());

        RefreshSessionEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getDeviceId()).isEqualTo(deviceId);
        assertThat(saved.getTokenHash()).isNotBlank();
        // token_hash should be the SHA-256 hex digest of the raw token
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(result.rawToken()));
    }

    @Test
    void shouldRotateRefreshTokenPreservingDeviceId() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID oldSessionId = UUID.randomUUID();
        String oldRawToken = "old-raw-token-for-testing";
        String oldHash = sha256Hex(oldRawToken);

        RefreshSessionEntity oldSession = new RefreshSessionEntity(
                oldSessionId, userId, oldHash, deviceId, "TestBrowser",
                Instant.now().plusSeconds(86400));

        when(refreshSessionRepository.findByTokenHash(oldHash))
                .thenReturn(Optional.of(oldSession));
        when(refreshSessionRepository.save(any(RefreshSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefreshResult result = refreshSessionService.rotate(oldRawToken);

        assertThat(result.rawToken()).isNotBlank();
        assertThat(result.rawToken()).isNotEqualTo(oldRawToken);

        // Old session should be marked with replacedBySessionId
        assertThat(oldSession.getReplacedBySessionId()).isNotNull();
        assertThat(oldSession.getRevokedAt()).isNotNull();

        // New session should preserve the device ID
        assertThat(result.session().getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    void shouldHashTokenUsingSha256() {
        String rawToken = "test-token-value";
        String hash = refreshSessionService.hashToken(rawToken);

        assertThat(hash).isEqualTo(sha256Hex(rawToken));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
