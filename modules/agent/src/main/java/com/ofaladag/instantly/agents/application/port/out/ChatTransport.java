package com.ofaladag.instantly.agents.application.port.out;

import java.util.UUID;

public interface ChatTransport {
    boolean ready(String characterId);

    String prepare(String characterId, UUID conversationId, String text);

    void send(String characterId, String frozenFrame);

    void reconcileKeys(String characterId);
}
