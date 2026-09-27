package com.ofaladag.instantly.agents.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PeerKeyStore {
    record PeerKey(
            UUID id, String encryptionPublicKey, String signingPublicKey, String fingerprint) {}

    Optional<PeerKey> find(String character, UUID conversation, UUID keyId);

    void save(String character, UUID conversation, PeerKey key);
}
