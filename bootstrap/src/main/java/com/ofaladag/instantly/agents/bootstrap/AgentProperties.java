package com.ofaladag.instantly.agents.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;

@ConfigurationProperties("agents")
public record AgentProperties(
        boolean enabled,
        URI backendUrl,
        Path keyDirectory,
        String identityKey,
        String model,
        String openaiApiKey,
        int concurrency,
        int maxAttempts,
        int replyDelaySeconds) {
    @Override
    public String toString() {
        return "AgentProperties[enabled=" + enabled + ", secrets=REDACTED]";
    }
}
