package com.ofaladag.instantly.agents.adapter.out.backend;

import com.ofaladag.instantly.agents.adapter.out.crypto.*;
import com.ofaladag.instantly.agents.application.port.out.*;
import com.ofaladag.instantly.agents.domain.Chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** One synchronized rotating authentication session per account. Never logs response bodies. */
public final class InstantlyAccountClient {
    private final URI base;
    private final AccountConfig account;
    private final HttpClient http;
    private final JsonMapper json;
    private final AgentStore store;
    private final PeerKeyStore peerKeys;
    private final IdentityKeyStore keys;
    private UUID memberId;
    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;
    private IdentityKeyStore.Identity identity;
    private UUID ownKeyId;

    public InstantlyAccountClient(
            URI base,
            AccountConfig account,
            HttpClient http,
            JsonMapper json,
            AgentStore store,
            PeerKeyStore peerKeys,
            IdentityKeyStore keys) {
        if (base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || base.getHost() == null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("backend_url_must_be_origin");
        if (!base.getScheme().equals("https")
                && !(base.getScheme().equals("http")
                        && Set.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost())))
            throw new IllegalArgumentException("backend_requires_https_except_loopback");
        this.base = base;
        this.account = account;
        this.http = http;
        this.json = json;
        this.store = store;
        this.peerKeys = peerKeys;
        this.keys = keys;
    }

    public String characterId() {
        return account.characterId();
    }

    public synchronized void initialize() {
        token();
        var profile = request("GET", "/api/v1/profiles/me", null, accessToken);
        if (!profile.path("isAi").asBoolean()) throw new Chat.PermanentFailure("account_is_not_ai");
        store.bind(characterId(), memberId, base.toString().replaceAll("/$", ""));
        reconcile();
    }

    public synchronized String token() {
        if (expiresAt.isAfter(Instant.now().plusSeconds(60))) return accessToken;
        JsonNode response;
        if (refreshToken != null) {
            try {
                response =
                        request(
                                "POST",
                                "/api/v1/auth/refresh",
                                Map.of("refreshToken", refreshToken),
                                null);
            } catch (BackendFailure failure) {
                if (failure.status != 401 && failure.status != 403) throw failure;
                response = login();
            }
        } else response = login();
        UUID member = uuid(response, "memberUid");
        if (memberId != null && !memberId.equals(member))
            throw new Chat.PermanentFailure("account_identity_changed");
        memberId = member;
        accessToken = required(response, "accessToken");
        refreshToken = required(response, "refreshToken");
        expiresAt = Instant.parse(required(response, "accessTokenExpiresAt"));
        return accessToken;
    }

    private JsonNode login() {
        return request(
                "POST",
                "/api/v1/auth/agents/login",
                Map.of("username", account.username(), "password", account.password()),
                null);
    }

    public synchronized Instant expiresAt() {
        return expiresAt;
    }

    public URI socketUri() {
        return URI.create(
                (base.getScheme().equals("https") ? "wss" : "ws")
                        + "://"
                        + base.getRawAuthority()
                        + "/api/v1/chat/ws");
    }

    public synchronized void reconcile() {
        var state = request("GET", "/api/v1/chat/crypto-identity", null, token());
        boolean registered = state.hasNonNull("registeredKey");
        identity = keys.load(characterId(), !registered);
        if (registered
                && !identity.fingerprint()
                        .equals(required(state.get("registeredKey"), "fingerprint")))
            throw new Chat.PermanentFailure("identity_key_mismatch");
        var challenge = state.get("challenge");
        if (challenge.path("proofFormatVersion").asInt() != 1)
            throw new Chat.PermanentFailure("unsupported_key_proof_version");
        String proof =
                String.join(
                        "\n",
                        "instantly-e2e-key-proof-v1",
                        "action=RECONCILE",
                        "memberId=" + memberId,
                        "challengeId=" + required(challenge, "challengeId"),
                        "challenge=" + required(challenge, "challenge"),
                        "suite=" + ChatEnvelope.SUITE,
                        "encryptionPublicKey=" + identity.encryptionPublic(),
                        "signingPublicKey=" + identity.signingPublic(),
                        "expectedCurrentKeyId=");
        var response =
                request(
                        "PUT",
                        "/api/v1/chat/crypto-identity",
                        Map.of(
                                "challengeId",
                                required(challenge, "challengeId"),
                                "suite",
                                ChatEnvelope.SUITE,
                                "encryptionPublicKey",
                                identity.encryptionPublic(),
                                "signingPublicKey",
                                identity.signingPublic(),
                                "proof",
                                identity.sign(proof)),
                        token());
        if (!identity.fingerprint().equals(required(response, "fingerprint")))
            throw new Chat.PermanentFailure("identity_key_mismatch");
        ownKeyId = uuid(response, "keyId");
    }

