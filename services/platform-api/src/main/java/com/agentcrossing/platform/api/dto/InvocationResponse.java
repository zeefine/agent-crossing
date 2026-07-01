package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.invocation.Invocation;

/**
 * MODEL_SYNC(Invocation) — 改字段时三处同步（无 codegen）：
 *   · contracts/schemas/invocation.schema.json
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/domain/invocation/Invocation.java
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/api/dto/InvocationResponse.java  ← 本文件
 */
public record InvocationResponse(
        String invocationId,
        String userId,
        String taskId,
        String traceId,
        String agentId,
        String status,
        String createdAt,
        String startedAt,
        String completedAt) {
    public static InvocationResponse from(Invocation invocation) {
        return new InvocationResponse(
                invocation.invocationId(),
                invocation.userId(),
                invocation.taskId(),
                invocation.traceId(),
                invocation.agentId(),
                invocation.status().wireValue(),
                invocation.createdAt().toString(),
                invocation.startedAt() == null ? null : invocation.startedAt().toString(),
                invocation.completedAt() == null ? null : invocation.completedAt().toString());
    }
}
