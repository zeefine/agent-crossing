package com.agentcrossing.platform.application.invocation;

import java.util.List;
import java.util.Map;

public record AgentExecutionResult(List<AgentMessage> messages) {
    public boolean hasError() {
        return messages.stream().anyMatch(message -> message.type() == AgentMessageType.ERROR);
    }

    public String errorOutput() {
        StringBuilder builder = new StringBuilder();
        for (AgentMessage message : messages) {
            if (message.type() == AgentMessageType.ERROR && message.content() != null && !message.content().isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(message.content());
            }
        }
        return builder.isEmpty() ? "Agent runtime returned an error" : builder.toString();
    }

    public String aggregateOutput() {
        StringBuilder builder = new StringBuilder();
        for (AgentMessage message : messages) {
            if ((message.type() == AgentMessageType.TEXT_DELTA || message.type() == AgentMessageType.MESSAGE)
                    && message.content() != null) {
                builder.append(message.content());
                if (message.type() == AgentMessageType.MESSAGE) {
                    builder.append('\n');
                }
            }
        }
        return builder.toString().trim();
    }

    public String providerSessionId() {
        for (AgentMessage message : messages) {
            if (message.raw() instanceof Map<?, ?> raw) {
                Object value = raw.get("providerSessionId");
                if (value == null) {
                    value = raw.get("sessionId");
                }
                if (value instanceof String sessionId && !sessionId.isBlank()) {
                    return sessionId;
                }
            }
        }
        return null;
    }
}
