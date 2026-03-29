package com.collabeditor.execution.service;

import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.websocket.protocol.ExecutionUpdatedPayload;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Redis-backed relay for room-scoped execution lifecycle updates.
 */
@Service
public class ExecutionEventRelayService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEventRelayService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper redisObjectMapper;
    private final RedisCollaborationProperties properties;

    public ExecutionEventRelayService(StringRedisTemplate redisTemplate,
                                      RedisMessageListenerContainer listenerContainer,
                                      ObjectMapper redisObjectMapper,
                                      RedisCollaborationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.redisObjectMapper = redisObjectMapper;
        this.properties = properties;
    }

    public void publish(UUID sessionId, ExecutionUpdatedPayload payload) {
        String channel = channelFor(sessionId);
        try {
            redisTemplate.convertAndSend(channel, redisObjectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize execution update for session {}", sessionId, e);
            throw new IllegalStateException("Cannot serialize execution update", e);
        }
    }

    public Subscription subscribe(UUID sessionId, Consumer<ExecutionUpdatedPayload> callback) {
        String channel = channelFor(sessionId);
        ChannelTopic topic = new ChannelTopic(channel);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                callback.accept(redisObjectMapper.readValue(json, ExecutionUpdatedPayload.class));
            } catch (Exception e) {
                log.error("Failed to deserialize execution update on channel {}", channel, e);
            }
        };

        listenerContainer.addMessageListener(listener, topic);
        return () -> listenerContainer.removeMessageListener(listener, topic);
    }

    public String channelFor(UUID sessionId) {
        return properties.keyPrefix() + ":session:" + sessionId + ":execution-events";
    }

    @FunctionalInterface
    public interface Subscription {
        void unsubscribe();
    }
}
