package com.collabeditor.auth;

import com.collabeditor.auth.security.SecurityProperties;
import com.collabeditor.auth.service.JwtTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String TEST_SECRET = "test-jwt-secret-must-be-at-least-32-bytes-long!!";
    private static final String TEST_ISSUER = "collaborative-code-editor";

    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties(
                TEST_SECRET,
                TEST_ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "ccd_refresh_token",
                "/api/auth",
                "Strict"
        );
        jwtTokenService = new JwtTokenService(props);
    }

    @Test
    void shouldCreateAccessTokenWithCorrectClaims() {
        UUID userId = UUID.randomUUID();
        String email = "test@example.com";

        String token = jwtTokenService.createAccessToken(userId, email);

        assertThat(token).isNotBlank();

        Claims claims = jwtTokenService.parseToken(token).orElseThrow();
        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo(TEST_ISSUER);
        assertThat(claims.get("email", String.class)).isEqualTo(email);
    }

    @Test
    void shouldSetExpirationToFifteenMinutes() {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenService.createAccessToken(userId, "test@example.com");

        Claims claims = jwtTokenService.parseToken(token).orElseThrow();

        long ttlMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        // Allow small tolerance (within 1 second of 15 min)
        assertThat(ttlMillis).isBetween(
                Duration.ofMinutes(15).toMillis() - 1000L,
                Duration.ofMinutes(15).toMillis() + 1000L
        );
    }

    @Test
    void shouldReturnNullForInvalidToken() {
        assertThat(jwtTokenService.parseToken("this.is.not.a.valid.token")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForTokenSignedWithDifferentSecret() {
        SecurityProperties props = new SecurityProperties(
                "different-secret-that-is-at-least-32-bytes-long!!",
                TEST_ISSUER,
                Duration.ofMinutes(15), Duration.ofDays(30),
                "ccd_refresh_token", "/api/auth", "Strict"
        );
        JwtTokenService otherService = new JwtTokenService(props);

        String token = otherService.createAccessToken(UUID.randomUUID(), "test@example.com");

        // Parsing with the original service should fail
        assertThat(jwtTokenService.parseToken(token)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForWrongIssuerToken() {
        String token = createToken(UUID.randomUUID().toString(), "someone-else", Instant.now().plusSeconds(60));

        assertThat(jwtTokenService.parseToken(token)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForMalformedSubjectToken() {
        String token = createToken("not-a-uuid", TEST_ISSUER, Instant.now().plusSeconds(60));

        assertThat(jwtTokenService.parseToken(token)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForExpiredToken() {
        String token = createToken(UUID.randomUUID().toString(), TEST_ISSUER, Instant.now().minusSeconds(60));

        assertThat(jwtTokenService.parseToken(token)).isEmpty();
    }

    @Test
    void shouldRejectBlankSecretConfiguration() {
        assertThatThrownBy(() -> new SecurityProperties(
                " ",
                TEST_ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "ccd_refresh_token",
                "/api/auth",
                "Strict"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt-secret");
    }

    @Test
    void shouldRejectShortSecretConfiguration() {
        assertThatThrownBy(() -> new SecurityProperties(
                "short-secret",
                TEST_ISSUER,
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "ccd_refresh_token",
                "/api/auth",
                "Strict"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void shouldReportAccessTokenTtlInSeconds() {
        assertThat(jwtTokenService.getAccessTokenTtlSeconds()).isEqualTo(900L);
    }

    private String createToken(String subject, String issuer, Instant expiration) {
        SecretKey signingKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Instant issuedAt = expiration.minusSeconds(60);

        return Jwts.builder()
                .issuer(issuer)
                .subject(subject)
                .claim("email", "test@example.com")
                .issuedAt(java.util.Date.from(issuedAt))
                .expiration(java.util.Date.from(expiration))
                .signWith(signingKey)
                .compact();
    }
}
