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
