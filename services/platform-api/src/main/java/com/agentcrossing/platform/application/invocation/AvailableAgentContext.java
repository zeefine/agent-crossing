package com.agentcrossing.platform.application.invocation;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;

public record AvailableAgentContext(
        String agentId,
        String displayName,
        String role,
        List<String> capabilities,
        List<String> tools) {
    public AvailableAgentContext {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static AvailableAgentContext from(Agent agent) {
        return new AvailableAgentContext(
                agent.agentId(),
                agent.displayName(),
                agent.role(),
                agent.capabilities(),
                agent.tools());
    }
}
