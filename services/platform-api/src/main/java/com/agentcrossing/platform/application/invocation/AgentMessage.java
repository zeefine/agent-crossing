package com.agentcrossing.platform.application.invocation;

import java.time.Instant;

/**
 * MODEL_SYNC(AgentMessage) — 改字段时三处同步（无 codegen）：
 *   · contracts/schemas/agent-message.schema.json
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/application/invocation/AgentMessage.java  ← 本文件
 *   · services/agent-runtime/src/agent_runtime/contracts/models.py (class AgentMessage)
 */
public record AgentMessage(
        String invocationId,
        String taskId,
        String traceId,
        String agentId,
        AgentMessageType type,
        String content,
        Object raw,
        Instant createdAt) {
}

