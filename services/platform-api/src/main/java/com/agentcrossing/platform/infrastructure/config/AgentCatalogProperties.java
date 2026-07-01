package com.agentcrossing.platform.infrastructure.config;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-crossing")
public class AgentCatalogProperties {
    private List<AgentDefinition> agents = List.of();

    public List<AgentDefinition> getAgents() {
        return agents;
    }

    public void setAgents(List<AgentDefinition> agents) {
        this.agents = agents == null ? List.of() : List.copyOf(agents);
    }

    public record AgentDefinition(
            String agentId,
            String displayName,
            String role,
            List<String> capabilities,
            List<String> tools) {
        public Agent toAgent() {
            return new Agent(agentId, displayName, role, capabilities, tools);
        }
    }
}
