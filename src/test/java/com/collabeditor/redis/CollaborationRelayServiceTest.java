package com.collabeditor.redis;

import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.redis.model.CanonicalCollaborationEvent;
import com.collabeditor.redis.model.CanonicalEventType;
import com.collabeditor.redis.service.CollaborationRelayService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis-backed tests for CollaborationRelayService proving that canonical
 * collaboration events publish, subscribe, and round-trip correctly through
 * session-specific Redis channels.
 */
@Testcontainers
class CollaborationRelayServiceTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private CollaborationRelayService relayService;
    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);

        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCollaborationProperties props = new RedisCollaborationProperties("collab", 5000, 1800);
        relayService = new CollaborationRelayService(redisTemplate, listenerContainer, objectMapper, props);

        // Flush all keys between tests
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("published OPERATION_APPLIED event is observed by a subscriber")
    void publishedOperationAppliedEventIsObservedBySubscriber() throws Exception {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        CopyOnWriteArrayList<CanonicalCollaborationEvent> received = new CopyOnWriteArrayList<>();

        relayService.subscribe(sessionId, event -> {
            received.add(event);
            latch.countDown();
        });

        // Small delay to let the subscription register
        Thread.sleep(200);

        CanonicalCollaborationEvent event = new CanonicalCollaborationEvent(
                sessionId,
                CanonicalEventType.OPERATION_APPLIED,
                5L,
                UUID.randomUUID(),
                "{\"type\":\"insert\",\"position\":0,\"text\":\"hello\"}",
                Instant.now()
        );

        relayService.publish(event);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);

        CanonicalCollaborationEvent receivedEvent = received.get(0);
        assertThat(receivedEvent.sessionId()).isEqualTo(event.sessionId());
        assertThat(receivedEvent.eventType()).isEqualTo(CanonicalEventType.OPERATION_APPLIED);
        assertThat(receivedEvent.revision()).isEqualTo(5L);
        assertThat(receivedEvent.userId()).isEqualTo(event.userId());
        assertThat(receivedEvent.payloadJson()).isEqualTo(event.payloadJson());
    }

    @Test
    @DisplayName("participant and presence event types round-trip without losing payloadJson")
    void participantAndPresenceEventTypesRoundTripWithoutLosingPayloadJson() throws Exception {
        UUID sessionId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(3);
        CopyOnWriteArrayList<CanonicalCollaborationEvent> received = new CopyOnWriteArrayList<>();

        relayService.subscribe(sessionId, event -> {
            received.add(event);
            latch.countDown();
        });

        Thread.sleep(200);

        UUID userId = UUID.randomUUID();
        String joinPayload = "{\"email\":\"user@example.com\",\"displayName\":\"TestUser\"}";
        String presencePayload = "{\"cursor\":{\"line\":10,\"column\":5}}";
        String leavePayload = "{\"reason\":\"disconnect\"}";

        relayService.publish(new CanonicalCollaborationEvent(
                sessionId, CanonicalEventType.PARTICIPANT_JOINED, 0L, userId, joinPayload, Instant.now()));
        relayService.publish(new CanonicalCollaborationEvent(
                sessionId, CanonicalEventType.PRESENCE_UPDATED, 0L, userId, presencePayload, Instant.now()));
        relayService.publish(new CanonicalCollaborationEvent(
                sessionId, CanonicalEventType.PARTICIPANT_LEFT, 0L, userId, leavePayload, Instant.now()));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(3);

        assertThat(received.get(0).eventType()).isEqualTo(CanonicalEventType.PARTICIPANT_JOINED);
        assertThat(received.get(0).payloadJson()).isEqualTo(joinPayload);

        assertThat(received.get(1).eventType()).isEqualTo(CanonicalEventType.PRESENCE_UPDATED);
        assertThat(received.get(1).payloadJson()).isEqualTo(presencePayload);

        assertThat(received.get(2).eventType()).isEqualTo(CanonicalEventType.PARTICIPANT_LEFT);
        assertThat(received.get(2).payloadJson()).isEqualTo(leavePayload);
    }

    @Test
    @DisplayName("session-specific channel naming prevents cross-session event delivery")
    void sessionSpecificChannelNamingPreventsCrossSessionDelivery() throws Exception {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);
        CopyOnWriteArrayList<CanonicalCollaborationEvent> receivedByA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<CanonicalCollaborationEvent> receivedByB = new CopyOnWriteArrayList<>();

        relayService.subscribe(sessionA, event -> {
            receivedByA.add(event);
            latchA.countDown();
        });
        relayService.subscribe(sessionB, event -> {
            receivedByB.add(event);
            latchB.countDown();
        });

        Thread.sleep(200);

        // Publish only to session A
        relayService.publish(new CanonicalCollaborationEvent(
                sessionA, CanonicalEventType.OPERATION_APPLIED, 1L,
                UUID.randomUUID(), "{\"op\":\"a\"}", Instant.now()));

        assertThat(latchA.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedByA).hasSize(1);

        // Session B should not receive session A's event
        // Wait briefly to confirm no delivery
        assertThat(latchB.await(500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(receivedByB).isEmpty();
    }
}
