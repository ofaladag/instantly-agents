package com.ofaladag.instantly.agents.adapter.out.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Wire-compatible with Instantly iOS ChatEnvelope.swift v1. */
public final class ChatEnvelope {
    public static final String SUITE = "X25519_ED25519_V1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChatEnvelope() {}

    public static String seal(
            PrivateKey own,
            byte[] peer,
            UUID conversation,
            UUID sender,
            UUID recipient,
            byte[] plaintext) {
        if (plaintext.length > 65507) throw new IllegalArgumentException("message_too_large");
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] aad = routing(conversation, sender, recipient);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, own, peer, aad, nonce, plaintext);
        return encode(
                ByteBuffer.allocate(13 + encrypted.length)
                        .put((byte) 1)
                        .put(nonce)
                        .put(encrypted)
                        .array());
    }

    public static byte[] open(
            PrivateKey own,
            byte[] peer,
            UUID conversation,
            UUID sender,
            UUID recipient,
            String envelope) {
        if (envelope.length() > 87382) throw new IllegalArgumentException("invalid_envelope");
        byte[] wire = decode(envelope);
        if (wire.length < 29 || wire.length > 65536 || wire[0] != 1)
            throw new IllegalArgumentException("invalid_envelope");
        return crypt(
                Cipher.DECRYPT_MODE,
                own,
                peer,
                routing(conversation, sender, recipient),
                Arrays.copyOfRange(wire, 1, 13),
                Arrays.copyOfRange(wire, 13, wire.length));
    }

    private static byte[] crypt(
            int mode, PrivateKey own, byte[] peer, byte[] aad, byte[] nonce, byte[] input) {
        try {
            var agreement = KeyAgreement.getInstance("X25519");
            agreement.init(own);
            agreement.doPhase(publicKey("X25519", peer), true);
            byte[] shared = agreement.generateSecret();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            "instantly-chat-msg-v1".getBytes(StandardCharsets.US_ASCII),
                            "HmacSHA256"));
            byte[] prk = mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(aad);
            byte[] derived = mac.doFinal(new byte[] {1});
            var cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(mode, new SecretKeySpec(derived, "ChaCha20"), new IvParameterSpec(nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("envelope_authentication_failed");
        }
    }

    public static byte[] routing(UUID conversation, UUID sender, UUID recipient) {
        var buffer = ByteBuffer.allocate(49).put((byte) 1);
        for (UUID id : List.of(conversation, sender, recipient))
            buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    public static PublicKey publicKey(String algorithm, byte[] raw)
            throws GeneralSecurityException {
        if (raw.length != 32) throw new IllegalArgumentException("invalid_public_key");
        byte[] prefix =
                HexFormat.of()
                        .parseHex(
                                algorithm.equals("X25519")
                                        ? "302a300506032b656e032100"
                                        : "302a300506032b6570032100");
        return KeyFactory.getInstance(algorithm)
                .generatePublic(
                        new X509EncodedKeySpec(
                                ByteBuffer.allocate(prefix.length + 32)
                                        .put(prefix)
                                        .put(raw)
                                        .array()));
    }

    public static byte[] raw(PublicKey key) {
        byte[] encoded = key.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    public static String fingerprint(byte[] encryption, byte[] signing) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update("instantly-e2e-key-fingerprint-v1".getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(SUITE.getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(encryption);
            sha.update((byte) 0);
            sha.update(signing);
            return encode(sha.digest());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("sha256_unavailable");
        }
    }

    public static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("invalid_base64url");
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!encode(decoded).equals(value))
            throw new IllegalArgumentException("noncanonical_base64url");
        return decoded;
    }
}
