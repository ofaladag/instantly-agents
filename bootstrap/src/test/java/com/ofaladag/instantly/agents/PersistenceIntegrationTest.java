package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;

import com.ofaladag.instantly.agents.adapter.out.persistence.JdbcAgentStore;
import com.ofaladag.instantly.agents.domain.Chat;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = {"agents.enabled=false", "server.port=0"})
@Testcontainers
class PersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    JdbcAgentStore store;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE agent.agent_account CASCADE");
        store = new JdbcAgentStore(jdbc, new TransactionTemplate(transactions), 8);
        store.bind("aylin-izmir", UUID.randomUUID(), "http://localhost:8080");
    }

    Chat.Incoming incoming(UUID conversation, long sequence) {
        return new Chat.Incoming(
                "aylin-izmir",
                conversation,
                UUID.randomUUID(),
                sequence,
                "private incoming " + sequence,
                "encrypted wire payload");
    }

    @Test
    void liquibaseOwnsSchemaAndDuplicateIngestCreatesExactlyOneJob() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM databasechangelog WHERE"
                                        + " id='001-agent-runtime'",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT data_type FROM information_schema.columns
                                WHERE table_schema='agent' AND (table_name, column_name) IN (
                                    ('conversation', 'summary'),
                                    ('chat_message', 'content'),
                                    ('chat_message', 'wire_payload'),
                                    ('reply_job', 'reply'),
                                    ('reply_job', 'outbound_frame'))
                                """,
                                String.class))
                .containsExactly("text", "text", "text", "text", "text");
        var input = incoming(UUID.randomUUID(), 1);
        store.receive(input, Duration.ZERO);
        store.receive(input, Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.chat_message", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.reply_job", Long.class))
                .isEqualTo(1);
        var job = store.claim(Set.of("aylin-izmir")).orElseThrow();
        assertThat(store.context(job).messages())
                .extracting(Chat.Message::text)
                .containsExactly(input.text());
        assertThat(jdbc.queryForObject("SELECT content FROM agent.chat_message", String.class))
                .isEqualTo(input.text());
        assertThat(jdbc.queryForObject("SELECT wire_payload FROM agent.chat_message", String.class))
                .isEqualTo(input.wirePayload());
    }

    @Test
    void failedIngestRollsBackBothInputAndWork() {
        var first = incoming(UUID.randomUUID(), 1);
        store.receive(first, Duration.ZERO);
        assertThatThrownBy(() -> store.receive(incoming(first.conversationId(), 1), Duration.ZERO))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.chat_message", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent.reply_job", Long.class))
                .isEqualTo(1);
    }

    @Test
    void leasesFenceStaleWorkersAndRecoverExactFrameInConversationOrder() {
        UUID conversation = UUID.randomUUID();
        store.receive(incoming(conversation, 1), Duration.ZERO);
        store.receive(incoming(conversation, 3), Duration.ZERO);
        var old = store.claim(Set.of("aylin-izmir")).orElseThrow();
        store.saveReply(old, "persisted reply");
        store.saveFrame(old, "persisted frame");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reply FROM agent.reply_job WHERE id=?",
                                String.class,
                                old.id()))
                .isEqualTo("persisted reply");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outbound_frame FROM agent.reply_job WHERE id=?",
                                String.class,
                                old.id()))
                .isEqualTo("persisted frame");
        assertThat(store.claim(Set.of("aylin-izmir"))).isEmpty();
        jdbc.update(
                "UPDATE agent.reply_job SET lease_until=now()-interval '1 second' WHERE id=?",
                old.id());
        var recovered = store.claim(Set.of("aylin-izmir")).orElseThrow();
        assertThat(recovered.id()).isEqualTo(old.id());
        assertThat(recovered.leaseOwner()).isNotEqualTo(old.leaseOwner());
        assertThat(recovered.reply()).isEqualTo("persisted reply");
        assertThat(recovered.outboundFrame()).isEqualTo("persisted frame");
        assertThatThrownBy(() -> store.saveFrame(old, "stale write"))
                .isInstanceOf(Chat.LeaseLost.class);
        store.complete(recovered, UUID.randomUUID(), recovered.reply());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content FROM agent.chat_message WHERE direction='OUT'",
                                String.class))
                .isEqualTo(recovered.reply());
        var next = store.claim(Set.of("aylin-izmir")).orElseThrow();
        assertThat(next.position()).isEqualTo(3);
        assertThat(store.context(next).messages())
                .extracting(Chat.Message::role)
                .containsExactly("user", "assistant", "user");
        store.summarize(next, "summary", 1);
        assertThat(store.context(next).messages()).hasSize(1);
        assertThat(store.context(next).summary()).isEqualTo("summary");
        assertThat(jdbc.queryForObject("SELECT summary FROM agent.conversation", String.class))
                .isEqualTo("summary");
    }

    @Test
    void parallelClaimsNeverLeaseOneConversationTwice() throws Exception {
        UUID conversation = UUID.randomUUID();
        for (int i = 1; i <= 10; i++) store.receive(incoming(conversation, i), Duration.ZERO);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Optional<Chat.Job>>>();
            for (int i = 0; i < 8; i++)
                futures.add(executor.submit(() -> store.claim(Set.of("aylin-izmir"))));
            int claimed = 0;
            for (var future : futures) if (future.get().isPresent()) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void anotherCharactersConversationNeverAppearsInContext() {
        UUID conversation = UUID.randomUUID();
        store.bind("deniz-izmir", UUID.randomUUID(), "http://localhost:8080");
        store.receive(
                new Chat.Incoming(
                        "deniz-izmir",
                        conversation,
                        UUID.randomUUID(),
                        1,
                        "other private conversation",
                        "wire"),
                Duration.ZERO);
        store.receive(incoming(conversation, 1), Duration.ZERO);
        var job = store.claim(Set.of("aylin-izmir")).orElseThrow();
        assertThat(store.context(job).messages())
                .extracting(Chat.Message::text)
                .containsExactly("private incoming 1");
        assertThatThrownBy(
                        () -> store.bind("aylin-izmir", UUID.randomUUID(), "http://localhost:8080"))
                .hasMessage("account_binding_changed");
    }
}
