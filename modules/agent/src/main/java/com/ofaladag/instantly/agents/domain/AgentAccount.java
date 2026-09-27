package com.ofaladag.instantly.agents.domain;

public record AgentAccount(String characterId, String username, String password) {
    public AgentAccount {
        if (characterId == null
                || characterId.isBlank()
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank()) throw new IllegalArgumentException("invalid_agent_account");
    }

    @Override
    public String toString() {
        return "AgentAccount[characterId=" + characterId + ", credentials=REDACTED]";
    }
}
