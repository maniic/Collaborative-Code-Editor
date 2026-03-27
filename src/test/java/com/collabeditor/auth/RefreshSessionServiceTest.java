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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
                "test-jwt-secret-must-be-at-least-32-bytes-long!!",
                "collaborative-code-editor",
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

        when(refreshSessionRepository.findByTokenHashForUpdate(oldHash))
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

    @Test
    void shouldRejectRefreshTokenReuse() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String reusedRawToken = "reused-token";
        String reusedHash = sha256Hex(reusedRawToken);

        RefreshSessionEntity revokedSession = new RefreshSessionEntity(
                UUID.randomUUID(), userId, reusedHash, deviceId, "TestBrowser",
                Instant.now().plusSeconds(86400));
        // Mark as already revoked (previously rotated)
        revokedSession.setRevokedAt(Instant.now().minusSeconds(3600));
        revokedSession.setReplacedBySessionId(UUID.randomUUID());

        when(refreshSessionRepository.findByTokenHashForUpdate(reusedHash))
                .thenReturn(Optional.of(revokedSession));

        assertThatThrownBy(() -> refreshSessionService.rotate(reusedRawToken))
                .isInstanceOf(RefreshSessionService.RefreshTokenReusedException.class);

        // Should also revoke all sessions for this device
        verify(refreshSessionRepository).revokeByUserIdAndDeviceId(userId, deviceId);
    }

    @Test
    void shouldAllowOnlyOneConcurrentSingleUseRefreshRotationRace() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID oldSessionId = UUID.randomUUID();
        String rawToken = "single-use-race-token";
        String tokenHash = sha256Hex(rawToken);

        RefreshSessionEntity oldSession = new RefreshSessionEntity(
                oldSessionId, userId, tokenHash, deviceId, "TestBrowser",
                Instant.now().plusSeconds(86400));

        ReentrantLock rotationLock = new ReentrantLock();
        List<RefreshSessionEntity> createdSessions = new ArrayList<>();

        when(refreshSessionRepository.findByTokenHashForUpdate(tokenHash)).thenAnswer(invocation -> {
            rotationLock.lock();
            return Optional.of(oldSession);
        });
        when(refreshSessionRepository.save(any(RefreshSessionEntity.class))).thenAnswer(invocation -> {
            RefreshSessionEntity session = invocation.getArgument(0);
            if (!session.getId().equals(oldSessionId)) {
                createdSessions.add(session);
                return session;
            }
            try {
                return session;
            } finally {
                rotationLock.unlock();
            }
        });
        when(refreshSessionRepository.revokeByUserIdAndDeviceId(userId, deviceId)).thenAnswer(invocation -> {
            rotationLock.unlock();
            return 1;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<RefreshResult> first = executor.submit(() -> rotateAfterBarrier(barrier, rawToken));
            Future<RefreshResult> second = executor.submit(() -> rotateAfterBarrier(barrier, rawToken));

            int successCount = 0;
            int reusedCount = 0;
            for (Future<RefreshResult> future : List.of(first, second)) {
                try {
                    RefreshResult result = future.get(5, TimeUnit.SECONDS);
                    assertThat(result.session().getDeviceId()).isEqualTo(deviceId);
                    successCount++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(RefreshSessionService.RefreshTokenReusedException.class);
                    reusedCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(reusedCount).isEqualTo(1);
            assertThat(createdSessions).hasSize(1);
            assertThat(oldSession.getRevokedAt()).isNotNull();
            assertThat(oldSession.getReplacedBySessionId()).isEqualTo(createdSessions.get(0).getId());
            verify(refreshSessionRepository).revokeByUserIdAndDeviceId(userId, deviceId);
        } finally {
            executor.shutdownNow();
        }
    }

    private RefreshResult rotateAfterBarrier(CyclicBarrier barrier, String rawToken) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return refreshSessionService.rotate(rawToken);
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
