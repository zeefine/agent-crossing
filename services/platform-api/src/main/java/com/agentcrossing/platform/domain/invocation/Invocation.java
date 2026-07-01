package com.agentcrossing.platform.domain.invocation;

import java.time.Instant;
import java.util.Objects;

/**
 * MODEL_SYNC(Invocation) — 改字段时三处同步（无 codegen）：
 *   · contracts/schemas/invocation.schema.json
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/invocation/Invocation.java  ← 本文件
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/InvocationResponse.java
 */
public record Invocation(
        String invocationId,
        String userId,
        String taskId,
        String traceId,
        String agentId,
        InvocationStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt) {
    public Invocation {
        Objects.requireNonNull(invocationId, "invocationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public Invocation withStatus(InvocationStatus nextStatus) {
        Instant now = Instant.now();
        Instant nextStartedAt = startedAt;
        Instant nextCompletedAt = completedAt;
        if (nextStatus == InvocationStatus.RUNNING && nextStartedAt == null) {
            nextStartedAt = now;
        }
        if (nextStatus == InvocationStatus.SUCCEEDED
                || nextStatus == InvocationStatus.FAILED
                || nextStatus == InvocationStatus.CANCELED) {
            nextCompletedAt = now;
        }
        return new Invocation(
                invocationId,
                userId,
                taskId,
                traceId,
                agentId,
                nextStatus,
                createdAt,
                nextStartedAt,
                nextCompletedAt);
    }
}
