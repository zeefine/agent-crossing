package com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper;

import com.agentcrossing.platform.domain.agent.Agent;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMapper {
    void upsert(Agent agent);

    Agent findByAgentId(@Param("agentId") String agentId);

    List<Agent> findAll();
}
