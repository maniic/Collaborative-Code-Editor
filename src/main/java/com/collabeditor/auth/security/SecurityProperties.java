package com.collabeditor.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        String jwtSecret,
        String jwtIssuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String refreshCookieName,
        String refreshCookiePath,
        String refreshCookieSameSite
) {
    private static final String REQUIRED_JWT_ISSUER = "collaborative-code-editor";

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalArgumentException("app.security.jwt-secret must be configured");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalArgumentException("app.security.jwt-secret must be at least 32 characters");
        }
        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            jwtIssuer = REQUIRED_JWT_ISSUER;
        }
        if (!REQUIRED_JWT_ISSUER.equals(jwtIssuer)) {
            throw new IllegalArgumentException("app.security.jwt-issuer must be " + REQUIRED_JWT_ISSUER);
        }
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(30);
        if (refreshCookieName == null) refreshCookieName = "ccd_refresh_token";
        if (refreshCookiePath == null) refreshCookiePath = "/api/auth";
        if (refreshCookieSameSite == null) refreshCookieSameSite = "Strict";
    }
}
