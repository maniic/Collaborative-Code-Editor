package com.collabeditor.redis;

import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.redis.service.SessionCoordinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis-backed tests for SessionCoordinationService proving lock exclusivity,
 * revision initialization, revision overwrite, and TTL-backed active session bookkeeping.
 */
@Testcontainers
class SessionCoordinationServiceTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private SessionCoordinationService coordinationService;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        RedisCollaborationProperties props = new RedisCollaborationProperties("collab", 5000, 1800);
        coordinationService = new SessionCoordinationService(redisTemplate, props);

        // Flush all keys between tests
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("lock exclusivity - only one thread executes the critical section at a time")
    void lockExclusivity() throws Exception {
        UUID sessionId = UUID.randomUUID();
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        int threads = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    coordinationService.withSessionLock(sessionId, () -> {
                        int current = concurrentExecutions.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, current));
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        concurrentExecutions.decrementAndGet();
                        return null;
                    });
                } catch (Exception e) {
                    // Lock acquisition may fail for some threads - that's acceptable
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("revision initialization from a durable value sets the key only when absent")
    void revisionInitializationFromDurableValue() {
        UUID sessionId = UUID.randomUUID();

        // Initialize from durable state
        coordinationService.initializeRevisionIfAbsent(sessionId, 42);
        OptionalLong rev = coordinationService.getRevision(sessionId);
        assertThat(rev).isPresent();
        assertThat(rev.getAsLong()).isEqualTo(42);

        // Second initialization should not overwrite
        coordinationService.initializeRevisionIfAbsent(sessionId, 99);
        rev = coordinationService.getRevision(sessionId);
        assertThat(rev).isPresent();
        assertThat(rev.getAsLong()).isEqualTo(42);
    }

    @Test
    @DisplayName("setRevision overwrites to a newer value")
    void revisionOverwriteToNewerValue() {
        UUID sessionId = UUID.randomUUID();

        coordinationService.initializeRevisionIfAbsent(sessionId, 10);
        coordinationService.setRevision(sessionId, 20);

        OptionalLong rev = coordinationService.getRevision(sessionId);
        assertThat(rev).isPresent();
        assertThat(rev.getAsLong()).isEqualTo(20);
    }

    @Test
    @DisplayName("getRevision returns empty when no revision is stored")
    void getRevisionReturnsEmptyWhenAbsent() {
        UUID sessionId = UUID.randomUUID();
        OptionalLong rev = coordinationService.getRevision(sessionId);
        assertThat(rev).isEmpty();
    }

    @Test
    @DisplayName("TTL-backed active session bookkeeping with markSessionActive and markSessionInactive")
    void activeSessionBookkeeping() {
        UUID sessionId = UUID.randomUUID();

        // Mark session active - key should exist with a TTL
        coordinationService.markSessionActive(sessionId);
        String key = "collab:session:" + sessionId + ":active";
        assertThat(redisTemplate.hasKey(key)).isTrue();
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0);
        assertThat(ttl).isLessThanOrEqualTo(1800);

        // Mark session inactive - key should be removed
        coordinationService.markSessionInactive(sessionId);
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }
}
