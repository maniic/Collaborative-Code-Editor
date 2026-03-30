package com.collabeditor.websocket;

import com.collabeditor.auth.persistence.UserRepository;
import com.collabeditor.ot.service.CollaborationPersistenceService;
import com.collabeditor.redis.config.RedisCollaborationProperties;
import com.collabeditor.redis.model.CanonicalCollaborationEvent;
import com.collabeditor.redis.model.CanonicalEventType;
import com.collabeditor.redis.service.CollaborationRelayService;
import com.collabeditor.redis.service.SessionCoordinationService;
import com.collabeditor.execution.service.ExecutionBroadcastGateway;
import com.collabeditor.session.persistence.SessionParticipantRepository;
import com.collabeditor.snapshot.service.SnapshotRecoveryService;
import com.collabeditor.websocket.handler.CollaborationWebSocketHandler;
import com.collabeditor.websocket.protocol.CollaborationEnvelope;
import com.collabeditor.websocket.service.CollaborationSessionRegistry;
import com.collabeditor.websocket.service.DistributedCollaborationGateway;
import com.collabeditor.websocket.service.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DistributedCollaborationWebSocketHandlerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("collabeditor_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private CollaborationPersistenceService persistenceService;

    @Autowired
    private SnapshotRecoveryService snapshotRecoveryService;

    @Autowired
    private SessionParticipantRepository participantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final List<NodeContext> nodes = new CopyOnWriteArrayList<>();

    private final UUID sessionId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID userA = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID userB = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private final String emailA = "node-a@example.com";
    private final String emailB = "node-b@example.com";

    @BeforeEach
    void setUp() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbcTemplate.update("DELETE FROM execution_history");
        jdbcTemplate.update("DELETE FROM document_snapshots");
        jdbcTemplate.update("DELETE FROM session_operations");
        jdbcTemplate.update("DELETE FROM session_participants");
        jdbcTemplate.update("DELETE FROM coding_sessions");
        jdbcTemplate.update("DELETE FROM refresh_sessions");
        jdbcTemplate.update("DELETE FROM users");

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')",
                userA, emailA
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'hash')",
                userB, emailB
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) VALUES (?, 'DIST1234', 'JAVA', ?)",
                sessionId, userA
        );
        jdbcTemplate.update(
                "INSERT INTO session_participants (session_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                sessionId, userA
        );
        jdbcTemplate.update(
                "INSERT INTO session_participants (session_id, user_id, role, status) VALUES (?, ?, 'MEMBER', 'ACTIVE')",
                sessionId, userB
        );
    }

    @AfterEach
    void tearDown() {
        for (NodeContext node : nodes) {
            node.close();
        }
        nodes.clear();
    }

    @Test
    @DisplayName("node A accepts an insert, node B receives the relay event, and both nodes finish on the same canonical document and revision")
    void nodeAInsertRelaysToNodeBAndConverges() throws Exception {
        NodeContext nodeA = createNode();
        NodeContext nodeB = createNode();

        SocketCapture socketA = createSocket(sessionId, userA, emailA);
        SocketCapture socketB = createSocket(sessionId, userB, emailB);

        nodeA.handler.afterConnectionEstablished(socketA.session);
        nodeB.handler.afterConnectionEstablished(socketB.session);

        Thread.sleep(250);
        socketA.clear();
        socketB.clear();

        nodeA.handler.handleTextMessage(socketA.session, new TextMessage(submitInsertJson("op-1", 0, 0, "hello")));

        awaitCondition(() -> hasMessageOfType(socketA, "operation_ack"), "sender ack on node A");
        awaitCondition(() -> hasMessageOfType(socketA, "operation_applied"), "local relay delivery on node A");
        awaitCondition(() -> hasMessageOfType(socketB, "operation_applied"), "relay delivery on node B");

        CollaborationEnvelope appliedOnB = firstMessageOfType(socketB, "operation_applied").orElseThrow();
        assertThat(appliedOnB.payload().get("operationType").asText()).isEqualTo("INSERT");
        assertThat(appliedOnB.payload().get("text").asText()).isEqualTo("hello");
        assertThat(appliedOnB.payload().get("revision").asLong()).isEqualTo(1);

        assertThat(nodeA.registry.getRuntimeIfPresent(sessionId)).isPresent();
        assertThat(nodeA.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().document()).isEqualTo("hello");
        assertThat(nodeA.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().revision()).isEqualTo(1);

        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId)).isPresent();
        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().document()).isEqualTo("hello");
        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().revision()).isEqualTo(1);
    }

    @Test
    @DisplayName("node B starts with no cached runtime and document_sync bootstraps from the persisted document and revision")
    void nodeBConnectsFromPersistedDocumentState() throws Exception {
        NodeContext nodeA = createNode();
        SocketCapture socketA = createSocket(sessionId, userA, emailA);
        nodeA.handler.afterConnectionEstablished(socketA.session);
        Thread.sleep(250);
        socketA.clear();

        nodeA.handler.handleTextMessage(socketA.session, new TextMessage(submitInsertJson("persisted-op", 0, 0, "persisted document")));
        awaitCondition(() -> hasMessageOfType(socketA, "operation_ack"), "persisted ack on node A");

        NodeContext nodeB = createNode();
        SocketCapture socketB = createSocket(sessionId, userB, emailB);
        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId)).isEmpty();

        nodeB.handler.afterConnectionEstablished(socketB.session);
        awaitCondition(() -> hasMessageOfType(socketB, "document_sync"), "document_sync on node B");

        CollaborationEnvelope syncEnvelope = firstMessageOfType(socketB, "document_sync").orElseThrow();
        assertThat(syncEnvelope.payload().get("document").asText()).isEqualTo("persisted document");
        assertThat(syncEnvelope.payload().get("revision").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("node B receives a relay event with a revision gap, evicts its cached runtime, and emits resync_required instead of continuing on stale state")
    void revisionGapTriggersRuntimeEvictionAndResyncRequired() throws Exception {
        NodeContext nodeA = createNode();
        NodeContext nodeB = createNode();

        SocketCapture socketA = createSocket(sessionId, userA, emailA);
        SocketCapture socketB = createSocket(sessionId, userB, emailB);

        nodeA.handler.afterConnectionEstablished(socketA.session);
        nodeB.handler.afterConnectionEstablished(socketB.session);

        Thread.sleep(250);
        socketA.clear();
        socketB.clear();

        nodeA.handler.handleTextMessage(socketA.session, new TextMessage(submitInsertJson("gap-1", 0, 0, "a")));
        awaitCondition(() -> nodeB.registry.getRuntimeIfPresent(sessionId)
                .map(runtime -> runtime.snapshot().revision() == 1)
                .orElse(false), "node B revision 1 after first relay");

        socketB.clear();
        nodeB.listenerContainer.stop();

        nodeA.handler.handleTextMessage(socketA.session, new TextMessage(submitInsertJson("gap-2", 1, 1, "b")));
        awaitCondition(() -> nodeA.registry.getRuntimeIfPresent(sessionId)
                .map(runtime -> runtime.snapshot().revision() == 2)
                .orElse(false), "node A revision 2");

        nodeA.handler.handleTextMessage(socketA.session, new TextMessage(submitInsertJson("gap-3", 2, 2, "c")));
        awaitCondition(() -> nodeA.registry.getRuntimeIfPresent(sessionId)
                .map(runtime -> runtime.snapshot().revision() == 3)
                .orElse(false), "node A revision 3");

        CanonicalCollaborationEvent gapEvent = new CanonicalCollaborationEvent(
                sessionId,
                CanonicalEventType.OPERATION_APPLIED,
                3,
                userA,
                objectMapper.writeValueAsString(Map.of(
                        "userId", userA,
                        "revision", 3,
                        "operationType", "INSERT",
                        "position", 2,
                        "text", "c",
                        "clientOperationId", "gap-3"
                )),
                Instant.now()
        );

        nodeB.gateway.handleRelayEvent(gapEvent);
        awaitCondition(() -> hasMessageOfType(socketB, "resync_required"), "resync_required on node B");

        CollaborationEnvelope resync = firstMessageOfType(socketB, "resync_required").orElseThrow();
        assertThat(resync.payload().get("document").asText()).isEqualTo("abc");
        assertThat(resync.payload().get("revision").asLong()).isEqualTo(3);
        assertThat(resync.payload().get("reason").asText()).contains("Revision gap");

        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId)).isPresent();
        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().document()).isEqualTo("abc");
        assertThat(nodeB.registry.getRuntimeIfPresent(sessionId).orElseThrow().snapshot().revision()).isEqualTo(3);
    }

    private NodeContext createNode() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();

        ObjectMapper redisObjectMapper = new ObjectMapper();
        redisObjectMapper.registerModule(new JavaTimeModule());
        redisObjectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCollaborationProperties properties = new RedisCollaborationProperties("collab", 5000, 1800);
        CollaborationRelayService relayService =
                new CollaborationRelayService(redisTemplate, listenerContainer, redisObjectMapper, properties);
        SessionCoordinationService coordinationService = new SessionCoordinationService(redisTemplate, properties);
        CollaborationSessionRegistry registry = new CollaborationSessionRegistry();
        PresenceService presenceService = new PresenceService(75);
        DistributedCollaborationGateway gateway = new DistributedCollaborationGateway(
                registry,
                snapshotRecoveryService,
                persistenceService,
                coordinationService,
                relayService,
                presenceService,
                objectMapper
        );
        ExecutionBroadcastGateway executionBroadcastGateway = mock(ExecutionBroadcastGateway.class);
        CollaborationWebSocketHandler handler = new CollaborationWebSocketHandler(
                registry,
                gateway,
                executionBroadcastGateway,
                participantRepository,
                userRepository,
                presenceService,
                objectMapper
        );

        NodeContext node = new NodeContext(
                registry,
                gateway,
                handler,
                listenerContainer,
                connectionFactory
        );
        nodes.add(node);
        return node;
    }

    private SocketCapture createSocket(UUID sessionId, UUID userId, String email) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sessionId", sessionId);
        attributes.put("userId", userId);
        attributes.put("email", email);
        when(session.getAttributes()).thenReturn(attributes);

        CopyOnWriteArrayList<CollaborationEnvelope> messages = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            messages.add(objectMapper.readValue(message.getPayload(), CollaborationEnvelope.class));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        return new SocketCapture(session, messages);
    }

    private String submitInsertJson(String clientOperationId, long baseRevision, int position, String text) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "type", "submit_operation",
                "payload", Map.of(
                        "clientOperationId", clientOperationId,
                        "baseRevision", baseRevision,
                        "operationType", "INSERT",
                        "position", position,
                        "text", text
                )
        ));
    }

    private boolean hasMessageOfType(SocketCapture socket, String type) {
        return socket.messages.stream().anyMatch(message -> type.equals(message.type()));
    }

    private Optional<CollaborationEnvelope> firstMessageOfType(SocketCapture socket, String type) {
        return socket.messages.stream().filter(message -> type.equals(message.type())).findFirst();
    }

    private void awaitCondition(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + description);
    }

    private static final class NodeContext {
        private final CollaborationSessionRegistry registry;
        private final DistributedCollaborationGateway gateway;
        private final CollaborationWebSocketHandler handler;
        private final RedisMessageListenerContainer listenerContainer;
        private final LettuceConnectionFactory connectionFactory;

        private NodeContext(CollaborationSessionRegistry registry,
                            DistributedCollaborationGateway gateway,
                            CollaborationWebSocketHandler handler,
                            RedisMessageListenerContainer listenerContainer,
                            LettuceConnectionFactory connectionFactory) {
            this.registry = registry;
            this.gateway = gateway;
            this.handler = handler;
            this.listenerContainer = listenerContainer;
            this.connectionFactory = connectionFactory;
        }

        private void close() {
            listenerContainer.stop();
            connectionFactory.destroy();
        }
    }

    private static final class SocketCapture {
        private final WebSocketSession session;
        private final CopyOnWriteArrayList<CollaborationEnvelope> messages;

        private SocketCapture(WebSocketSession session, CopyOnWriteArrayList<CollaborationEnvelope> messages) {
            this.session = session;
            this.messages = messages;
        }

        private void clear() {
            messages.clear();
        }
    }
}
