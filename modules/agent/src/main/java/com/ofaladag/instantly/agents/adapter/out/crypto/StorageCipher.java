package com.ofaladag.instantly.agents.adapter.out.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The key lives outside PostgreSQL. Associated data prevents swapping rows or fields. */
public final class StorageCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public StorageCipher(String base64Key) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != 32)
            throw new IllegalArgumentException("AGENT_STORAGE_KEY must be base64 of 32 bytes");
        key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(String text, String context) {
        if (text == null) return null;
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(13 + encrypted.length)
                    .put((byte) 1)
                    .put(nonce)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("storage_encryption_failed");
        }
    }

    public String decrypt(byte[] encrypted, String context) {
        if (encrypted == null) return null;
        if (encrypted.length < 29 || encrypted[0] != 1)
            throw new IllegalStateException("invalid_storage_envelope");
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 1, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(encrypted, 13, encrypted.length - 13), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("storage_decryption_failed");
        }
    }
}
