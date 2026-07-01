package com.agentcrossing.platform.domain.task;

import java.time.Instant;
import java.util.Objects;

/**
 * MODEL_SYNC(Task domain) — 本文件只保存 task 节点字段。
 * Wire 层的 dependsOn 不在 domain Task 中，查询时由 TaskDependencyRepository/task_dependency 组装进 TaskResponse。
 * 改节点字段时同步检查：
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/task/Task.java  ← 本文件
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/TaskResponse.java
 *   · contracts/schemas/task.schema.json
 *   · services/platform-web/lib/api.ts (export type Task)
 */
public record Task(
        String taskId,
        String userId,
        String traceId,
        String createdByTaskId,
        TaskStatus status,
        TaskSource source,
        // MyBatis ConstructorResolver 按 boxed 找 ctor，primitive int 在 mysql 模式下匹配不到。
        // 详见 docs/mysql-mode-pitfalls.md §5。
        Integer depth,
        String agentId,
        String context,
        Instant createdAt,
        Instant updatedAt) {
    public Task {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(depth, "depth must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be greater than or equal to 0");
        }
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public Task withStatus(TaskStatus nextStatus) {
        return new Task(
                taskId,
                userId,
                traceId,
                createdByTaskId,
                nextStatus,
                source,
                depth,
                agentId,
                context,
                createdAt,
                Instant.now());
    }
}
