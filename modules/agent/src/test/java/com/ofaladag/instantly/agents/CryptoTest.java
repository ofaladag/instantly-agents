package com.ofaladag.instantly.agents;

import static org.assertj.core.api.Assertions.*;

import com.ofaladag.instantly.agents.adapter.out.crypto.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

class CryptoTest {
    @TempDir Path directory;
    private static final String IDENTITY_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void decryptsIndependentAppleCryptoKitFixture() throws Exception {
        var fixture =
                new JsonMapper()
                        .readTree(getClass().getResourceAsStream("/ios-envelope-vector.json"));
        byte[] raw = ChatEnvelope.decode(fixture.get("recipientPrivate").asText());
        byte[] prefix = HexFormat.of().parseHex("302e020100300506032b656e04220420");
        byte[] pkcs8 =
                java.nio.ByteBuffer.allocate(prefix.length + 32).put(prefix).put(raw).array();
        var key = KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        var plaintext =
                ChatEnvelope.open(
                        key,
                        ChatEnvelope.decode(fixture.get("senderPublic").asText()),
                        UUID.fromString(fixture.get("conversation").asText()),
                        UUID.fromString(fixture.get("sender").asText()),
                        UUID.fromString(fixture.get("recipient").asText()),
                        fixture.get("envelope").asText());
        assertThat(new String(plaintext, StandardCharsets.UTF_8))
                .isEqualTo(fixture.get("plaintext").asText());
    }

    @Test
    void roundTripRejectsTamperingAndWrongRouting() throws Exception {
        var alice = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        var bob = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        UUID conversation = UUID.randomUUID(),
                sender = UUID.randomUUID(),
                recipient = UUID.randomUUID();
        byte[] text = "Türkçe 🫖 日本語".getBytes(StandardCharsets.UTF_8);
        String wire =
                ChatEnvelope.seal(
                        alice.getPrivate(),
                        ChatEnvelope.raw(bob.getPublic()),
                        conversation,
                        sender,
                        recipient,
                        text);
        assertThat(
                        ChatEnvelope.open(
                                bob.getPrivate(),
                                ChatEnvelope.raw(alice.getPublic()),
                                conversation,
                                sender,
                                recipient,
                                wire))
                .isEqualTo(text);
        assertThatThrownBy(
                        () ->
                                ChatEnvelope.open(
                                        bob.getPrivate(),
                                        ChatEnvelope.raw(alice.getPublic()),
                                        conversation,
                                        recipient,
                                        sender,
                                        wire))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] tampered = ChatEnvelope.decode(wire);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(
                        () ->
                                ChatEnvelope.open(
                                        bob.getPrivate(),
                                        ChatEnvelope.raw(alice.getPublic()),
                                        conversation,
                                        sender,
                                        recipient,
                                        ChatEnvelope.encode(tampered)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChatEnvelope.decode(wire + "="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityCipherBindsCharactersAndKeysSurviveRestart() throws Exception {
        var cipher = new StorageCipher(IDENTITY_KEY);
        byte[] encrypted = cipher.encrypt("private identity", "identity/aylin-izmir");
        assertThat(new String(encrypted, StandardCharsets.UTF_8))
                .doesNotContain("private identity");
        assertThat(cipher.decrypt(encrypted, "identity/aylin-izmir")).isEqualTo("private identity");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "identity/deniz-izmir"))
                .isInstanceOf(IllegalStateException.class);
        var store = new IdentityKeyStore(directory, cipher, new JsonMapper());
        var first = store.load("aylin-izmir", true);
        var second =
                new IdentityKeyStore(directory, new StorageCipher(IDENTITY_KEY), new JsonMapper())
                        .load("aylin-izmir", false);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.encryption().getPrivate().getEncoded())
                .isEqualTo(second.encryption().getPrivate().getEncoded());
        assertThatThrownBy(() -> store.load("deniz-izmir", false))
                .hasMessage("registered_identity_has_no_local_keys");
        IdentityKeyStore.requirePrivate(directory.resolve("aylin-izmir.key"));
    }
}
