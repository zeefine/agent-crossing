package com.agentcrossing.platform.application.agent;

import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentRegistry;
import com.agentcrossing.platform.infrastructure.config.AgentCatalogProperties;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgentInitializer implements ApplicationRunner {
    private static final List<Agent> DEFAULT_AGENTS = List.of(new Agent(
            "opencode",
            "OpenCode",
            "CLI coding agent for implementation, reasoning, and codebase operations.",
            List.of("code reasoning", "implementation", "command-line execution", "project analysis"),
            List.of("opencode-cli", "filesystem", "shell")),
            new Agent(
                    "claudecode",
                    "ClaudeCode",
                    "CLI coding agent for complex reasoning, code review, and implementation.",
                    List.of(
                            "complex reasoning",
                            "code review",
                            "architecture analysis",
                            "implementation",
                            "command-line execution"),
                    List.of("claude-code-cli", "filesystem", "shell")));

    private final AgentRegistry agentRegistry;
    private final AgentCatalogProperties agentCatalogProperties;

    public DefaultAgentInitializer(AgentRegistry agentRegistry, AgentCatalogProperties agentCatalogProperties) {
        this.agentRegistry = agentRegistry;
        this.agentCatalogProperties = agentCatalogProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Agent> configuredAgents = agentCatalogProperties.getAgents().stream()
                .map(AgentCatalogProperties.AgentDefinition::toAgent)
                .toList();
        if (configuredAgents.isEmpty()) {
            DEFAULT_AGENTS.forEach(this::register);
            return;
        }
        configuredAgents.forEach(this::register);
    }

    private void register(Agent agent) {
        agentRegistry.register(agent);
    }
}
