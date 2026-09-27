package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ofaladag.instantly.agents.adapter.in.internal.ReplyWorkers;
import com.ofaladag.instantly.agents.adapter.out.backend.InstantlyTransport;
import com.ofaladag.instantly.agents.adapter.out.persistence.JdbcAgentAccounts;
import com.ofaladag.instantly.agents.application.port.out.AgentAccounts;
import com.ofaladag.instantly.agents.bootstrap.AgentRuntime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.util.Base64;

import javax.sql.DataSource;

@SpringBootTest(
        properties = {
            "agents.enabled=true",
            "server.port=0",
            "agents.backend-url=http://127.0.0.1:1",
            "agents.model=local-test-model",
            "agents.openai-api-key=local-test-only"
        })
@Testcontainers
@Import(EnabledRuntimeIntegrationTest.SeedConfiguration.class)
class EnabledRuntimeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        Path directory = Files.createTempDirectory("agents-enabled-test-");
        directory.toFile().deleteOnExit();
        r.add("agents.key-directory", () -> directory.resolve("keys").toString());
        r.add("agents.identity-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired AgentRuntime runtime;
    @Autowired DataSource dataSource;
    @Autowired InstantlyTransport configuredTransport;

    @TestConfiguration(proxyBeanMethods = false)
    static class SeedConfiguration {
        @Bean
        @Primary
        @DependsOnDatabaseInitialization
        AgentAccounts seededAccounts(JdbcTemplate jdbc) throws Exception {
            try (var connection = jdbc.getDataSource().getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection, new ClassPathResource("db/seed/initial-agents.sql"));
            }
            jdbc.update(
                    "UPDATE agent.agent_account SET enabled=true WHERE character_id='aylin-izmir'");
            return new JdbcAgentAccounts(jdbc);
        }
    }

    @Test
    void enabledWiringStartsAndRejectsSecondRuntimeBeforeAnyAccountLogin() {
        assertThat(runtime.isRunning()).isTrue();
        assertThat(configuredTransport.configuredCount()).isEqualTo(1);
        var transport = mock(InstantlyTransport.class);
        var workers = mock(ReplyWorkers.class);
        var second = new AgentRuntime(dataSource, transport, workers);
        assertThatThrownBy(second::start).hasMessage("Unable to acquire agent runtime leadership");
        verify(transport, never()).start();
        verify(workers, never()).start();
        runtime.stop();
        assertThat(runtime.isRunning()).isFalse();
        var replacement = new AgentRuntime(dataSource, transport, workers);
        try {
            replacement.start();
            assertThat(replacement.isRunning()).isTrue();
        } finally {
            replacement.stop();
        }
    }
}
