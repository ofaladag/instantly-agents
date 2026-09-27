package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;

import com.ofaladag.instantly.agents.adapter.out.persistence.JdbcAgentAccounts;
import com.ofaladag.instantly.agents.adapter.out.persistence.JdbcAgentStore;
import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;
import com.ofaladag.instantly.agents.domain.AgentAccount;
import com.ofaladag.instantly.agents.domain.CharacterProfile;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

@SpringBootTest(properties = {"agents.enabled=false", "server.port=0"})
@Testcontainers
class AgentProfilesIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CharacterCatalog catalog;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE agent.character_profile CASCADE");
        seed();
    }

    void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("db/seed/initial-agents.sql"));
        }
    }

    @Test
    void manualSeedCreatesApprovedRosterAndDistinctPlaintextCredentials() {
        assertThat(catalog.all()).hasSize(50);
        assertThat(catalog.all().stream().filter(p -> p.gender().equals("female"))).hasSize(30);
        assertThat(catalog.all().stream().filter(p -> p.gender().equals("male"))).hasSize(20);
        assertThat(catalog.all())
                .allSatisfy(
                        p -> {
                            assertThat(p.age())
                                    .isBetween(20, p.gender().equals("female") ? 35 : 30);
                            assertThat(p.persona())
                                    .contains("## Voice", "## Background", "## Social approach");
                        });
        assertThat(catalog.all()).extracting(CharacterProfile::persona).doesNotHaveDuplicates();
        assertThat(catalog.commonInstructions())
                .contains("without sexual content", "language", "untrusted");
        var usernames = jdbc.queryForList("SELECT username FROM agent.agent_account", String.class);
        assertThat(usernames)
                .hasSize(50)
                .doesNotHaveDuplicates()
                .allSatisfy(value -> assertThat(value).matches("ai_[a-z0-9_]{3,27}"));
        var passwords = jdbc.queryForList("SELECT password FROM agent.agent_account", String.class);
        assertThat(passwords)
                .hasSize(50)
                .doesNotHaveDuplicates()
                .allSatisfy(value -> assertThat(value).matches("Aa1![0-9a-f]{32}"));
        assertThat(new JdbcAgentAccounts(jdbc).enabled()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM agent.agent_account WHERE member_id IS NOT"
                                    + " NULL OR backend_url IS NOT NULL",
                                Long.class))
                .isZero();
    }

    @Test
    void repeatedSeedPreservesEditedPersonaCredentialsActivationAndBinding() throws Exception {
        var store = new JdbcAgentStore(jdbc, new TransactionTemplate(transactions), 8);
        var member = UUID.randomUUID();
        store.bind("aylin-izmir", member, "http://localhost:8080");
        store.bind("aylin-izmir", member, "http://localhost:8080");
        jdbc.update(
                "UPDATE agent.character_profile SET persona=? WHERE id=?",
                "Edited persona",
                "aylin-izmir");
        jdbc.update(
                "UPDATE agent.agent_account SET username=?,password=?,enabled=true WHERE"
                    + " character_id=?",
                "edited-agent",
                "changed-password-for-test",
                "aylin-izmir");
        var before = jdbc.queryForList("SELECT * FROM agent.agent_account ORDER BY character_id");
        seed();
        assertThat(jdbc.queryForList("SELECT * FROM agent.agent_account ORDER BY character_id"))
                .isEqualTo(before);
        assertThat(catalog.get("aylin-izmir").persona()).isEqualTo("Edited persona");
        assertThat(catalog.all()).hasSize(50);
        assertThatThrownBy(() -> store.bind("unknown-character", member, "http://localhost:8080"))
                .hasMessage("account_binding_changed");
    }

    @Test
    void enabledAccountsAndCharacterEditsAreReadFromDatabase() {
        var accounts = new JdbcAgentAccounts(jdbc);
        jdbc.update("UPDATE agent.agent_account SET enabled=true WHERE character_id='aylin-izmir'");
        assertThat(accounts.enabled())
                .extracting(AgentAccount::characterId)
                .containsExactly("aylin-izmir");
        var account = accounts.enabled().getFirst();
        assertThat(account.password())
                .isEqualTo(
                        jdbc.queryForObject(
                                "SELECT password FROM agent.agent_account WHERE character_id=?",
                                String.class,
                                account.characterId()));
        assertThat(account.toString()).doesNotContain(account.username(), account.password());
        assertThat(catalog.get("aylin-izmir").name()).isEqualTo("Aylin");
        jdbc.update(
                "UPDATE agent.character_profile SET name=?,persona=? WHERE id=?",
                "Updated Aylin",
                "New personality from DB",
                account.characterId());
        assertThat(catalog.get(account.characterId()).instructions())
                .contains("Updated Aylin", "New personality from DB");
        jdbc.update(
                "UPDATE agent.agent_account SET enabled=false WHERE character_id=?",
                account.characterId());
        assertThat(accounts.enabled()).isEmpty();
        assertThatThrownBy(() -> catalog.get("unknown-character"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
