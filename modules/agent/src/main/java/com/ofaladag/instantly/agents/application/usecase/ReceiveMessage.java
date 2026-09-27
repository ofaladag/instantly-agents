package com.ofaladag.instantly.agents.application.usecase;

import com.ofaladag.instantly.agents.application.port.in.ReceiveMessageUseCase;
import com.ofaladag.instantly.agents.application.port.out.AgentStore;
import com.ofaladag.instantly.agents.domain.Chat;

import lombok.RequiredArgsConstructor;

import java.time.Duration;

@RequiredArgsConstructor
public final class ReceiveMessage implements ReceiveMessageUseCase {
    private final AgentStore store;
    private final Duration delay;

    public void receive(Chat.Incoming message) {
        if (message.sequence() < 1
                || message.text() == null
                || message.text().isBlank()
                || message.text().length() > 32000)
            throw new IllegalArgumentException("invalid_incoming_text");
        store.receive(message, delay);
    }
}
