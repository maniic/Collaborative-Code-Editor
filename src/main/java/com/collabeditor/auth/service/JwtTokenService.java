package com.collabeditor.auth.service;

import com.collabeditor.auth.security.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final SecurityProperties securityProperties;

    public JwtTokenService(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
        this.signingKey = Keys.hmacShaKeyFor(
                securityProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mint a new access JWT for the given user.
     */
    public String createAccessToken(UUID userId, String email) {
        Instant now = Instant.now();
        Instant expiry = now.plus(securityProperties.accessTokenTtl());

        return Jwts.builder()
                .issuer(securityProperties.jwtIssuer())
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate a JWT, returning its claims.
     * Returns an empty result if the token is invalid, expired, or carries the wrong claims.
     */
    public Optional<Claims> parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!securityProperties.jwtIssuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            UUID.fromString(claims.getSubject());
            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Extract userId (from subject) and email (from claim) from validated claims.
     * Returns empty if subject is not a valid UUID or email is missing.
     */
    public Optional<TokenIdentity> extractIdentity(Claims claims) {
        try {
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            if (email == null || email.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TokenIdentity(userId, email));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Identity extracted from a validated JWT.
     */
    public record TokenIdentity(UUID userId, String email) {}

    public long getAccessTokenTtlSeconds() {
        return securityProperties.accessTokenTtl().toSeconds();
    }
}
