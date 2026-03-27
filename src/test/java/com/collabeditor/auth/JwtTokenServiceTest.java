package com.collabeditor.auth;

import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.JwtTokenService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    private static final String TEST_SECRET = "test-jwt-secret-must-be-at-least-32-bytes-long!!";

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties(
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "ccd_refresh_token",
                "/api/auth",
                "Strict"
        );
        jwtTokenService = new JwtTokenService(TEST_SECRET, props);
    }

    @Test
    void shouldCreateAccessTokenWithCorrectClaims() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";

        String token = jwtTokenService.createAccessToken(userId, email);

        assertThat(token).isNotBlank();

        Claims claims = jwtTokenService.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("collaborative-code-editor");
        assertThat(claims.get("email", String.class)).isEqualTo(email);
    }

    @Test
    void shouldSetExpirationToFifteenMinutes() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.createAccessToken(userId, "test@example.com");

        Claims claims = jwtTokenService.parseToken(token);
        assertThat(claims).isNotNull();

        long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        // Allow small tolerance (within 1 second of 15 min)
        assertThat(ttlMillis).isBetween(
                Duration.ofMinutes(15).toMillis() - 1000L,
                Duration.ofMinutes(15).toMillis() + 1000L
        );
    }

    @Test
    void shouldReturnNullForInvalidToken() {
        Claims claims = jwtTokenService.parseToken("this.is.not.a.valid.token");
        assertThat(claims).isNull();
    }

    @Test
    void shouldReturnNullForTokenSignedWithDifferentSecret() {
        SecurityProperties props = new SecurityProperties(
                Duration.ofMinutes(15), Duration.ofDays(30),
                "ccd_refresh_token", "/api/auth", "Strict"
        );
        JwtTokenService otherService = new JwtTokenService(
                "different-secret-that-is-at-least-32-bytes-long!!", props);

        String token = otherService.createAccessToken(UUID.randomUUID(), "test@example.com");

        // Parsing with the original service should fail
        Claims claims = jwtTokenService.parseToken(token);
        assertThat(claims).isNull();
    }

    @Test
    void shouldReportAccessTokenTtlInSeconds() {
        assertThat(jwtTokenService.getAccessTokenTtlSeconds()).isEqualTo(900L);
    }
}
