package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ofaladag.instantly.agents.adapter.in.internal.ReplyWorkers;
import com.ofaladag.instantly.agents.adapter.out.backend.InstantlyTransport;
import com.ofaladag.instantly.agents.bootstrap.AgentRuntime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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
class EnabledRuntimeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        Path directory = Files.createTempDirectory("agents-enabled-test-");
        Path accounts =
                Files.createFile(
                        directory.resolve("accounts.json"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
                accounts,
                "[{\"characterId\":\"aylin-izmir\",\"username\":\"local-test\",\"password\":\"local-test\"}]");
        accounts.toFile().deleteOnExit();
        directory.toFile().deleteOnExit();
        r.add("agents.accounts-file", accounts::toString);
        r.add("agents.key-directory", () -> directory.resolve("keys").toString());
        r.add("agents.storage-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired AgentRuntime runtime;
    @Autowired DataSource dataSource;

    @Test
    void enabledWiringStartsAndRejectsSecondRuntimeBeforeAnyAccountLogin() {
        assertThat(runtime.isRunning()).isTrue();
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
