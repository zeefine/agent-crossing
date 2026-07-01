package com.agentcrossing.platform.api.dto;

import com.agentcrossing.platform.domain.agent.Agent;

public record AgentStatusResponse(
        String agentId,
        String displayName,
        String status,
        int runningInvocations,
        int processingTasks) {
    public static AgentStatusResponse of(Agent agent, int runningInvocations, int processingTasks) {
        String status = runningInvocations > 0 || processingTasks > 0 ? "running" : "idle";
        return new AgentStatusResponse(
                agent.agentId(),
                agent.displayName(),
                status,
                runningInvocations,
                processingTasks);
    }
}
