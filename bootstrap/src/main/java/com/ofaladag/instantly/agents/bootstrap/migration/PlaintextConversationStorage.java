package com.ofaladag.instantly.agents.bootstrap.migration;

import com.ofaladag.instantly.agents.adapter.out.crypto.StorageCipher;
import com.ofaladag.instantly.agents.bootstrap.AgentRuntime;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** One-time upgrade from the original AES-GCM BYTEA columns. Normal JDBC access uses TEXT. */
@RequiredArgsConstructor
public final class PlaintextConversationStorage implements CustomTaskChange {
    private final Supplier<String> legacyKey;

    public PlaintextConversationStorage() {
        this(() -> System.getenv("AGENT_STORAGE_KEY"));
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection jdbc))
            throw new CustomChangeException("Plaintext migration requires a JDBC connection");
        Connection connection = jdbc.getUnderlyingConnection();
        try {
            if (connection.getAutoCommit())
                throw new CustomChangeException("Plaintext migration must run in a transaction");
            try (var statement = connection.createStatement();
                    var lock =
                            statement.executeQuery(
                                    "SELECT pg_try_advisory_xact_lock("
                                            + AgentRuntime.LEADERSHIP_LOCK
                                            + ")")) {
                lock.next();
                if (!lock.getBoolean(1))
                    throw new CustomChangeException(
                            "Stop the existing agent runtime before migrating database storage");
            }
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '30s'");
                statement.execute(
                        "LOCK TABLE agent.conversation, agent.chat_message, agent.reply_job IN"
                            + " ACCESS EXCLUSIVE MODE");
            }
            var decryptor = new LegacyDecryptor();
            migrate(
                    connection,
                    decryptor,
                    "conversation",
                    List.of("character_id", "conversation_id"),
                    List.of("summary"),
                    (row, _) ->
                            "summary/"
                                    + row.getString("character_id")
                                    + "/"
                                    + row.getString("conversation_id"));
            migrate(
                    connection,
                    decryptor,
                    "chat_message",
                    List.of("character_id", "message_id"),
                    List.of("content", "wire_payload"),
                    (row, column) ->
                            "message/"
                                    + row.getString("character_id")
                                    + "/"
                                    + row.getString("conversation_id")
                                    + "/"
                                    + row.getString("message_id")
                                    + "/"
                                    + (column.equals("content") ? "content" : "wire"));
            migrate(
                    connection,
                    decryptor,
                    "reply_job",
                    List.of("id"),
                    List.of("reply", "outbound_frame"),
                    (row, column) ->
                            "job/"
                                    + row.getString("id")
                                    + "/"
                                    + (column.equals("reply") ? "reply" : "frame"));
            // Liquibase commits the data conversion, column types and change-set record together.
        } catch (SQLException failure) {
            throw new CustomChangeException(
                    "PostgreSQL plaintext migration failed (SQLSTATE "
                            + failure.getSQLState()
                            + "); transaction must roll back");
        } catch (RuntimeException failure) {
            // Do not attach exceptions that could contain row values or key material.
            throw new CustomChangeException(
                    "Cannot decrypt legacy database content. Supply the original AGENT_STORAGE_KEY;"
                        + " transaction must roll back");
        }
    }

    private void migrate(
            Connection connection,
            LegacyDecryptor decryptor,
            String table,
            List<String> keys,
            List<String> columns,
            AssociatedData associatedData)
            throws SQLException {
        // All table/column identifiers come from the fixed lists in execute(), never from input.
        String assignments =
                columns.stream().map(column -> column + "=?").collect(Collectors.joining(","));
        String predicate =
                keys.stream().map(key -> key + "=?").collect(Collectors.joining(" AND "));
        try (var select = connection.prepareStatement("SELECT * FROM agent." + table);
                var update =
                        connection.prepareStatement(
                                "UPDATE agent."
                                        + table
                                        + " SET "
                                        + assignments
                                        + " WHERE "
                                        + predicate)) {
            select.setFetchSize(100);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    int parameter = 1;
                    for (String column : columns)
                        update.setBytes(
                                parameter++,
                                decryptor.decode(
                                        rows.getBytes(column),
                                        associatedData.context(rows, column)));
                    for (String key : keys) update.setObject(parameter++, rows.getObject(key));
                    update.executeUpdate();
                }
            }
        }
        String alterations =
                columns.stream()
                        .map(
                                column ->
                                        "ALTER COLUMN "
                                                + column
                                                + " TYPE TEXT USING convert_from("
                                                + column
                                                + ", 'UTF8')")
                        .collect(Collectors.joining(", "));
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent." + table + " " + alterations);
        }
    }

    private final class LegacyDecryptor {
        private StorageCipher cipher;

        byte[] decode(byte[] encrypted, String context) {
            if (encrypted == null) return null;
            if (cipher == null) {
                String key = legacyKey.get();
                if (key == null || key.isBlank())
                    throw new IllegalStateException("missing_legacy_key");
                cipher = new StorageCipher(key);
            }
            return cipher.decrypt(encrypted, context).getBytes(StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface AssociatedData {
        String context(ResultSet row, String column) throws SQLException;
    }

    @Override
    public String getConfirmationMessage() {
        return "Conversation data converted to plaintext TEXT; wire frames preserved";
    }

    @Override
    public void setUp() {}

    @Override
    public void setFileOpener(ResourceAccessor accessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        var errors = new ValidationErrors();
        if (!(database instanceof PostgresDatabase))
            errors.addError("Plaintext storage migration requires PostgreSQL");
        return errors;
    }
}
