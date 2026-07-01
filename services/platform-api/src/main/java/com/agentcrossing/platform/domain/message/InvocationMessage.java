package com.agentcrossing.platform.domain.message;

import com.agentcrossing.platform.application.invocation.AgentMessageType;
import java.time.Instant;
import java.util.Objects;

public record InvocationMessage(
        String messageId,
        String userId,
        String invocationId,
        String taskId,
        String traceId,
        String agentId,
        AgentMessageType type,
        String content,
        Object raw,
        Instant createdAt) {
    public InvocationMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(invocationId, "invocationId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

}
