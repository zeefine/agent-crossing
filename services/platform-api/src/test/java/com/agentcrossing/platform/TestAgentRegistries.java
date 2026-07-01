package com.agentcrossing.platform;

import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.domain.agent.InMemoryAgentCatalog;
import java.util.List;

public final class TestAgentRegistries {
    private TestAgentRegistries() {}

    public static AgentRegistry withDefaultAgent() {
        AgentRegistry registry = new AgentRegistry(new InMemoryAgentCatalog());
        registry.register(new Agent(
                "opencode",
                "OpenCode",
                "CLI coding agent for implementation, reasoning, and codebase operations.",
                List.of("code reasoning", "implementation", "command-line execution", "project analysis"),
                List.of("opencode-cli", "filesystem", "shell")));
        return registry;
    }
}
