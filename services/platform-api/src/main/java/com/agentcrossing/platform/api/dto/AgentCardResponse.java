package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;

public record AgentCardResponse(
        String agentId,
        String displayName,
        String role,
        List<String> capabilities,
        List<String> tools,
        String status,
        int runningInvocations,
        int processingTasks) {
    public static AgentCardResponse of(Agent agent, int runningInvocations, int processingTasks) {
        String status = runningInvocations > 0 || processingTasks > 0 ? "running" : "idle";
        return new AgentCardResponse(
                agent.agentId(),
                agent.displayName(),
                agent.role(),
                agent.capabilities(),
                agent.tools(),
                status,
                runningInvocations,
                processingTasks);
    }
}
