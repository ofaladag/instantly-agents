package com.ofaladag.instantly.agents.adapter.out.backend;

import com.ofaladag.instantly.agents.adapter.out.crypto.IdentityKeyStore;
import com.ofaladag.instantly.agents.application.port.out.CharacterCatalog;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public record AccountConfig(String characterId, String username, String password) {
    public AccountConfig {
        if (characterId == null
                || username == null
                || password == null
                || username.isBlank()
                || password.isBlank())
            throw new IllegalArgumentException("invalid_account_configuration");
    }

    @Override
    public String toString() {
        return "AccountConfig[characterId=" + characterId + ", credentials=REDACTED]";
    }

    public static List<AccountConfig> load(Path path, JsonMapper json, CharacterCatalog catalog) {
        try {
            IdentityKeyStore.requirePrivate(path);
            var data = json.readTree(Files.readString(path));
            if (!data.isArray() || data.isEmpty())
                throw new IllegalArgumentException("accounts_must_be_nonempty_array");
            var characters = new HashSet<String>();
            var usernames = new HashSet<String>();
            var accounts = new ArrayList<AccountConfig>();
            for (var node : data) {
                var account =
                        new AccountConfig(
                                node.path("characterId").asText(),
                                node.path("username").asText(),
                                node.path("password").asText());
                catalog.get(account.characterId());
                if (!characters.add(account.characterId())
                        || !usernames.add(account.username().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("duplicate_agent_account");
                accounts.add(account);
            }
            return List.copyOf(accounts);
        } catch (IOException failure) {
            throw new IllegalStateException("accounts_file_unreadable");
        }
    }
}
