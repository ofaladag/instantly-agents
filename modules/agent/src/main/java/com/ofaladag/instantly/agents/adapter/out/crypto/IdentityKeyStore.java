package com.ofaladag.instantly.agents.adapter.out.crypto;

import lombok.RequiredArgsConstructor;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Map;

@RequiredArgsConstructor
public final class IdentityKeyStore {
    private final Path directory;
    private final StorageCipher cipher;
    private final JsonMapper json;

    public record Identity(KeyPair encryption, KeyPair signing) {
        @Override
        public String toString() {
            return "Identity[REDACTED]";
        }

        public String encryptionPublic() {
            return ChatEnvelope.encode(ChatEnvelope.raw(encryption.getPublic()));
        }

        public String signingPublic() {
            return ChatEnvelope.encode(ChatEnvelope.raw(signing.getPublic()));
        }

        public String fingerprint() {
            return ChatEnvelope.fingerprint(
                    ChatEnvelope.raw(encryption.getPublic()),
                    ChatEnvelope.raw(signing.getPublic()));
        }

        public String sign(String text) {
            try {
                var signature = Signature.getInstance("Ed25519");
                signature.initSign(signing.getPrivate());
                signature.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return ChatEnvelope.encode(signature.sign());
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("key_signing_failed");
            }
        }
    }

    public synchronized Identity load(String character, boolean allowCreation) {
        if (!character.matches("[a-z][a-z0-9-]{2,63}"))
            throw new IllegalArgumentException("invalid_character_id");
        Path file = directory.resolve(character + ".key");
        try {
            if (Files.exists(file)) {
                requirePrivate(file);
                var data =
                        json.readTree(
                                cipher.decrypt(Files.readAllBytes(file), "identity/" + character));
                return new Identity(
                        pair(
                                "X25519",
                                data.get("encryptionPrivate").asText(),
                                data.get("encryptionPublic").asText()),
                        pair(
                                "Ed25519",
                                data.get("signingPrivate").asText(),
                                data.get("signingPublic").asText()));
            }
            if (!allowCreation)
                throw new IllegalStateException("registered_identity_has_no_local_keys");
            Files.createDirectories(
                    directory,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            var identity =
                    new Identity(
                            KeyPairGenerator.getInstance("X25519").generateKeyPair(),
                            KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            String encoded =
                    json.writeValueAsString(
                            Map.of(
                                    "encryptionPrivate",
                                    ChatEnvelope.encode(
                                            identity.encryption().getPrivate().getEncoded()),
                                    "encryptionPublic",
                                    identity.encryptionPublic(),
                                    "signingPrivate",
                                    ChatEnvelope.encode(
                                            identity.signing().getPrivate().getEncoded()),
                                    "signingPublic",
                                    identity.signingPublic()));
            Path temporary =
                    Files.createTempFile(
                            directory,
                            ".identity-",
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            try {
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    var buffer = ByteBuffer.wrap(cipher.encrypt(encoded, "identity/" + character));
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                // Persist the rename before the caller registers this identity with the backend.
                try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                    channel.force(true);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return identity;
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException("identity_key_storage_failed");
        }
    }

    private static KeyPair pair(String algorithm, String privateKey, String publicKey)
            throws GeneralSecurityException {
        return new KeyPair(
                ChatEnvelope.publicKey(algorithm, ChatEnvelope.decode(publicKey)),
                KeyFactory.getInstance(algorithm)
                        .generatePrivate(new PKCS8EncodedKeySpec(ChatEnvelope.decode(privateKey))));
    }

    public static void requirePrivate(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(file)
                        .equals(PosixFilePermissions.fromString("rw-------")))
            throw new IllegalStateException("secret_file_requires_mode_0600");
    }
}
