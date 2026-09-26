package com.ofaladag.instantly.agents.adapter.out.persistence;

import com.ofaladag.instantly.agents.adapter.out.crypto.StorageCipher;
import com.ofaladag.instantly.agents.application.port.out.AgentStore;
import com.ofaladag.instantly.agents.domain.Chat;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

@RequiredArgsConstructor
public final class JdbcAgentStore implements AgentStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StorageCipher cipher;
    private final int maxAttempts;

    @Override
    public void bind(String character, UUID member, String backend) {
        transactions.executeWithoutResult(
                _ -> {
                    jdbc.update(
                            "INSERT INTO agent.agent_account(character_id,member_id,backend_url)"
                                + " VALUES (?,?,?) ON CONFLICT(character_id) DO NOTHING",
                            character,
                            member,
                            backend);
                    boolean matches =
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT member_id=? AND backend_url=? FROM"
                                                + " agent.agent_account WHERE character_id=?",
                                            Boolean.class,
                                            member,
                                            backend,
                                            character));
                    if (!matches) throw new IllegalStateException("account_binding_changed");
                });
    }

    @Override
    public void receive(Chat.Incoming m, Duration delay) {
        transactions.executeWithoutResult(
                _ -> {
                    jdbc.update(
                            "INSERT INTO agent.conversation(character_id,conversation_id) VALUES"
                                + " (?,?) ON CONFLICT DO NOTHING",
                            m.characterId(),
                            m.conversationId());
                    int inserted =
                            jdbc.update(
                                    """
                                    INSERT INTO agent.chat_message(character_id,conversation_id,message_id,position,direction,content,wire_payload)
                                    VALUES (?,?,?,?,'IN',?,?) ON CONFLICT(character_id,message_id) DO NOTHING
                                    """,
                                    m.characterId(),
                                    m.conversationId(),
                                    m.messageId(),
                                    m.sequence(),
                                    cipher.encrypt(
                                            m.text(),
                                            messageKey(
                                                    m.characterId(),
                                                    m.conversationId(),
                                                    m.messageId(),
                                                    "content")),
                                    cipher.encrypt(
                                            m.wirePayload(),
                                            messageKey(
                                                    m.characterId(),
                                                    m.conversationId(),
                                                    m.messageId(),
                                                    "wire")));
                    if (inserted == 1)
                        jdbc.update(
                                """
                                INSERT INTO agent.reply_job(id,character_id,conversation_id,incoming_message_id,position,available_at)
                                VALUES (?,?,?,?,?,now() + (? * interval '1 millisecond'))
                                """,
                                UUID.randomUUID(),
                                m.characterId(),
                                m.conversationId(),
                                m.messageId(),
                                m.sequence(),
                                delay.toMillis());
                });
    }

    @Override
    public Optional<Chat.Job> claim(Set<String> readyCharacters) {
        if (readyCharacters.isEmpty()) return Optional.empty();
        return transactions.execute(
                _ -> {
                    jdbc.update(
                            """
                            UPDATE agent.reply_job SET state='DEAD',last_error='attempts_exhausted',lease_owner=NULL,lease_until=NULL
                            WHERE state='PROCESSING' AND lease_until<now() AND attempts>=?
                            """,
                            maxAttempts);
                    var named = new NamedParameterJdbcTemplate(jdbc);
                    var jobs =
                            named.query(
                                    """
                                    WITH candidate AS (
                                      SELECT j.id FROM agent.reply_job j
                                      WHERE j.character_id IN (:characters)
                                        AND ((j.state='PENDING' AND j.available_at<=now()) OR (j.state='PROCESSING' AND j.lease_until<now()))
                                        AND NOT EXISTS (SELECT 1 FROM agent.reply_job earlier
                                          WHERE earlier.character_id=j.character_id AND earlier.conversation_id=j.conversation_id
                                            AND earlier.position<j.position AND earlier.state IN ('PENDING','PROCESSING'))
                                      ORDER BY j.available_at,j.created_at LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                                    )
                                    UPDATE agent.reply_job j SET state='PROCESSING',attempts=attempts+1,
                                      lease_owner=:owner,lease_until=now()+interval '5 minutes'
                                    FROM candidate WHERE j.id=candidate.id RETURNING j.*
                                    """,
                                    Map.of(
                                            "characters",
                                            readyCharacters,
                                            "owner",
                                            UUID.randomUUID()),
                                    (rs, _) -> job(rs));
                    return jobs.stream().findFirst();
                });
    }

    private Chat.Job job(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String character = rs.getString("character_id");
        UUID conversation = rs.getObject("conversation_id", UUID.class);
        return new Chat.Job(
                id,
                character,
                conversation,
                rs.getObject("incoming_message_id", UUID.class),
                rs.getLong("position"),
                rs.getInt("attempts"),
                rs.getObject("lease_owner", UUID.class),
                cipher.decrypt(rs.getBytes("reply"), jobKey(id, "reply")),
                cipher.decrypt(rs.getBytes("outbound_frame"), jobKey(id, "frame")));
    }

    @Override
    public Chat.Context context(Chat.Job job) {
        return transactions.execute(
                _ -> {
                    lock(job);
                    var summary =
                            jdbc.queryForObject(
                                    "SELECT summary,summary_through FROM agent.conversation WHERE"
                                        + " character_id=? AND conversation_id=?",
                                    (rs, _) ->
                                            new Chat.Context(
                                                    cipher.decrypt(rs.getBytes(1), summaryKey(job)),
                                                    rs.getLong(2),
                                                    List.of()),
                                    job.characterId(),
                                    job.conversationId());
                    var messages =
                            jdbc.query(
                                    """
                                    SELECT message_id,position,direction,content FROM agent.chat_message
                                    WHERE character_id=? AND conversation_id=? AND position>? AND position<=?
                                    ORDER BY position,direction
                                    """,
                                    (rs, _) ->
                                            new Chat.Message(
                                                    rs.getLong("position"),
                                                    rs.getString("direction").equals("IN")
                                                            ? "user"
                                                            : "assistant",
                                                    cipher.decrypt(
                                                            rs.getBytes("content"),
                                                            messageKey(
                                                                    job.characterId(),
                                                                    job.conversationId(),
                                                                    rs.getObject(
                                                                            "message_id",
                                                                            UUID.class),
                                                                    "content"))),
                                    job.characterId(),
                                    job.conversationId(),
                                    summary.summaryThrough(),
                                    job.position());
                    return new Chat.Context(summary.summary(), summary.summaryThrough(), messages);
                });
    }

    @Override
    public void summarize(Chat.Job job, String summary, long through) {
        if (through >= job.position())
            throw new IllegalArgumentException("summary_includes_current_message");
        transactions.executeWithoutResult(
                _ -> {
                    lock(job);
                    jdbc.update(
                            "UPDATE agent.conversation SET summary=?,summary_through=? WHERE"
                                + " character_id=? AND conversation_id=? AND summary_through<?",
                            cipher.encrypt(summary, summaryKey(job)),
                            through,
                            job.characterId(),
                            job.conversationId(),
                            through);
                });
    }

    @Override
    public void saveReply(Chat.Job job, String text) {
        update(job, "reply", cipher.encrypt(text, jobKey(job.id(), "reply")));
    }

    @Override
    public void saveFrame(Chat.Job job, String frame) {
        update(job, "outbound_frame", cipher.encrypt(frame, jobKey(job.id(), "frame")));
    }

    @Override
    public void clearFrame(Chat.Job job) {
        update(job, "outbound_frame", null);
    }

    private void update(Chat.Job job, String column, byte[] value) {
        // Column comes only from the three fixed methods above, never from input.
        check(
                jdbc.update(
                        "UPDATE agent.reply_job SET "
                                + column
                                + "=? WHERE id=? AND state='PROCESSING' AND lease_owner=? AND"
                                + " lease_until>now()",
                        value,
                        job.id(),
                        job.leaseOwner()));
    }

    @Override
    public void complete(Chat.Job job, UUID messageId, String text) {
        transactions.executeWithoutResult(
                _ -> {
                    lock(job);
                    jdbc.update(
                            """
                            INSERT INTO agent.chat_message(character_id,conversation_id,message_id,position,direction,content)
                            VALUES (?,?,?,?,'OUT',?)
                            """,
                            job.characterId(),
                            job.conversationId(),
                            messageId,
                            job.position(),
                            cipher.encrypt(
                                    text,
                                    messageKey(
                                            job.characterId(),
                                            job.conversationId(),
                                            messageId,
                                            "content")));
                    check(
                            jdbc.update(
                                    "UPDATE agent.reply_job SET"
                                        + " state='DONE',completed_at=now(),lease_owner=NULL,lease_until=NULL,last_error=NULL"
                                        + " WHERE id=? AND lease_owner=?",
                                    job.id(),
                                    job.leaseOwner()));
                });
    }

    @Override
    public void fail(Chat.Job job, String code, boolean permanent, int attempts) {
        if (!code.matches("[a-z_]{1,80}")) code = "processing_failed";
        long backoff = Math.min(300, 1L << Math.min(job.attempts(), 8));
        check(
                jdbc.update(
                        """
                        UPDATE agent.reply_job SET state=?,last_error=?,lease_owner=NULL,lease_until=NULL,
                          available_at=now()+(? * interval '1 second')
                        WHERE id=? AND state='PROCESSING' AND lease_owner=? AND lease_until>now()
                        """,
                        permanent || job.attempts() >= attempts ? "DEAD" : "PENDING",
                        code,
                        backoff,
                        job.id(),
                        job.leaseOwner()));
    }

    private void lock(Chat.Job job) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM agent.reply_job WHERE id=? AND state='PROCESSING' AND"
                            + " lease_owner=? AND lease_until>now() FOR UPDATE",
                        UUID.class,
                        job.id(),
                        job.leaseOwner());
        check(ids.size());
    }

    private static void check(int count) {
        if (count != 1) throw new Chat.LeaseLost();
    }

    private static String jobKey(UUID id, String field) {
        return "job/" + id + "/" + field;
    }

    private static String summaryKey(Chat.Job j) {
        return "summary/" + j.characterId() + "/" + j.conversationId();
    }

    private static String messageKey(
            String character, UUID conversation, UUID message, String field) {
        return "message/" + character + "/" + conversation + "/" + message + "/" + field;
    }
}
