package com.collabeditor.redis.service;

import com.collabeditor.redis.config.RedisCollaborationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis-backed per-session coordination service for apply serialization,
 * revision mirroring, and active-session bookkeeping.
 *
 * <p>Key patterns:
 * <ul>
 *   <li>{@code collab:session:{sessionId}:lock} - distributed lock for critical sections</li>
 *   <li>{@code collab:session:{sessionId}:revision} - canonical revision mirror</li>
 *   <li>{@code collab:session:{sessionId}:active} - TTL-backed active session marker</li>
 * </ul>
 */
@Service
public class SessionCoordinationService {

    private static final long LOCK_RETRY_DELAY_MS = 50;
    private static final int MAX_LOCK_RETRIES = 100;

    private final StringRedisTemplate redisTemplate;
    private final RedisCollaborationProperties properties;

    public SessionCoordinationService(StringRedisTemplate redisTemplate,
                                       RedisCollaborationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Executes the given critical section under a distributed per-session lock.
     *
     * <p>Uses {@code SET NX PX} semantics with a random token. The lock is released
     * only when the stored token matches the current owner.
     *
     * @param sessionId       the collaboration session identity
     * @param criticalSection the work to execute under the lock
     * @param <T>             the return type of the critical section
     * @return the result of the critical section
     * @throws IllegalStateException if the lock cannot be acquired
     */
    public <T> T withSessionLock(UUID sessionId, Supplier<T> criticalSection) {
        String lockKey = lockKey(sessionId);
        String token = UUID.randomUUID().toString();

        try {
            boolean acquired = false;
            for (int i = 0; i < MAX_LOCK_RETRIES; i++) {
                Boolean result = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, token, properties.lockTtlMs(), TimeUnit.MILLISECONDS);
                if (Boolean.TRUE.equals(result)) {
                    acquired = true;
                    break;
                }
                try {
                    Thread.sleep(LOCK_RETRY_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Lock acquisition interrupted for session " + sessionId, e);
                }
            }

            if (!acquired) {
                throw new IllegalStateException("Could not acquire lock for session " + sessionId);
            }

            return criticalSection.get();
        } finally {
            // Release lock only if we still own it (token matches)
            String storedToken = redisTemplate.opsForValue().get(lockKey);
            if (token.equals(storedToken)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    /**
     * Initializes the Redis revision mirror from a durable PostgreSQL value,
     * but only if no revision is currently stored.
     *
     * @param sessionId       the collaboration session identity
     * @param durableRevision the revision from the durable source of truth
     */
    public void initializeRevisionIfAbsent(UUID sessionId, long durableRevision) {
        String revisionKey = revisionKey(sessionId);
        redisTemplate.opsForValue().setIfAbsent(revisionKey, String.valueOf(durableRevision));
    }

    /**
     * Overwrites the Redis revision mirror to the given value.
     *
     * @param sessionId the collaboration session identity
     * @param revision  the new canonical revision number
     */
    public void setRevision(UUID sessionId, long revision) {
        redisTemplate.opsForValue().set(revisionKey(sessionId), String.valueOf(revision));
    }

    /**
     * Returns the current Redis revision mirror for the given session,
     * or empty if no revision is stored.
     *
     * @param sessionId the collaboration session identity
     * @return the mirrored revision, or empty
     */
    public OptionalLong getRevision(UUID sessionId) {
        String value = redisTemplate.opsForValue().get(revisionKey(sessionId));
        if (value == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(value));
    }

    /**
     * Marks the given session as active with a TTL-backed key.
     *
     * @param sessionId the collaboration session identity
     */
    public void markSessionActive(UUID sessionId) {
        redisTemplate.opsForValue().set(
                activeKey(sessionId),
                "active",
                properties.sessionTtlSeconds(),
                TimeUnit.SECONDS
        );
    }

    /**
     * Marks the given session as inactive by removing the active key.
     *
     * @param sessionId the collaboration session identity
     */
    public void markSessionInactive(UUID sessionId) {
        redisTemplate.delete(activeKey(sessionId));
    }

    private String lockKey(UUID sessionId) {
        return properties.keyPrefix() + ":session:" + sessionId + ":lock";
    }

    private String revisionKey(UUID sessionId) {
        return properties.keyPrefix() + ":session:" + sessionId + ":revision";
    }

    private String activeKey(UUID sessionId) {
        return properties.keyPrefix() + ":session:" + sessionId + ":active";
    }
}
