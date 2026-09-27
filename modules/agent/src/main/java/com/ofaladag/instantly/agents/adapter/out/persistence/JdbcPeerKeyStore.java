package com.ofaladag.instantly.agents.adapter.out.persistence;

import com.ofaladag.instantly.agents.application.port.out.PeerKeyStore;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

@RequiredArgsConstructor
public final class JdbcPeerKeyStore implements PeerKeyStore {
    private final JdbcTemplate jdbc;

    public Optional<PeerKey> find(String character, UUID conversation, UUID id) {
        return jdbc
                .query(
                        "SELECT encryption_public_key,signing_public_key,fingerprint FROM"
                            + " agent.peer_key WHERE character_id=? AND conversation_id=? AND"
                            + " key_id=?",
                        (rs, _) ->
                                new PeerKey(id, rs.getString(1), rs.getString(2), rs.getString(3)),
                        character,
                        conversation,
                        id)
                .stream()
                .findFirst();
    }

    public void save(String character, UUID conversation, PeerKey key) {
        jdbc.update(
                "INSERT INTO"
                    + " agent.peer_key(character_id,conversation_id,key_id,encryption_public_key,signing_public_key,fingerprint)"
                    + " VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                character,
                conversation,
                key.id(),
                key.encryptionPublicKey(),
                key.signingPublicKey(),
                key.fingerprint());
        if (!find(character, conversation, key.id()).orElseThrow().equals(key))
            throw new IllegalStateException("peer_key_changed_in_place");
    }
}
