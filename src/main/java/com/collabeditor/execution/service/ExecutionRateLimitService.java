package com.collabeditor.execution.service;

import com.collabeditor.execution.config.ExecutionProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Distributed five-second execution cooldown backed by Redis.
 */
@Service
public class ExecutionRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final ExecutionProperties properties;

    public ExecutionRateLimitService(StringRedisTemplate redisTemplate,
                                     ExecutionProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public boolean tryAcquire(UUID userId) {
        validateConfiguredCooldown();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                cooldownKey(userId),
                "cooldown",
                Duration.ofSeconds(5)
        );
        return Boolean.TRUE.equals(acquired);
    }

    public void release(UUID userId) {
        redisTemplate.delete(cooldownKey(userId));
    }

    String cooldownKey(UUID userId) {
        return "collab:execution:user:" + userId + ":cooldown";
    }

    private void validateConfiguredCooldown() {
        if (properties.getCooldownSeconds() != 5) {
            throw new IllegalStateException("app.execution.cooldown-seconds must remain 5");
        }
    }
}
