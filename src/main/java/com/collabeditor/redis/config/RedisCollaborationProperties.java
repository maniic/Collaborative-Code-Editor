package com.collabeditor.redis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for Redis-backed collaboration coordination.
 *
 * <p>Prefix: {@code app.collaboration.redis}
 */
@ConfigurationProperties(prefix = "app.collaboration.redis")
public record RedisCollaborationProperties(
        String keyPrefix,
        long lockTtlMs,
        long sessionTtlSeconds
) {
    public RedisCollaborationProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "collab";
        }
        if (lockTtlMs <= 0) {
            lockTtlMs = 5000;
        }
        if (sessionTtlSeconds <= 0) {
            sessionTtlSeconds = 1800;
        }
    }
}
