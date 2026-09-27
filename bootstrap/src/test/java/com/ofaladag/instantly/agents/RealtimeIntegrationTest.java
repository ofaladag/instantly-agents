package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.ofaladag.instantly.agents.adapter.in.internal.ReplyWorkers;
import com.ofaladag.instantly.agents.adapter.out.backend.*;
import com.ofaladag.instantly.agents.adapter.out.crypto.*;
import com.ofaladag.instantly.agents.adapter.out.persistence.*;
import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;
import com.ofaladag.instantly.agents.application.port.out.LanguageModel;
import com.ofaladag.instantly.agents.application.usecase.*;
import com.ofaladag.instantly.agents.domain.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agents.enabled=false")
@Import(RealtimeIntegrationTest.FakeBackendConfiguration.class)
@Testcontainers
class RealtimeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.3-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JsonMapper json;
    @Autowired FakeApi backend;
    @Autowired CharacterCatalog catalog;
    @TempDir Path keys;

    @Test
    @Sql("classpath:db/seed/initial-agents.sql")
    void repliesThroughEncryptedSocketAndRecoversLostAcceptanceWithoutDuplicateGeneration()
            throws Exception {
        var cipher = new StorageCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new JdbcAgentStore(jdbc, new TransactionTemplate(transactions), 8);
        var peers = new JdbcPeerKeyStore(jdbc);
        var modelCalls = new AtomicInteger();
        LanguageModel model =
                new LanguageModel() {
                    public String reply(CharacterProfile character, Chat.Context context) {
                        modelCalls.incrementAndGet();
                        assertThat(character.id()).isEqualTo("aylin-izmir");
                        assertThat(context.messages())
                                .extracting(Chat.Message::text)
                                .containsExactly("Merhaba Aylin 👋");
                        return "Merhaba! Günün nasıl geçti?";
                    }

                    public String summarize(String previous, List<Chat.Message> messages) {
                        throw new AssertionError("unexpected summarization");
                    }
                };
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            jdbc.update(
                    "UPDATE agent.agent_account SET username=?,password=?,enabled=true WHERE"
                        + " character_id=?",
                    "test-agent",
                    "local-test-password",
                    "aylin-izmir");
            var account = new JdbcAgentAccounts(jdbc).enabled().getFirst();
            var client =
                    new InstantlyAccountClient(
                            URI.create("http://127.0.0.1:" + port),
                            account,
                            http,
                            json,
                            store,
                            peers,
                            new IdentityKeyStore(keys, cipher, json));
            try (var transport =
                    new InstantlyTransport(
                            List.of(client),
                            http,
                            json,
                            new ReceiveMessage(store, Duration.ZERO))) {
                var processor =
                        new ReplyProcessor(
                                store,
                                catalog,
                                model,
                                transport,
                                frame ->
                                        InstantlyAccountClient.uuid(
                                                json.readTree(frame).path("payload"),
                                                "clientMessageId"),
                                8);
                try (var workers =
                        new ReplyWorkers(store, processor, transport::readyCharacters, 2)) {
                    transport.start();
                    workers.start();
                    await().atMost(Duration.ofSeconds(25))
                            .untilAsserted(
                                    () ->
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT count(*) FROM"
                                                                        + " agent.reply_job WHERE"
                                                                        + " state='DONE'",
                                                                    Long.class))
                                                    .isEqualTo(1));
                    assertThat(backend.ackAfterCommit.get()).isTrue();
                    assertThat(backend.outboundFrames.get()).isEqualTo(2);
                    assertThat(backend.exactRetry.get()).isTrue();
                    assertThat(backend.decryptedReply.get())
                            .isEqualTo("Merhaba! Günün nasıl geçti?");
                    assertThat(modelCalls).hasValue(1);
                    assertThat(backend.refreshes.get()).isGreaterThanOrEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM agent.chat_message", Long.class))
                            .isEqualTo(2);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM agent.reply_job", Long.class))
                            .isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM agent.peer_key", Long.class))
                            .isEqualTo(1);
                    assertThat(transport.states()).containsEntry("aylin-izmir", "ready");
                }
            }
            var missingKeys =
                    new InstantlyAccountClient(
                            URI.create("http://127.0.0.1:" + port),
                            account,
                            http,
                            json,
                            store,
                            peers,
                            new IdentityKeyStore(keys.resolve("lost"), cipher, json));
            assertThatThrownBy(missingKeys::initialize)
                    .hasMessage("registered_identity_has_no_local_keys");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSocket
    static class FakeBackendConfiguration {
        @Bean
        FakeApi fakeApi(JsonMapper json, JdbcTemplate jdbc) throws Exception {
            return new FakeApi(json, jdbc);
        }

        @Bean
        WebSocketConfigurer fakeSocket(FakeApi api) {
            return registry -> registry.addHandler(api.socket(), "/api/v1/chat/ws");
        }
    }

    @RestController
    static class FakeApi {
        final JsonMapper json;
        final JdbcTemplate jdbc;
        final UUID member = UUID.randomUUID(),
                ownId = UUID.randomUUID(),
                peerId = UUID.randomUUID(),
                conversation = UUID.randomUUID(),
                message = UUID.randomUUID(),
                delivery = UUID.randomUUID(),
                challenge = UUID.randomUUID();
        final KeyPair peerEncryption = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        final KeyPair peerSigning = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        volatile String encryptionPublic, signingPublic, fingerprint;
        volatile JsonNode firstOutbound;
        final AtomicInteger refreshes = new AtomicInteger(), outboundFrames = new AtomicInteger();
        final AtomicBoolean ackAfterCommit = new AtomicBoolean(), exactRetry = new AtomicBoolean();
        final AtomicReference<String> decryptedReply = new AtomicReference<>();

        FakeApi(JsonMapper json, JdbcTemplate jdbc) throws Exception {
            this.json = json;
            this.jdbc = jdbc;
        }

        @PostMapping("/api/v1/auth/agents/login")
        Map<String, Object> login(@RequestBody Map<String, String> body) {
            assertThat(body)
                    .containsEntry("username", "test-agent")
                    .containsEntry("password", "local-test-password");
            return tokens(30);
        }

        @PostMapping("/api/v1/auth/refresh")
        Map<String, Object> refresh(@RequestBody Map<String, String> body) {
            assertThat(body).containsEntry("refreshToken", "test-refresh");
            refreshes.incrementAndGet();
            return tokens(300);
        }

        Map<String, Object> tokens(int seconds) {
            return Map.of(
                    "memberUid",
                    member,
                    "accessToken",
                    "test-access",
                    "refreshToken",
                    "test-refresh",
                    "accessTokenExpiresAt",
                    Instant.now().plusSeconds(seconds).toString());
        }

        @GetMapping("/api/v1/profiles/me")
        Map<String, Object> profile(@RequestHeader("Authorization") String auth) {
            assertThat(auth).isEqualTo("Bearer test-access");
            return Map.of("isAi", true);
        }

        @GetMapping("/api/v1/chat/crypto-identity")
        Map<String, Object> identity() {
            var result = new HashMap<String, Object>();
            result.put("state", fingerprint == null ? "UNREGISTERED" : "REGISTERED");
            if (fingerprint != null) result.put("registeredKey", registered());
            result.put(
                    "challenge",
                    Map.of(
                            "challengeId",
                            challenge,
                            "challenge",
                            "dGVzdC1jaGFsbGVuZ2U",
                            "proofFormatVersion",
                            1));
            return result;
        }

        @PutMapping("/api/v1/chat/crypto-identity")
        Map<String, Object> reconcile(@RequestBody Map<String, String> body) throws Exception {
            assertThat(body.get("suite")).isEqualTo(ChatEnvelope.SUITE);
            String proof =
                    String.join(
                            "\n",
                            "instantly-e2e-key-proof-v1",
                            "action=RECONCILE",
                            "memberId=" + member,
                            "challengeId=" + challenge,
                            "challenge=dGVzdC1jaGFsbGVuZ2U",
                            "suite=X25519_ED25519_V1",
                            "encryptionPublicKey=" + body.get("encryptionPublicKey"),
                            "signingPublicKey=" + body.get("signingPublicKey"),
                            "expectedCurrentKeyId=");
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(
                    ChatEnvelope.publicKey(
                            "Ed25519", ChatEnvelope.decode(body.get("signingPublicKey"))));
            verifier.update(proof.getBytes(StandardCharsets.UTF_8));
            assertThat(verifier.verify(ChatEnvelope.decode(body.get("proof")))).isTrue();
            encryptionPublic = body.get("encryptionPublicKey");
            signingPublic = body.get("signingPublicKey");
            fingerprint =
                    ChatEnvelope.fingerprint(
                            ChatEnvelope.decode(encryptionPublic),
                            ChatEnvelope.decode(signingPublic));
            return registered();
        }

        Map<String, Object> registered() {
            return Map.of("keyId", ownId, "suite", ChatEnvelope.SUITE, "fingerprint", fingerprint);
        }

        @GetMapping("/api/v1/chat/conversations/{id}/crypto-identity")
        Map<String, Object> peer(@PathVariable UUID id) {
            assertThat(id).isEqualTo(conversation);
            return Map.of(
                    "conversationId",
                    conversation,
                    "keyId",
                    peerId,
                    "suite",
                    ChatEnvelope.SUITE,
                    "encryptionPublicKey",
                    ChatEnvelope.encode(ChatEnvelope.raw(peerEncryption.getPublic())),
                    "signingPublicKey",
                    ChatEnvelope.encode(ChatEnvelope.raw(peerSigning.getPublic())),
                    "fingerprint",
                    ChatEnvelope.fingerprint(
                            ChatEnvelope.raw(peerEncryption.getPublic()),
                            ChatEnvelope.raw(peerSigning.getPublic())));
        }

        Map<String, Object> incoming() {
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "t",
                                    "text",
                                    "body",
                                    "Merhaba Aylin 👋",
                                    "sentAt",
                                    Instant.now().toString()));
            return Map.of(
                    "messageId",
                    message,
                    "conversationId",
                    conversation,
                    "conversationSequence",
                    1,
                    "outgoing",
                    false,
                    "deliveryId",
                    delivery,
                    "senderKeyId",
                    peerId,
                    "recipientKeyId",
                    ownId,
                    "envelopeVersion",
                    1,
                    "encryptedEnvelope",
                    ChatEnvelope.seal(
                            peerEncryption.getPrivate(),
                            ChatEnvelope.decode(encryptionPublic),
                            conversation,
                            peerId,
                            ownId,
                            body.getBytes(StandardCharsets.UTF_8)));
        }

        WebSocketHandler socket() {
            return new TextWebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    send(session, "connection.ready", null, Map.of("heartbeatIntervalSeconds", 20));
                    send(
                            session,
                            "sync.batch",
                            null,
                            Map.of("messages", List.of(incoming()), "hasMore", false));
                }

                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage text)
                        throws Exception {
                    var frame = json.readTree(text.getPayload());
                    var payload = frame.path("payload");
                    String requestId = frame.path("requestId").asText();
                    switch (frame.path("type").asText()) {
                        case "delivery.ack" -> {
                            boolean persisted =
                                    jdbc.queryForObject(
                                                            "SELECT count(*) FROM"
                                                                    + " agent.chat_message WHERE"
                                                                    + " message_id=?",
                                                            Long.class,
                                                            message)
                                                    == 1
                                            && jdbc.queryForObject(
                                                            "SELECT count(*) FROM agent.reply_job"
                                                                + " WHERE incoming_message_id=?",
                                                            Long.class,
                                                            message)
                                                    == 1;
                            ackAfterCommit.set(persisted);
                            assertThat(persisted).isTrue();
                            send(
                                    session,
                                    "delivery.acknowledged",
                                    requestId,
                                    Map.of("updated", 1, "unreadCount", 0));
                            send(session, "sync.complete", null, Map.of());
                        }
                        case "message.send" -> {
                            assertThat(payload.has("isAi")).isFalse();
                            byte[] plaintext =
                                    ChatEnvelope.open(
                                            peerEncryption.getPrivate(),
                                            ChatEnvelope.decode(encryptionPublic),
                                            conversation,
                                            ownId,
                                            peerId,
                                            payload.path("encryptedEnvelope").asText());
                            decryptedReply.set(json.readTree(plaintext).path("body").asText());
                            if (outboundFrames.incrementAndGet() == 1) {
                                firstOutbound = frame;
                                session.close(CloseStatus.GOING_AWAY);
                            } else {
                                exactRetry.set(firstOutbound.equals(frame));
                                send(
                                        session,
                                        "message.accepted",
                                        requestId,
                                        Map.of(
                                                "clientMessageId",
                                                payload.path("clientMessageId").asText()));
                            }
                        }
                        default -> {}
                    }
                }
            };
        }

        void send(WebSocketSession session, String type, String requestId, Object payload)
                throws Exception {
            var frame = new HashMap<String, Object>();
            frame.put("v", 1);
            frame.put("type", type);
            frame.put("requestId", requestId);
            frame.put("payload", payload);
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
        }
    }
}
