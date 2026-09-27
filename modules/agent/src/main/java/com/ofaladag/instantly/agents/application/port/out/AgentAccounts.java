package com.ofaladag.instantly.agents.application.port.out;

import com.ofaladag.instantly.agents.domain.AgentAccount;

import java.util.List;

public interface AgentAccounts {
    List<AgentAccount> enabled();
}
