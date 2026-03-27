package com.collabeditor.auth.service;

import com.collabeditor.auth.security.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final SecurityProperties securityProperties;

    public JwtTokenService(@Value("${APP_JWT_SECRET:default-dev-secret-that-is-at-least-32-bytes-long}") String secret,
                           SecurityProperties securityProperties) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.securityProperties = securityProperties;
    }

    /**
     * Mint a new access JWT for the given user.
     */
    public String createAccessToken(UUID userId, String email) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Parse and validate a JWT, returning its claims.
     * Returns null if the token is invalid or expired.
     */
    public Claims parseToken(String token) {
        // TODO: implement
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public long getAccessTokenTtlSeconds() {
        return securityProperties.accessTokenTtl().toSeconds();
    }
}
