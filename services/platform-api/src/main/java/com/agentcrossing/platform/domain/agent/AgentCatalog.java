package com.agentcrossing.platform.domain.agent;

import java.util.List;
import java.util.Optional;

public interface AgentCatalog {
    Agent save(Agent agent);

    boolean exists(String agentId);

    Optional<Agent> findByAgentId(String agentId);

    List<Agent> findAll();
}
