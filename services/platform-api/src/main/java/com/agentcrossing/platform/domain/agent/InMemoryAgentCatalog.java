package com.agentcrossing.platform.domain.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentCatalog implements AgentCatalog {
    private final ConcurrentMap<String, Agent> agents = new ConcurrentHashMap<>();

    @Override
    public Agent save(Agent agent) {
        agents.put(agent.agentId(), agent);
        return agent;
    }

    @Override
    public boolean exists(String agentId) {
        return agents.containsKey(agentId);
    }

    @Override
    public Optional<Agent> findByAgentId(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public List<Agent> findAll() {
        return agents.values().stream()
                .sorted(Comparator.comparing(Agent::agentId))
                .toList();
    }
}
