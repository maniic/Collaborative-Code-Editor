package com.collabeditor.redis.service;

import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.redis.model.CanonicalCollaborationEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Canonical Redis pub/sub relay for collaboration events.
 *
 * <p>Publishes every canonical event to the deterministic channel
 * {@code collab:session:{sessionId}:events} and allows callers to
 * register a callback-based subscriber for a specific session's channel.
 */
@Service
public class CollaborationRelayService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationRelayService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper redisObjectMapper;
    private final RedisCollaborationProperties properties;

    public CollaborationRelayService(StringRedisTemplate redisTemplate,
                                      RedisMessageListenerContainer listenerContainer,
                                      ObjectMapper redisObjectMapper,
                                      RedisCollaborationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.redisObjectMapper = redisObjectMapper;
        this.properties = properties;
    }

    /**
     * Publishes a canonical collaboration event to the session-specific Redis channel.
     *
     * @param event the canonical event to publish
     */
    public void publish(CanonicalCollaborationEvent event) {
        String channel = channelFor(event.sessionId());
        try {
            String json = redisObjectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(channel, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize canonical event for session {}: {}",
                    event.sessionId(), e.getMessage(), e);
            throw new IllegalStateException("Cannot serialize canonical collaboration event", e);
        }
    }

    /**
     * Registers a callback-based subscriber for the given session's canonical event channel.
     *
     * <p>The callback receives deserialized {@link CanonicalCollaborationEvent} instances.
     * The returned {@link Subscription} can be used to unsubscribe when the session
     * listener is no longer needed.
     *
     * @param sessionId the collaboration session identity
     * @param callback  the consumer to invoke for each received event
     * @return a subscription handle for unsubscribing
     */
    public Subscription subscribe(UUID sessionId, Consumer<CanonicalCollaborationEvent> callback) {
        String channel = channelFor(sessionId);
        ChannelTopic topic = new ChannelTopic(channel);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody());
                CanonicalCollaborationEvent event =
                        redisObjectMapper.readValue(json, CanonicalCollaborationEvent.class);
                callback.accept(event);
            } catch (Exception e) {
                log.error("Failed to deserialize canonical event on channel {}: {}",
                        channel, e.getMessage(), e);
            }
        };

        listenerContainer.addMessageListener(listener, topic);

        return () -> listenerContainer.removeMessageListener(listener, topic);
    }

    /**
     * Returns the deterministic Redis channel name for the given session.
     *
     * @param sessionId the collaboration session identity
     * @return the channel name
     */
    public String channelFor(UUID sessionId) {
        return properties.keyPrefix() + ":session:" + sessionId + ":events";
    }

    /**
     * Handle for unsubscribing from a session's canonical event channel.
     */
    @FunctionalInterface
    public interface Subscription {
        void unsubscribe();
    }
}
