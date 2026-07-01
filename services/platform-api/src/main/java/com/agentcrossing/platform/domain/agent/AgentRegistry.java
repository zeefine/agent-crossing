package com.agentcrossing.platform.domain.agent;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AgentRegistry {
    private final AgentCatalog agentCatalog;

    public AgentRegistry(AgentCatalog agentCatalog) {
        this.agentCatalog = agentCatalog;
    }

    public Agent register(Agent agent) {
        return agentCatalog.save(agent);
    }

    public boolean exists(String agentId) {
        return agentCatalog.exists(agentId);
    }

    public Optional<Agent> findByAgentId(String agentId) {
        return agentCatalog.findByAgentId(agentId);
    }

    public List<Agent> findAll() {
        return agentCatalog.findAll();
    }
}
