package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;

import com.ofaladag.instantly.agents.adapter.out.crypto.StorageCipher;
import com.ofaladag.instantly.agents.bootstrap.AgentRuntime;
import com.ofaladag.instantly.agents.bootstrap.migration.PlaintextConversationStorage;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.UUID;

@Testcontainers
class PlaintextStorageMigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CHARACTER = "aylin-izmir";
    private static final UUID CONVERSATION = UUID.randomUUID(),
            MESSAGE = UUID.randomUUID(),
            JOB = UUID.randomUUID();
    private static final String TEXT = "Merhaba 🫖, Ça va? 日本語";
    private static final String WIRE = "{\"encryptedEnvelope\":\"unchanged-e2e-ciphertext\"}";
    private static final String FRAME =
            "{ \"clientMessageId\":\"73fe0aa2-6377-4c23-aa31-8440ad43b5f6\",\n"
                + " \"encryptedEnvelope\":\"same-ciphertext\" }";
    private final StorageCipher cipher = new StorageCipher(KEY);
    private Connection connection;
    private JdbcTemplate jdbc;

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void originalSchema() throws Exception {
        try (var reset = connect();
                var statement = reset.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS agent CASCADE");
            statement.execute("DROP TABLE IF EXISTS databasechangelog, databasechangeloglock");
        }
        try (var db = connect();
                var resources = new ClassLoaderResourceAccessor();
                var liquibase =
                        new Liquibase(
                                "db/changelog/master.yaml", resources, new JdbcConnection(db))) {
            liquibase.update(1, new Contexts(), new LabelExpression());
        }
        connection = connect();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) connection.close();
    }

    private void seed() {
        jdbc.update(
                "INSERT INTO agent.agent_account(character_id,member_id,backend_url) VALUES"
                    + " (?,?,?)",
                CHARACTER,
                UUID.randomUUID(),
                "http://localhost:8080");
        jdbc.update(
                "INSERT INTO"
                    + " agent.conversation(character_id,conversation_id,summary,summary_through)"
                    + " VALUES (?,?,?,?)",
                CHARACTER,
                CONVERSATION,
                cipher.encrypt("Çayı seviyor", "summary/" + CHARACTER + "/" + CONVERSATION),
                0);
        jdbc.update(
                "INSERT INTO"
                    + " agent.chat_message(character_id,conversation_id,message_id,position,direction,content,wire_payload)"
                    + " VALUES (?,?,?,1,'IN',?,?)",
                CHARACTER,
                CONVERSATION,
                MESSAGE,
                cipher.encrypt(TEXT, messageContext(MESSAGE, "content")),
                cipher.encrypt(WIRE, messageContext(MESSAGE, "wire")));
        jdbc.update(
                "INSERT INTO"
                    + " agent.reply_job(id,character_id,conversation_id,incoming_message_id,position,reply,outbound_frame)"
                    + " VALUES (?,?,?,?,1,?,?)",
                JOB,
                CHARACTER,
                CONVERSATION,
                MESSAGE,
                cipher.encrypt("Selam 👋", "job/" + JOB + "/reply"),
                cipher.encrypt(FRAME, "job/" + JOB + "/frame"));
    }

    private String messageContext(UUID id, String field) {
        return "message/" + CHARACTER + "/" + CONVERSATION + "/" + id + "/" + field;
    }

    private PostgresDatabase database() throws Exception {
        var database = new PostgresDatabase();
        database.setConnection(new JdbcConnection(connection));
        return database;
    }

    private void migrate(String key) throws Exception {
        connection.setAutoCommit(false);
        try {
            new PlaintextConversationStorage(() -> key).execute(database());
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private String type(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema='agent' AND"
                    + " table_name=? AND column_name=?",
                String.class,
                table,
                column);
    }

    @Test
    void convertsAllFieldsAndPreservesExactPreparedFrameAndNulls() throws Exception {
        seed();
        UUID outgoing = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " agent.chat_message(character_id,conversation_id,message_id,position,direction,content)"
                    + " VALUES (?,?,?,1,'OUT',?)",
                CHARACTER,
                CONVERSATION,
                outgoing,
                cipher.encrypt("Eski cevap", messageContext(outgoing, "content")));
        migrate(KEY);
        assertThat(jdbc.queryForObject("SELECT summary FROM agent.conversation", String.class))
                .isEqualTo("Çayı seviyor");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content FROM agent.chat_message WHERE message_id=?",
                                String.class,
                                MESSAGE))
                .isEqualTo(TEXT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT wire_payload FROM agent.chat_message WHERE message_id=?",
                                String.class,
                                MESSAGE))
                .isEqualTo(WIRE);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT wire_payload FROM agent.chat_message WHERE message_id=?",
                                String.class,
                                outgoing))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT reply FROM agent.reply_job", String.class))
                .isEqualTo("Selam 👋");
        assertThat(jdbc.queryForObject("SELECT outbound_frame FROM agent.reply_job", String.class))
                .isEqualTo(FRAME);
        assertThat(jdbc.queryForObject("SELECT id FROM agent.reply_job", UUID.class))
                .isEqualTo(JOB);
        assertThat(type("chat_message", "content")).isEqualTo("text");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_nullable FROM information_schema.columns WHERE"
                                    + " table_schema='agent' AND table_name='chat_message' AND"
                                    + " column_name='content'",
                                String.class))
                .isEqualTo("NO");
    }

    @Test
    void freshLiquibaseInstallAndSubsequentStartupNeedNoDatabaseKey() throws Exception {
        try (var db = connect();
                var resources = new ClassLoaderResourceAccessor();
                var liquibase =
                        new Liquibase(
                                "db/changelog/master.yaml", resources, new JdbcConnection(db))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
        assertThat(type("conversation", "summary")).isEqualTo("text");
        assertThat(type("chat_message", "content")).isEqualTo("text");
        assertThat(type("chat_message", "wire_payload")).isEqualTo("text");
        assertThat(type("reply_job", "reply")).isEqualTo("text");
        assertThat(type("reply_job", "outbound_frame")).isEqualTo("text");
        jdbc.update(
                "INSERT INTO agent.agent_account(character_id,member_id,backend_url) VALUES"
                    + " (?,?,?)",
                CHARACTER,
                UUID.randomUUID(),
                "http://localhost:8080");
        jdbc.update(
                "INSERT INTO agent.conversation(character_id,conversation_id,summary) VALUES"
                    + " (?,?,?)",
                CHARACTER,
                CONVERSATION,
                "plain memory");
        try (var db = connect();
                var resources = new ClassLoaderResourceAccessor();
                var liquibase =
                        new Liquibase(
                                "db/changelog/master.yaml", resources, new JdbcConnection(db))) {
            liquibase.update(new Contexts(), new LabelExpression());
        }
        assertThat(jdbc.queryForObject("SELECT summary FROM agent.conversation", String.class))
                .isEqualTo("plain memory");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void missingKeyKeepsEncryptedRowsAndOriginalSchema() {
        seed();
        byte[] original =
                jdbc.queryForObject("SELECT content FROM agent.chat_message", byte[].class);
        assertThatThrownBy(() -> migrate(null))
                .isInstanceOf(CustomChangeException.class)
                .hasMessageContaining("original AGENT_STORAGE_KEY");
        assertThat(type("chat_message", "content")).isEqualTo("bytea");
        assertThat(jdbc.queryForObject("SELECT content FROM agent.chat_message", byte[].class))
                .isEqualTo(original);
    }

    @Test
    void corruptMessageRollsBackPreviouslyConvertedSummaryAndColumnTypes() {
        seed();
        byte[] summary =
                jdbc.queryForObject("SELECT summary FROM agent.conversation", byte[].class);
        byte[] damaged =
                jdbc.queryForObject("SELECT content FROM agent.chat_message", byte[].class);
        damaged[damaged.length - 1] ^= 1;
        jdbc.update("UPDATE agent.chat_message SET content=?", damaged);
        assertThatThrownBy(() -> migrate(KEY)).isInstanceOf(CustomChangeException.class);
        assertThat(type("conversation", "summary")).isEqualTo("bytea");
        assertThat(jdbc.queryForObject("SELECT summary FROM agent.conversation", byte[].class))
                .isEqualTo(summary);
        assertThat(jdbc.queryForObject("SELECT content FROM agent.chat_message", byte[].class))
                .isEqualTo(damaged);
    }

    @Test
    void streamsMoreThanOneFetchBatchWithoutSkippingMessages() throws Exception {
        seed();
        for (int i = 2; i <= 205; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO"
                        + " agent.chat_message(character_id,conversation_id,message_id,position,direction,content)"
                        + " VALUES (?,?,?,?,'IN',?)",
                    CHARACTER,
                    CONVERSATION,
                    id,
                    i,
                    cipher.encrypt(TEXT, messageContext(id, "content")));
        }
        migrate(KEY);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM agent.chat_message WHERE content=?",
                                Integer.class,
                                TEXT))
                .isEqualTo(205);
    }

    @Test
    void runningAgentPreventsConversion() throws Exception {
        seed();
        try (var owner = connect();
                var statement = owner.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + AgentRuntime.LEADERSHIP_LOCK + ")");
            assertThatThrownBy(() -> migrate(KEY))
                    .hasMessageContaining("Stop the existing agent runtime");
            assertThat(type("chat_message", "content")).isEqualTo("bytea");
        }
    }
}
