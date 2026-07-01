package com.agentcrossing.platform.infrastructure.persistence.mybatis.repository;

import com.agentcrossing.platform.domain.agent.Agent;
import com.agentcrossing.platform.domain.agent.AgentCatalog;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper.AgentMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisAgentCatalog implements AgentCatalog {
    private final AgentMapper agentMapper;

    public MybatisAgentCatalog(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    @Override
    public Agent save(Agent agent) {
        agentMapper.upsert(agent);
        return agent;
    }

    @Override
    public boolean exists(String agentId) {
        return findByAgentId(agentId).isPresent();
    }

    @Override
    public Optional<Agent> findByAgentId(String agentId) {
        return Optional.ofNullable(agentMapper.findByAgentId(agentId));
    }

    @Override
    public List<Agent> findAll() {
        return agentMapper.findAll();
    }
}
