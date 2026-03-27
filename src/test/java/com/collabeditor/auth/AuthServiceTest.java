package com.collabeditor.auth;

import com.collabeditor.auth.api.dto.AuthTokenResponse;
import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.auth.persistence.entity.RefreshSessionEntity;
import com.collabeditor.auth.persistence.entity.UserEntity;
import com.collabeditor.auth.service.AuthService;
import com.collabeditor.auth.service.JwtTokenService;
import com.collabeditor.auth.service.RefreshSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private RefreshSessionService refreshSessionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenService, refreshSessionService);
    }

    @Test
    void shouldRejectDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("test@example.com", "password123"))
                .isInstanceOf(AuthService.DuplicateEmailException.class);
    }

    @Test
    void shouldIssueAccessTokenAndRefreshCookie() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, "test@example.com", "hashed");
        RefreshSessionEntity session = new RefreshSessionEntity(
                UUID.randomUUID(), userId, "hash", UUID.randomUUID(), "agent",
                Instant.now().plusSeconds(86400));
        RefreshSessionService.RefreshResult refreshResult =
                new RefreshSessionService.RefreshResult("raw-refresh", session);

        when(userRepository.findByEmailIgnoreCase("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(refreshSessionService.createSession(eq(userId), any(), anyString())).thenReturn(refreshResult);
        when(jwtTokenService.createAccessToken(userId, "test@example.com")).thenReturn("jwt-token");
        when(jwtTokenService.getAccessTokenTtlSeconds()).thenReturn(900L);

        AuthService.AuthResult result = authService.login("test@example.com", "password123", "TestAgent");

        assertThat(result.tokenResponse().accessToken()).isEqualTo("jwt-token");
        assertThat(result.tokenResponse().expiresInSeconds()).isEqualTo(900L);
        assertThat(result.tokenResponse().userId()).isEqualTo(userId);
        assertThat(result.rawRefreshToken()).isEqualTo("raw-refresh");
    }

    @Test
    void shouldRejectInvalidPassword() {
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(userId, "test@example.com", "hashed");

        when(userRepository.findByEmailIgnoreCase("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("test@example.com", "wrong", "agent"))
                .isInstanceOf(AuthService.BadCredentialsException.class);
    }

    @Test
    void shouldRotateRefreshTokenOnRefresh() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        RefreshSessionEntity newSession = new RefreshSessionEntity(
                UUID.randomUUID(), userId, "new-hash", deviceId, "agent",
                Instant.now().plusSeconds(86400));
        RefreshSessionService.RefreshResult refreshResult =
                new RefreshSessionService.RefreshResult("new-raw", newSession);

        UserEntity user = new UserEntity(userId, "test@example.com", "hashed");

        when(refreshSessionService.rotate("old-raw-token")).thenReturn(refreshResult);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtTokenService.createAccessToken(userId, "test@example.com")).thenReturn("new-jwt");
        when(jwtTokenService.getAccessTokenTtlSeconds()).thenReturn(900L);

        AuthService.AuthResult result = authService.refresh("old-raw-token");

        assertThat(result.tokenResponse().accessToken()).isEqualTo("new-jwt");
        assertThat(result.rawRefreshToken()).isEqualTo("new-raw");
        verify(refreshSessionService).rotate("old-raw-token");
    }

    @Test
    void shouldRejectRefreshTokenReuse() {
        when(refreshSessionService.rotate("reused-token"))
                .thenThrow(new RefreshSessionService.RefreshTokenReusedException("Token reuse detected"));

        assertThatThrownBy(() -> authService.refresh("reused-token"))
                .isInstanceOf(RefreshSessionService.RefreshTokenReusedException.class);
    }
}
