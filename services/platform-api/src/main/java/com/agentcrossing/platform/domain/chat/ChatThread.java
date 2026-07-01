package com.agentcrossing.platform.domain.chat;

import java.time.Instant;
import java.util.Objects;

public record ChatThread(
        String threadId,
        String userId,
        String title,
        ChatThreadStatus status,
        String traceId,
        Instant createdAt,
        Instant updatedAt) {
    public ChatThread {
        Objects.requireNonNull(threadId, "threadId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public ChatThread withStatus(ChatThreadStatus nextStatus) {
        return new ChatThread(threadId, userId, title, nextStatus, traceId, createdAt, Instant.now());
    }

    public ChatThread withTitle(String nextTitle) {
        return new ChatThread(threadId, userId, nextTitle, status, traceId, createdAt, Instant.now());
    }

    public ChatThread withTraceId(String nextTraceId) {
        return new ChatThread(threadId, userId, title, status, nextTraceId, createdAt, Instant.now());
    }
}
