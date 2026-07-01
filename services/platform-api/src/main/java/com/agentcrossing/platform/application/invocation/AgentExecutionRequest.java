package com.agentcrossing.platform.application.invocation;

/**
 * MODEL_SYNC(AgentExecutionRequest) — 改字段时三处同步（无 codegen）：
 *   · contracts/schemas/runtime-event.schema.json ($defs/AgentExecutionRequest)
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/AgentExecutionRequest.java  ← 本文件
 *   · services/agent-runtime/src/agent_runtime/contracts/models.py (class AgentExecutionRequest)
 */
public record AgentExecutionRequest(
        String invocationId,
        String userId,
        String taskId,
        String traceId,
        String agentId,
        String context,
        String callbackBaseUrl,
        AgentContextPack contextPack,
        String providerSessionId) {
}
