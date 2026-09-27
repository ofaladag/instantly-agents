package com.ofaladag.instantly.agents.adapter.out.persistence;

import com.ofaladag.instantly.agents.application.port.out.AgentAccounts;
import com.ofaladag.instantly.agents.domain.AgentAccount;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@RequiredArgsConstructor
public final class JdbcAgentAccounts implements AgentAccounts {
    private final JdbcTemplate jdbc;

    @Override
    public List<AgentAccount> enabled() {
        return jdbc.query(
                "SELECT character_id, username, password FROM agent.agent_account WHERE enabled"
                    + " ORDER BY character_id",
                (rs, _) ->
                        new AgentAccount(
                                rs.getString("character_id"),
                                rs.getString("username"),
                                rs.getString("password")));
    }
}
