package com.ofaladag.instantly.agents.bootstrap;

import com.ofaladag.instantly.agents.adapter.in.internal.ReplyWorkers;
import com.ofaladag.instantly.agents.adapter.out.backend.*;
import com.ofaladag.instantly.agents.adapter.out.crypto.*;
import com.ofaladag.instantly.agents.adapter.out.openai.OpenAiLanguageModel;
import com.ofaladag.instantly.agents.adapter.out.persistence.*;
import com.ofaladag.instantly.agents.application.port.in.*;
import com.ofaladag.instantly.agents.application.port.out.*;
import com.ofaladag.instantly.agents.application.usecase.*;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {
    @Bean
    CharacterCatalog characters(JdbcTemplate jdbc) throws IOException {
        return new JdbcCharacterCatalog(
                jdbc,
                new ClassPathResource("prompts/social-policy.md")
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "agents.enabled", havingValue = "true")
    static class EnabledRuntime {
        @Bean
        AgentAccounts agentAccounts(JdbcTemplate jdbc) {
            return new JdbcAgentAccounts(jdbc);
        }

        @Bean
        StorageCipher identityCipher(AgentProperties p) {
            if (p.concurrency() < 1
                    || p.concurrency() > 32
                    || p.maxAttempts() < 1
                    || p.maxAttempts() > 20
                    || p.replyDelaySeconds() < 0
                    || p.replyDelaySeconds() > 3600)
                throw new IllegalArgumentException("Invalid agent worker configuration");
            return new StorageCipher(p.identityKey());
        }

        @Bean
        AgentStore agentStore(JdbcTemplate jdbc, PlatformTransactionManager tx, AgentProperties p) {
            return new JdbcAgentStore(jdbc, new TransactionTemplate(tx), p.maxAttempts());
        }

        @Bean
        PeerKeyStore peerKeyStore(JdbcTemplate jdbc) {
            return new JdbcPeerKeyStore(jdbc);
        }

        @Bean
        IdentityKeyStore keyStore(AgentProperties p, StorageCipher cipher, JsonMapper json) {
            return new IdentityKeyStore(p.keyDirectory(), cipher, json);
        }

        @Bean(destroyMethod = "close")
        HttpClient backendHttpClient() {
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Bean(destroyMethod = "close")
        OpenAIClient openAIClient(AgentProperties p) {
            if (p.model().isBlank() || p.openaiApiKey().isBlank())
                throw new IllegalArgumentException(
                        "OPENAI_MODEL and OPENAI_API_KEY are required when agents are enabled");
            return OpenAIOkHttpClient.builder()
                    .apiKey(p.openaiApiKey())
                    .timeout(Duration.ofSeconds(45))
                    .maxRetries(0)
                    .build();
        }

        @Bean
        LanguageModel languageModel(
                OpenAIClient client, AgentProperties p, CharacterCatalog catalog) {
            return new OpenAiLanguageModel(client, p.model(), catalog);
        }

        @Bean
        ReceiveMessageUseCase receiveMessage(AgentStore store, AgentProperties p) {
            return new ReceiveMessage(store, Duration.ofSeconds(p.replyDelaySeconds()));
        }

        @Bean
        @DependsOnDatabaseInitialization
        InstantlyTransport transport(
                AgentProperties p,
                HttpClient http,
                JsonMapper json,
                CharacterCatalog catalog,
                AgentAccounts accounts,
                AgentStore store,
                PeerKeyStore peers,
                IdentityKeyStore keys,
                ReceiveMessageUseCase receiver) {
            var configured = accounts.enabled();
            if (configured.isEmpty()) throw new IllegalStateException("no_enabled_agent_accounts");
            configured.forEach(account -> catalog.get(account.characterId()));
            var clients =
                    configured.stream()
                            .map(
                                    account ->
                                            new InstantlyAccountClient(
                                                    p.backendUrl(),
                                                    account,
                                                    http,
                                                    json,
                                                    store,
                                                    peers,
                                                    keys))
                            .toList();
            return new InstantlyTransport(clients, http, json, receiver);
        }

        @Bean
        ProcessReplyUseCase processReply(
                AgentStore store,
                CharacterCatalog catalog,
                LanguageModel model,
                InstantlyTransport transport,
                JsonMapper json,
                AgentProperties p) {
            return new ReplyProcessor(
                    store,
                    catalog,
                    model,
                    transport,
                    frame ->
                            InstantlyAccountClient.uuid(
                                    json.readTree(frame).path("payload"), "clientMessageId"),
                    p.maxAttempts());
        }

        @Bean
        ReplyWorkers workers(
                AgentStore store,
                ProcessReplyUseCase processor,
                InstantlyTransport transport,
                AgentProperties p) {
            return new ReplyWorkers(store, processor, transport::readyCharacters, p.concurrency());
        }

        @Bean
        AgentRuntime agentRuntime(
                DataSource dataSource, InstantlyTransport transport, ReplyWorkers workers) {
            return new AgentRuntime(dataSource, transport, workers);
        }

        @Bean
        HealthIndicator agentsHealth(
                AgentRuntime runtime, InstantlyTransport transport, JdbcTemplate jdbc) {
            return () -> {
                long dead =
                        jdbc.queryForObject(
                                "SELECT count(*) FROM agent.reply_job WHERE state='DEAD'",
                                Long.class);
                int ready = transport.readyCharacters().size();
                return (runtime.isRunning() && ready == transport.configuredCount() && dead == 0
                                ? Health.up()
                                : Health.down())
                        .withDetail("readyAccounts", ready)
                        .withDetail("configuredAccounts", transport.configuredCount())
                        .withDetail("deadJobs", dead)
                        .build();
            };
        }
    }
}
