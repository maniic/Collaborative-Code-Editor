package com.collabeditor.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that V1__phase1_baseline.sql applies cleanly against a real
 * PostgreSQL instance and produces the expected schema artifacts.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("collabeditor_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

    @Test
    void contextBootsAgainstContainerWithValidationEnabled() throws SQLException {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.locations")).contains("classpath:db/migration");

        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
    }

    @Test
    void migrationCreatesUsersTable() throws SQLException {
        List<String> columns = getColumnNames("users");
        assertThat(columns).contains("id", "email", "password_hash", "created_at", "updated_at");
    }

    @Test
    void migrationCreatesRefreshSessionsTable() throws SQLException {
        List<String> columns = getColumnNames("refresh_sessions");
        assertThat(columns).contains(
                "id", "user_id", "token_hash", "device_id",
                "user_agent", "expires_at", "revoked_at",
                "replaced_by_session_id", "created_at", "last_used_at"
        );
    }

    @Test
    void migrationCreatesCodingSessionsTable() throws SQLException {
        List<String> columns = getColumnNames("coding_sessions");
        assertThat(columns).contains(
                "id", "invite_code", "language", "owner_user_id",
                "participant_cap", "empty_since", "cleanup_after",
                "created_at", "updated_at"
        );
    }

    @Test
    void participantCapColumnUsesSmallint() throws SQLException {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection();
             ResultSet columns = connection.getMetaData().getColumns(null, "public", "coding_sessions", "participant_cap")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("TYPE_NAME")).matches("(?i)(int2|smallint)");
        }
    }

    @Test
    void migrationCreatesSessionOperationsTable() throws SQLException {
        List<String> columns = getColumnNames("session_operations");
        assertThat(columns).contains(
                "id", "session_id", "revision", "author_user_id",
                "client_operation_id", "operation_type", "position",
                "text", "length", "created_at"
        );
    }

    @Test
    void migrationCreatesDocumentSnapshotsTable() throws SQLException {
        List<String> columns = getColumnNames("document_snapshots");
        assertThat(columns).contains(
                "id", "session_id", "revision", "document", "created_at"
        );
    }

    @Test
    void migrationCreatesExecutionHistoryTable() throws SQLException {
        List<String> columns = getColumnNames("execution_history");
        assertThat(columns).contains(
                "id", "session_id", "requested_by_user_id", "language",
                "source_revision", "status", "stdout", "stderr",
                "exit_code", "created_at", "started_at", "finished_at"
        );
    }

    @Test
    void migrationCreatesSessionParticipantsTable() throws SQLException {
        List<String> columns = getColumnNames("session_participants");
        assertThat(columns).contains(
                "session_id", "user_id", "role", "status",
                "joined_at", "left_at"
        );
    }

    @Test
    void languageCheckConstraintRejectsInvalidValue() {
        // Insert a user first (required by foreign key)
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES (gen_random_uuid(), 'test@example.com', 'hash')"
        );

        // Attempt insert with invalid language should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) " +
                        "VALUES (gen_random_uuid(), 'ABCD1234', 'RUBY', " +
                        "(SELECT id FROM users WHERE email = 'test@example.com'))"
                )
        );
    }

    @Test
    void sessionParticipantUniquenessConstraint() {
        // Insert user and session
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES ('a0000000-0000-0000-0000-000000000001', 'unique@example.com', 'hash')"
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) " +
                "VALUES ('b0000000-0000-0000-0000-000000000001', 'UNIQ1234', 'JAVA', 'a0000000-0000-0000-0000-000000000001')"
        );
        // First participant join succeeds
        jdbcTemplate.update(
                "INSERT INTO session_participants (session_id, user_id, role, status) " +
                "VALUES ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'OWNER', 'ACTIVE')"
        );

        // Duplicate should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO session_participants (session_id, user_id, role, status) " +
                        "VALUES ('b0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'PARTICIPANT', 'ACTIVE')"
                )
        );
    }

    @Test
    void sessionOperationsRejectsDuplicateSessionRevision() {
        // Same (session_id, revision) should be rejected by unique constraint
        String userId = "c0000000-0000-0000-0000-000000000001";
        String sessionIdStr = "d0000000-0000-0000-0000-000000000001";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES ('" + userId + "', 'dup-rev@example.com', 'hash')"
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) " +
                "VALUES ('" + sessionIdStr + "', 'DREV1234', 'JAVA', '" + userId + "')"
        );
        // First operation at revision 1 succeeds
        jdbcTemplate.update(
                "INSERT INTO session_operations (id, session_id, revision, author_user_id, client_operation_id, operation_type, position, text) " +
                "VALUES (gen_random_uuid(), '" + sessionIdStr + "', 1, '" + userId + "', 'op-a', 'INSERT', 0, 'hello')"
        );

        // Second operation with same (session_id, revision) should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO session_operations (id, session_id, revision, author_user_id, client_operation_id, operation_type, position, text) " +
                        "VALUES (gen_random_uuid(), '" + sessionIdStr + "', 1, '" + userId + "', 'op-b', 'INSERT', 0, 'world')"
                )
        );
    }

    @Test
    void insertOperationRowCannotSetLength() {
        // INSERT operation row must have text NOT NULL and length NULL
        String userId = "c0000000-0000-0000-0000-000000000002";
        String sessionIdStr = "d0000000-0000-0000-0000-000000000002";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES ('" + userId + "', 'ins-len@example.com', 'hash')"
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) " +
                "VALUES ('" + sessionIdStr + "', 'ILEN1234', 'JAVA', '" + userId + "')"
        );

        // INSERT operation with length set should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO session_operations (id, session_id, revision, author_user_id, client_operation_id, operation_type, position, text, length) " +
                        "VALUES (gen_random_uuid(), '" + sessionIdStr + "', 1, '" + userId + "', 'op-ins', 'INSERT', 0, 'hello', 5)"
                )
        );
    }

    @Test
    void deleteOperationRowCannotOmitLength() {
        // DELETE operation row must have text NULL and length > 0
        String userId = "c0000000-0000-0000-0000-000000000003";
        String sessionIdStr = "d0000000-0000-0000-0000-000000000003";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash) VALUES ('" + userId + "', 'del-nolen@example.com', 'hash')"
        );
        jdbcTemplate.update(
                "INSERT INTO coding_sessions (id, invite_code, language, owner_user_id) " +
                "VALUES ('" + sessionIdStr + "', 'DLEN1234', 'JAVA', '" + userId + "')"
        );

        // DELETE operation without length should fail
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO session_operations (id, session_id, revision, author_user_id, client_operation_id, operation_type, position) " +
                        "VALUES (gen_random_uuid(), '" + sessionIdStr + "', 1, '" + userId + "', 'op-del', 'DELETE', 0)"
                )
        );
    }

    private List<String> getColumnNames(String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, "public", tableName, null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }
}
