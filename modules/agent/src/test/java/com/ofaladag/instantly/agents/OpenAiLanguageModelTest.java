package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ofaladag.instantly.agents.adapter.out.openai.OpenAiLanguageModel;
import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;
import com.ofaladag.instantly.agents.domain.CharacterProfile;
import com.ofaladag.instantly.agents.domain.Chat;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class OpenAiLanguageModelTest {
    @Test
    void sendsPersonaAndIsolatedRolesWithoutRemoteConversationStorage() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var json = new JsonMapper();
        var status = new AtomicReference<>("completed");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/responses",
                exchange -> {
                    request.set(json.readTree(exchange.getRequestBody().readAllBytes()));
                    byte[] body =
                            ("""
                            {"id":"resp_test","object":"response","created_at":1,"status":"%s","model":"test-model",
                             "output":[{"id":"msg_test","type":"message","status":"completed","role":"assistant",
                               "content":[{"type":"output_text","text":"Merhaba, günün nasıl geçti?","annotations":[]}]}]}
                            """)
                                    .formatted(status.get())
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        var client =
                OpenAIOkHttpClient.builder()
                        .apiKey("local-test-only")
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        try {
            var catalog = mock(CharacterCatalog.class);
            var profile =
                    new CharacterProfile(
                            "aylin-izmir",
                            "Aylin",
                            "female",
                            27,
                            "Türkiye",
                            "İzmir",
                            "tr",
                            "Europe/Istanbul",
                            "ceramic artist",
                            List.of("pottery", "coastal walks", "indie films"),
                            "Warm and observant.");
            when(catalog.get("aylin-izmir")).thenReturn(profile);
            when(catalog.commonInstructions())
                    .thenReturn(
                            new ClassPathResource("prompts/social-policy.md")
                                    .getContentAsString(StandardCharsets.UTF_8));
            var model = new OpenAiLanguageModel(client, "test-model", catalog);
            var context =
                    new Chat.Context(
                            "User likes tea",
                            1,
                            List.of(
                                    new Chat.Message(2, "user", "Merhaba"),
                                    new Chat.Message(2, "assistant", "Selam"),
                                    new Chat.Message(3, "user", "Nasılsın?")));
            assertThat(model.reply(catalog.get("aylin-izmir"), context))
                    .isEqualTo("Merhaba, günün nasıl geçti?");
            assertThat(request.get().path("store").asBoolean(true)).isFalse();
            assertThat(request.get().path("model").asText()).isEqualTo("test-model");
            assertThat(request.get().path("instructions").asText())
                    .contains("Aylin", "ceramic artist", "without sexual content");
            assertThat(request.get().path("input").get(2).path("role").asText())
                    .isEqualTo("assistant");
            assertThat(request.get().has("previous_response_id")).isFalse();
            assertThat(request.get().has("conversation")).isFalse();
            status.set("incomplete");
            assertThatThrownBy(() -> model.reply(catalog.get("aylin-izmir"), context))
                    .hasMessage("model_response_incomplete");
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