    public synchronized String prepare(UUID conversation, String text) {
        var peer = fetchPeer(conversation);
        String plaintext =
                json.writeValueAsString(
                        Map.of(
                                "t",
                                "text",
                                "body",
                                text,
                                "sentAt",
                                Instant.now()
                                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                        .toString()));
        String envelope =
                ChatEnvelope.seal(
                        identity.encryption().getPrivate(),
                        ChatEnvelope.decode(peer.encryptionPublicKey()),
                        conversation,
                        ownKeyId,
                        peer.id(),
                        plaintext.getBytes(StandardCharsets.UTF_8));
        return json.writeValueAsString(
                Map.of(
                        "v",
                        1,
                        "type",
                        "message.send",
                        "requestId",
                        UUID.randomUUID().toString(),
                        "payload",
                        Map.of(
                                "clientMessageId",
                                UUID.randomUUID().toString(),
                                "conversationId",
                                conversation.toString(),
                                "senderKeyId",
                                ownKeyId.toString(),
                                "recipientKeyId",
                                peer.id().toString(),
                                "envelopeVersion",
                                1,
                                "encryptedEnvelope",
                                envelope)));
    }

    public synchronized Chat.Incoming decrypt(JsonNode message) {
        if (message.hasNonNull("media")) throw new Chat.PermanentFailure("media_not_supported");
        UUID conversation = uuid(message, "conversationId"),
                sender = uuid(message, "senderKeyId"),
                recipient = uuid(message, "recipientKeyId");
        if (message.path("envelopeVersion").asInt() != 1 || !recipient.equals(ownKeyId))
            throw new Chat.PermanentFailure("unavailable_identity_key");
        var peer =
                peerKeys.find(characterId(), conversation, sender)
                        .orElseGet(() -> fetchPeer(conversation));
        if (!sender.equals(peer.id())) throw new Chat.PermanentFailure("unavailable_peer_key");
        byte[] plaintext =
                ChatEnvelope.open(
                        identity.encryption().getPrivate(),
                        ChatEnvelope.decode(peer.encryptionPublicKey()),
                        conversation,
                        sender,
                        recipient,
                        required(message, "encryptedEnvelope"));
        var content = json.readTree(plaintext);
        if (!content.path("t").asText().equals("text"))
            throw new Chat.PermanentFailure("message_type_not_supported");
        return new Chat.Incoming(
                characterId(),
                conversation,
                uuid(message, "messageId"),
                message.path("conversationSequence").asLong(),
                required(content, "body"),
                json.writeValueAsString(message));
    }

    private PeerKeyStore.PeerKey fetchPeer(UUID conversation) {
        var response =
                request(
                        "GET",
                        "/api/v1/chat/conversations/" + conversation + "/crypto-identity",
                        null,
                        token());
        if (!conversation.equals(uuid(response, "conversationId"))
                || !ChatEnvelope.SUITE.equals(required(response, "suite")))
            throw new Chat.PermanentFailure("invalid_peer_bundle");
        String enc = required(response, "encryptionPublicKey"),
                sign = required(response, "signingPublicKey"),
                fingerprint = required(response, "fingerprint");
        byte[] encBytes = ChatEnvelope.decode(enc), signBytes = ChatEnvelope.decode(sign);
        if (encBytes.length != 32
                || signBytes.length != 32
                || !ChatEnvelope.fingerprint(encBytes, signBytes).equals(fingerprint))
            throw new Chat.PermanentFailure("invalid_peer_fingerprint");
        var peer = new PeerKeyStore.PeerKey(uuid(response, "keyId"), enc, sign, fingerprint);
        peerKeys.save(characterId(), conversation, peer);
        return peer;
    }

    private JsonNode request(String method, String path, Object body, String bearer) {
        var builder =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else
            builder.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try {
            // Consume the body within the HTTP request deadline, not an unbounded InputStream read.
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new BackendFailure(response.statusCode());
            if (response.body().length > 100000)
                throw new IllegalStateException("backend_response_too_large");
            return json.readTree(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backend_interrupted");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("backend_unavailable");
        }
    }

    public static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(required(node, field));
    }

    public static String required(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.asText().isBlank())
            throw new IllegalArgumentException("invalid_backend_field_" + field);
        return value.asText();
    }

    public static final class BackendFailure extends RuntimeException {
        public final int status;

        public BackendFailure(int status) {
            super("backend_http_" + status);
            this.status = status;
        }
    }
}
