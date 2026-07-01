package com.agentcrossing.platform.domain.session;

import java.time.Instant;
import java.util.Objects;

public record AgentSession(
        String userId,
        String threadId,
        String traceId,
        String agentId,
        String provider,
        String providerSessionId,
        Instant createdAt,
        Instant updatedAt) {
    public AgentSession {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerSessionId, "providerSessionId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (providerSessionId.isBlank()) {
            throw new IllegalArgumentException("providerSessionId must not be blank");
        }
    }

}
