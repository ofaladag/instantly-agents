package com.ofaladag.instantly.agents.application.port.out;

import com.ofaladag.instantly.agents.domain.Chat;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AgentStore {
    void bind(String characterId, UUID memberId, String backendUrl);

    void receive(Chat.Incoming message, Duration delay);

    Optional<Chat.Job> claim(Set<String> readyCharacters);

    Chat.Context context(Chat.Job job);

    void summarize(Chat.Job job, String summary, long through);

    void saveReply(Chat.Job job, String text);

    void saveFrame(Chat.Job job, String frame);

    void clearFrame(Chat.Job job);

    void complete(Chat.Job job, UUID clientMessageId, String text);

    void fail(Chat.Job job, String code, boolean permanent, int maxAttempts);
}
