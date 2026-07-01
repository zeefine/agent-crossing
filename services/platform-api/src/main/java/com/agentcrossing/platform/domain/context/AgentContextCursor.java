package com.agentcrossing.platform.domain.context;

import java.time.Instant;
import java.util.Objects;

public record AgentContextCursor(
        String userId,
        String threadId,
        String agentId,
        Instant lastInjectedCreatedAt,
        String lastInjectedMessageId,
        Instant updatedAt) {
    public AgentContextCursor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
