package com.agentcrossing.platform.domain.task;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable idempotency mapping for agent-created tasks.
 *
 * <p>The agent-owned client task id is unique only within its source task. The platform assigns a
 * globally safe {@code taskId}; retries resolve this mapping instead of allocating a new task.
 */
public record TaskCreation(
        String sourceTaskId,
        String clientTaskId,
        String taskId,
        String userId,
        String traceId,
        String idempotencyKey,
        Instant createdAt) {
    public TaskCreation {
        Objects.requireNonNull(sourceTaskId, "sourceTaskId must not be null");
        Objects.requireNonNull(clientTaskId, "clientTaskId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sourceTaskId.isBlank() || clientTaskId.isBlank() || taskId.isBlank() || userId.isBlank()
                || traceId.isBlank() || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("task creation fields must not be blank");
        }
    }
}
