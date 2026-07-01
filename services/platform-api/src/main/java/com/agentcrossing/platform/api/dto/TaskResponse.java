package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.task.Task;
import java.util.List;

/**
 * MODEL_SYNC(Task wire) — 改 wire 字段时三处同步（无 codegen）：
 *   · contracts/schemas/task.schema.json
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/TaskResponse.java  ← 本文件
 *   · services/platform-web/lib/api.ts (export type Task)
 *
 * 注意：domain Task.java 不含 dependsOn；dependsOn 由 TaskDependencyRepository/task_dependency 组装。
 */
public record TaskResponse(
        String taskId,
        String userId,
        String traceId,
        String createdByTaskId,
        List<String> dependsOn,
        String status,
        String source,
        Integer depth,
        String agentId,
        String context,
        String createdAt,
        String updatedAt) {
    public static TaskResponse from(Task task, List<String> dependsOn) {
        return new TaskResponse(
                task.taskId(),
                task.userId(),
                task.traceId(),
                task.createdByTaskId(),
                List.copyOf(dependsOn),
                task.status().wireValue(),
                task.source().wireValue(),
                task.depth(),
                task.agentId(),
                task.context(),
                task.createdAt().toString(),
                task.updatedAt().toString());
    }
}
