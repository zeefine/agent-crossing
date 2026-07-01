package com.agentcrossing.platform.application.parser;

import java.util.List;
import java.util.Objects;

/**
 * MODEL_SYNC(ParsedTask) — 改字段时三处同步（无 codegen）：
 *   · contracts/schemas/parser.schema.json ($defs/ParsedTask)
 *   · services/platform-api/src/main/java/com/agentcrossing/platform/application/parser/ParsedTask.java  ← 本文件
 *   · services/agent-runtime/src/agent_runtime/contracts/models.py (class ParsedTask)
 */
public record ParsedTask(String taskId, String agentId, String context, List<String> dependsOn) {
    public ParsedTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(context, "context must not be null");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
