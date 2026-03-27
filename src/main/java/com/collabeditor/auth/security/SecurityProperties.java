package com.collabeditor.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String refreshCookieName,
        String refreshCookiePath,
        String refreshCookieSameSite
) {
    public SecurityProperties {
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(30);
        if (refreshCookieName == null) refreshCookieName = "ccd_refresh_token";
        if (refreshCookiePath == null) refreshCookiePath = "/api/auth";
        if (refreshCookieSameSite == null) refreshCookieSameSite = "Strict";
    }
}
