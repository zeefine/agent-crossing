package com.agentcrossing.platform.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcrossing.platform.application.agent.DefaultAgentInitializer;
import com.agentcrossing.platform.infrastructure.config.AgentCatalogProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRegistryTests {
    @Test
    void registryDoesNotWriteDefaultsDuringConstruction() {
        AgentRegistry registry = new AgentRegistry(new InMemoryAgentCatalog());

        assertThat(registry.exists("opencode")).isFalse();
    }

    @Test
    void initializerRegistersOpenCodeByDefault() {
        AgentRegistry registry = new AgentRegistry(new InMemoryAgentCatalog());

        new DefaultAgentInitializer(registry, new AgentCatalogProperties()).run(null);

        assertThat(registry.findByAgentId("opencode"))
                .contains(new Agent(
                        "opencode",
                        "OpenCode",
                        "CLI coding agent for implementation, reasoning, and codebase operations.",
                        List.of("code reasoning", "implementation", "command-line execution", "project analysis"),
                        List.of("opencode-cli", "filesystem", "shell")));
        assertThat(registry.exists("unknown-agent")).isFalse();
    }

    @Test
    void initializerRegistersConfiguredAgents() {
        AgentRegistry registry = new AgentRegistry(new InMemoryAgentCatalog());
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setAgents(List.of(new AgentCatalogProperties.AgentDefinition(
                "claude-code",
                "ClaudeCode",
                "CLI coding agent for code review and implementation.",
                List.of("code review", "implementation"),
                List.of("claude-code-cli", "filesystem"))));

        new DefaultAgentInitializer(registry, properties).run(null);

        assertThat(registry.findByAgentId("claude-code"))
                .contains(new Agent(
                        "claude-code",
                        "ClaudeCode",
                        "CLI coding agent for code review and implementation.",
                        List.of("code review", "implementation"),
                        List.of("claude-code-cli", "filesystem")));
        assertThat(registry.exists("opencode")).isFalse();
    }

    @Test
    void registersCustomAgentIntoBackingCatalog() {
        AgentRegistry registry = new AgentRegistry(new InMemoryAgentCatalog());

        registry.register(new Agent("codex-cli", "Codex CLI"));

        assertThat(registry.findByAgentId("codex-cli")).contains(new Agent("codex-cli", "Codex CLI"));
        assertThat(registry.findAll()).extracting(Agent::agentId).containsExactly("codex-cli");
    }
}
