package com.collabeditor.execution;

import com.collabeditor.execution.service.ExecutionBroadcastGateway;
import com.collabeditor.execution.service.ExecutionEventRelayService;
import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.protocol.ExecutionUpdatedPayload;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ExecutionEventRelayServiceTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private final List<NodeContext> nodes = new ArrayList<>();

    @BeforeEach
    void flushRedis() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        try {
            connectionFactory.getConnection().serverCommands().flushAll();
        } finally {
            connectionFactory.destroy();
        }
    }

    @AfterEach
    void tearDown() {
        for (NodeContext node : nodes) {
            node.close();
        }
        nodes.clear();
    }

    @Test
    @DisplayName("publish and subscribe use the exact collab:session:{sessionId}:execution-events channel")
    void publishAndSubscribeUseExactChannel() throws Exception {
        UUID sessionId = UUID.randomUUID();
        NodeContext node = createNode();
        List<ExecutionUpdatedPayload> received = new CopyOnWriteArrayList<>();

        assertThat(node.relayService.channelFor(sessionId))
                .isEqualTo("collab:session:" + sessionId + ":execution-events");

        ExecutionEventRelayService.Subscription subscription =
                node.relayService.subscribe(sessionId, received::add);
        try {
            node.relayService.publish(sessionId, payload("QUEUED", "Execution queued."));
            awaitCondition(() -> received.size() == 1, "redis execution relay delivery");
            assertThat(received.get(0).status()).isEqualTo("QUEUED");
        } finally {
            subscription.unsubscribe();
        }
    }

    @Test
    @DisplayName("ensureSessionSubscription broadcasts execution_updated to local room sockets")
    void ensureSessionSubscriptionBroadcastsToLocalSockets() throws Exception {
        UUID sessionId = UUID.randomUUID();
        NodeContext node = createNode();
        SocketCapture socket = createSocket();

        node.registry.addSocket(sessionId, socket.session);
        node.gateway.ensureSessionSubscription(sessionId);

        node.relayService.publish(sessionId, payload("RUNNING", "Execution started."));

        awaitCondition(() -> socket.messages.size() == 1, "local websocket execution_updated");
        CollaborationEnvelope envelope = node.objectMapper.readValue(
                socket.messages.get(0).getPayload(), CollaborationEnvelope.class);
        assertThat(envelope.type()).isEqualTo("execution_updated");
        assertThat(envelope.payload().get("status").asText()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("two backend nodes subscribed to the same session execution channel broadcast QUEUED RUNNING COMPLETED execution_updated payloads")
    void twoBackendNodesBroadcastSameLifecyclePayloads() throws Exception {
        UUID sessionId = UUID.randomUUID();
        NodeContext nodeA = createNode();
        NodeContext nodeB = createNode();
        SocketCapture socketA = createSocket();
        SocketCapture socketB = createSocket();

        nodeA.registry.addSocket(sessionId, socketA.session);
        nodeB.registry.addSocket(sessionId, socketB.session);
        nodeA.gateway.ensureSessionSubscription(sessionId);
        nodeB.gateway.ensureSessionSubscription(sessionId);

        nodeA.relayService.publish(sessionId, payload("QUEUED", "Execution queued."));
        nodeA.relayService.publish(sessionId, payload("RUNNING", "Execution started."));
        nodeA.relayService.publish(sessionId, payload("COMPLETED", "Execution completed successfully."));

        awaitCondition(() -> socketA.messages.size() == 3, "node A queued running completed");
        awaitCondition(() -> socketB.messages.size() == 3, "node B queued running completed");

        assertThat(extractStatuses(nodeA.objectMapper, socketA.messages))
                .containsExactly("QUEUED", "RUNNING", "COMPLETED");
        assertThat(extractStatuses(nodeB.objectMapper, socketB.messages))
                .containsExactly("QUEUED", "RUNNING", "COMPLETED");
    }

    private NodeContext createNode() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.setTaskExecutor(new SyncTaskExecutor());
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCollaborationProperties properties = new RedisCollaborationProperties("collab", 5000, 1800);
        ExecutionEventRelayService relayService =
                new ExecutionEventRelayService(redisTemplate, listenerContainer, objectMapper, properties);
        CollaborationSessionRegistry registry = new CollaborationSessionRegistry();
        ExecutionBroadcastGateway gateway = new ExecutionBroadcastGateway(registry, relayService, objectMapper);

        NodeContext node = new NodeContext(connectionFactory, listenerContainer, relayService, registry, gateway, objectMapper);
        nodes.add(node);
        return node;
    }

    private SocketCapture createSocket() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());

        List<TextMessage> messages = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0, TextMessage.class));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        return new SocketCapture(session, messages);
    }

    private ExecutionUpdatedPayload payload(String status, String message) {
        UUID executionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        return new ExecutionUpdatedPayload(
                executionId,
                userId,
                "exec@example.com",
                "PYTHON",
                12L,
                status,
                "COMPLETED".equals(status) ? "done" : null,
                null,
                "COMPLETED".equals(status) ? 0 : null,
                now,
                "QUEUED".equals(status) ? null : now,
                "COMPLETED".equals(status) ? now : null,
                message
        );
    }

    private List<String> extractStatuses(ObjectMapper objectMapper, List<TextMessage> messages) throws Exception {
        List<String> statuses = new ArrayList<>();
        for (TextMessage message : messages) {
            CollaborationEnvelope envelope = objectMapper.readValue(message.getPayload(), CollaborationEnvelope.class);
            statuses.add(envelope.payload().get("status").asText());
        }
        return statuses;
    }

    private void awaitCondition(BooleanSupplier condition, String description) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private record SocketCapture(WebSocketSession session, List<TextMessage> messages) {
    }

    private record NodeContext(
            LettuceConnectionFactory connectionFactory,
            RedisMessageListenerContainer listenerContainer,
            ExecutionEventRelayService relayService,
            CollaborationSessionRegistry registry,
            ExecutionBroadcastGateway gateway,
            ObjectMapper objectMapper
    ) {
        void close() {
            listenerContainer.stop();
            connectionFactory.destroy();
        }
    }
}
