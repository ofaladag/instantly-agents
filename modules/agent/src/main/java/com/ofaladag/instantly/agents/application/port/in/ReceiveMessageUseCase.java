package com.ofaladag.instantly.agents.application.port.in;

import com.ofaladag.instantly.agents.domain.Chat;

public interface ReceiveMessageUseCase {
    void receive(Chat.Incoming message);
}
