package com.ofaladag.instantly.agents.domain;

import java.util.List;
import java.util.UUID;

public final class Chat {
    private Chat() {}

    public record Incoming(
            String characterId,
            UUID conversationId,
            UUID messageId,
            long sequence,
            String text,
            String wirePayload) {}

    public record Job(
            UUID id,
            String characterId,
            UUID conversationId,
            UUID incomingMessageId,
            long position,
            int attempts,
            UUID leaseOwner,
            String reply,
            String outboundFrame) {}

    public record Message(long position, String role, String text) {}

    public record Context(String summary, long summaryThrough, List<Message> messages) {}

    public static final class LeaseLost extends RuntimeException {}

    public static final class KeyChanged extends RuntimeException {}

    public static final class PermanentFailure extends RuntimeException {
        public PermanentFailure(String code) {
            super(code);
        }
    }
}
